package dev.leonetic.chatchess.commands;

import dev.leonetic.chatchess.ChatChess;
import dev.leonetic.chatchess.chess.ChessServer;
import dev.leonetic.chatchess.chess.ChessWire;
import dev.leonetic.chatchess.chess.ChessWire.Type;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.Blaze3D;
import dev.leonetic.chatchess.chess.ChessWire.Message;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.misc.text.MeteorClickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static dev.leonetic.chatchess.chess.ChessWire.Type.INVITE;

/**
 * The Meteor Client command API uses the <a href="https://github.com/Mojang/brigadier">same command system as Minecraft does</a>.
 */
public class Chess extends Command {
    /**
     * The {@code name} parameter should be in kebab-case.
     */
    public Chess() {
        super("chess", "Play a game of chess w/ someone via chat");
    }

    private static final long INVITE_TIMEOUT_MS = 120_000;
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    // the invite prompt has its own line, so a later info doesn't replace it
    private static final int ID = "chess-invite".hashCode();

    private record Pending(String name, int game, long time) {
        boolean fresh() {
            return System.currentTimeMillis() - time < INVITE_TIMEOUT_MS;
        }
    }

    private static final class Game {
        final int id;
        final String me, opponent;
        final boolean white;
        final List<String> moves = new ArrayList<>();
        // empty while it's being played, else how it ended
        String result = "";
        boolean drawOfferedByMe, drawOfferedByThem;

        Game(int id, String me, String opponent, boolean white) {
            this.id = id;
            this.me = me;
            this.opponent = opponent;
            this.white = white;
        }

        boolean myTurn() {
            return (moves.size() % 2 == 0) == white;
        }
    }

    private final ChessServer server = new ChessServer(this::api);
    // all three are touched from the main thread and the server's, so every use is under this
    private Pending outgoing, incoming, awaitingStart;
    private Game game;


    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        String text;

        if (event.packet instanceof ClientboundPlayerChatPacket chat) {
            text = chat.body().content();
        } else if (event.packet instanceof ClientboundSystemChatPacket chat && !chat.overlay()) {
            text = chat.content().getString();
        } else {
            return;
        }

        Message message = ChessWire.find(text);

        if (message != null) {
            mc.execute(() -> receive(message));
        }
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(ctx -> status())
            .then(literal("accept").executes(ctx -> accept()))
            .then(literal("decline").executes(ctx -> decline()))
            .then(literal("open").executes(ctx -> open()))
            .then(literal("resign").executes(ctx -> resign()))
            .then(argument("player", StringArgumentType.word())
                .suggests((ctx, suggestions) -> {
                    String input = suggestions.getRemainingLowerCase();
                    if (mc.getConnection() == null || mc.player == null) return suggestions.buildFuture();
                    for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                        String player = info.getProfile().name();
                        if (!player.equals(myName()) && player.toLowerCase().startsWith(input)) suggestions.suggest(player);
                    }
                    return suggestions.buildFuture();
                })
                .executes(ctx -> challenge(StringArgumentType.getString(ctx, "player"))));
    }

    private synchronized int status() {
        if (game != null && game.result.isEmpty()) {
            return reply("Playing " + game.opponent + " as " + (game.white ? "white" : "black")
                + ", move " + (game.moves.size() / 2 + 1) + ". .chess open to show the board");
        }
        if (outgoing != null && outgoing.fresh()) return reply("Waiting for " + outgoing.name + " to answer");
        return reply("Usage: .chess <player>, accept, decline, open, resign");
    }

    private synchronized int challenge(String name) {
        if (mc.player == null) return reply("You need to be in a world to do that");
        if (name.equalsIgnoreCase(myName())) return reply("You can't play yourself");
        if (game != null && game.result.isEmpty()) {
            return reply("You're already playing " + game.opponent + ", .chess resign first");
        }
        int id = ThreadLocalRandom.current().nextInt();
        outgoing = new Pending(name, id, System.currentTimeMillis());
        send(name, ChessWire.encode(INVITE, id, myName()));
        return reply("Challenged " + name + " to chess");
    }

    private synchronized int accept() {
        if (mc.player == null) return reply("You need to be in a world to do that");
        if (incoming == null || !incoming.fresh()) return reply("No chess challenge to accept");
        if (game != null && game.result.isEmpty()) {
            return reply("You're already playing " + game.opponent + ", .chess resign first");
        }
        awaitingStart = new Pending(incoming.name, incoming.game, System.currentTimeMillis());
        send(incoming.name, ChessWire.encode(Type.ACCEPT, incoming.game, myName()));
        String name = incoming.name;
        incoming = null;
        return reply("Accepted, waiting for " + name + " to pick colours");
    }

    private synchronized int decline() {
        if (incoming == null || mc.player == null) return reply("No chess challenge to decline");
        send(incoming.name, ChessWire.encode(Type.DECLINE, incoming.game, myName()));
        String name = incoming.name;
        incoming = null;
        return reply("Declined " + name + "'s challenge");
    }

    private synchronized int open() {
        if (game == null) return reply("No chess game to show");
        openBrowser();
        return reply("Opened the board");
    }

    private synchronized int resign() {
        if (game == null || !game.result.isEmpty()) return reply("No chess game to resign");
        resignGame();
        return reply("Resigned against " + game.opponent);
    }

    // --- chat in ---

//    // netty thread; handled on the main thread, like infoModule
//    @Subscribe
//    public void onPacketReceive(PacketReceiveEvent event) {
//        String text;
//        if (event.getPacket() instanceof ClientboundPlayerChatPacket chat) text = chat.body().content();
//        else if (event.getPacket() instanceof ClientboundSystemChatPacket chat && !chat.overlay()) text = chat.content().getString();
//        else return;
//        Message message = ChessWire.find(text);
//        if (message != null) mc.execute(() -> receive(message));
//    }

    private synchronized void receive(Message message) {
        if (mc.player == null || message.from().equalsIgnoreCase(myName())) return; // our own whisper, echoed back
        String from = message.from();
        switch (message.type()) {
            case INVITE -> {
                incoming = new Pending(from, message.game(), System.currentTimeMillis());

                Component invitation = Component.empty()
                    .append(Component.literal(from).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" challenged you to chess! "))
                    .append(button("[Accept]", ChatFormatting.GREEN, toString("accept"), "Play " + from))
                    .append(Component.literal(" "))
                    .append(button("[Decline]", ChatFormatting.RED, toString("decline"), "Decline"));

                ChatUtils.sendMsg(ID, null, null, invitation);
            }
            case ACCEPT -> {
                if (!matches(outgoing, message) || !outgoing.fresh()) return;
                outgoing = null;
                boolean white = ThreadLocalRandom.current().nextBoolean();
                game = new Game(message.game(), myName(), from, white);

                send(from, ChessWire.encode(Type.START, message.game(), myName(), white ? "b" : "w"));
                info(from + " accepted, you're " + (white ? "white" : "black"));
                openBrowser();
            }
            case DECLINE -> {
                if (!matches(outgoing, message)) return;
                outgoing = null;
                info(from + " declined your challenge");
            }
            case START -> {
                if (!matches(awaitingStart, message)) return;
                awaitingStart = null;
                boolean white = message.arg(0).equals("w");
                game = new Game(message.game(), myName(), from, white);
                info("Playing " + from + ", you're " + (white ? "white" : "black"));
                openBrowser();
            }
            case MOVE -> {
                if (!inGame(message)) return;
                int ply;
                try {
                    ply = Integer.parseInt(message.arg(0));
                } catch (NumberFormatException e) {
                    return;
                }
                String uci = message.arg(1);

                if (ply != game.moves.size() || game.myTurn() || !UCI.matcher(uci).matches()) return;
                game.moves.add(uci);
                game.drawOfferedByMe = game.drawOfferedByThem = false;
            }
            case RESIGN -> {
                if (!inGame(message)) return;
                game.result = from + " resigned, you win";
                info(game.result);
            }
            case DRAW_OFFER -> {
                if (!inGame(message)) return;
                game.drawOfferedByThem = true;
                info(from + " offers a draw");
            }
            case DRAW_ACCEPT -> {
                if (!inGame(message) || !game.drawOfferedByMe) return;
                game.result = "Draw agreed";
                info(from + " accepted the draw");
            }
        }
    }

    private static boolean matches(Pending pending, Message message) {
        return pending != null && pending.game == message.game() && pending.name.equalsIgnoreCase(message.from());
    }

    private boolean inGame(Message message) {
        return game != null && game.result.isEmpty() && game.id == message.game()
            && game.opponent.equalsIgnoreCase(message.from());
    }

    private synchronized String api(String path, Map<String, String> params) {
        JsonObject response = new JsonObject();
        if (game == null) {
            response.addProperty("error", "No game");
            return response.toString();
        }
        String error = switch (path) {
            case "state" -> null;
            case "move" -> move(params.getOrDefault("ply", ""), params.getOrDefault("uci", ""));
            case "resign" -> {
                if (!game.result.isEmpty()) yield "The game is over";
                resignGame();
                yield null;
            }
            case "draw" -> draw();
            case "resend" -> resend();
            case "over" -> {
                // the page saw mate or a forced draw; both pages see it, so nothing goes over the wire
                if (game.result.isEmpty()) game.result = params.getOrDefault("result", "Game over");
                yield null;
            }
            default -> "Unknown call";
        };
        if (error != null) response.addProperty("error", error);

        response.addProperty("game", Integer.toHexString(game.id));
        response.addProperty("me", game.me);
        response.addProperty("opponent", game.opponent);
        response.addProperty("color", game.white ? "w" : "b");
        response.addProperty("result", game.result);
        response.addProperty("drawOfferedByMe", game.drawOfferedByMe);
        response.addProperty("drawOfferedByThem", game.drawOfferedByThem);
        JsonArray moves = new JsonArray();
        game.moves.forEach(moves::add);
        response.add("moves", moves);
        return response.toString();
    }

    private String move(String ply, String uci) {
        if (!game.result.isEmpty()) return "The game is over";
        if (!game.myTurn()) return "It's not your turn";
        if (!ply.equals(String.valueOf(game.moves.size()))) return "Out of sync, reload the page";
        if (!UCI.matcher(uci).matches()) return "Bad move";
        game.moves.add(uci);
        game.drawOfferedByMe = game.drawOfferedByThem = false;
        send(game.opponent, ChessWire.encode(Type.MOVE, game.id, game.me, ply, uci));
        return null;
    }

    private String draw() {
        if (!game.result.isEmpty()) return "The game is over";
        if (game.drawOfferedByThem) {
            game.result = "Draw agreed";
            send(game.opponent, ChessWire.encode(Type.DRAW_ACCEPT, game.id, game.me));
        } else if (!game.drawOfferedByMe) {
            game.drawOfferedByMe = true;
            send(game.opponent, ChessWire.encode(Type.DRAW_OFFER, game.id, game.me));
        }
        return null;
    }

    private String resend() {
        if (!game.result.isEmpty()) return "The game is over";
        if (game.myTurn() || game.moves.isEmpty()) return "Your opponent is the one to move";
        int ply = game.moves.size() - 1;
        send(game.opponent, ChessWire.encode(Type.MOVE, game.id, game.me, String.valueOf(ply), game.moves.get(ply)));
        return null;
    }

    private void resignGame() {
        game.result = "You resigned, " + game.opponent + " wins";
        send(game.opponent, ChessWire.encode(Type.RESIGN, game.id, game.me));
    }

    private static void send(String to, String word) {
        mc.execute(() -> ChatUtils.sendPlayerMsg("/msg " + to + " " + word, true)); // antispam not needed since encode makes it inherently diff
    }

    private void openBrowser() {
        try {
            Blaze3D.openUri(URI.create(server.url()));
        } catch (IOException e) {
            ChatChess.LOG.error("Couldn't start the chess server", e);
            info("Couldn't start the chess board: " + e.getMessage());
        }
    }

    private int reply(String message) {
        info(message);
        return SINGLE_SUCCESS;
    }

    private static String myName() {
        return mc.player == null ? "" : mc.player.getGameProfile().name();
    }

    private static Component button(String text, ChatFormatting color, String command, String hover) {
        return Component.literal(text).withStyle(style -> style.withColor(color)
            .withClickEvent(new MeteorClickEvent(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
