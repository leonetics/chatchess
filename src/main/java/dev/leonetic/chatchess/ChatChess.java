package dev.leonetic.chatchess;

import dev.leonetic.chatchess.commands.Chess;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import org.slf4j.Logger;

public class ChatChess extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing Chat Chess");

        // commands
        Chess chess = new Chess();
        Commands.add(chess);
        MeteorClient.EVENT_BUS.subscribe(chess);
    }

    @Override
    public String getPackage() {
        return "dev.leonetic.chatchess";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Leonetic", "chat-chess");
    }
}
