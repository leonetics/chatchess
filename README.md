# Chat Chess

A [Meteor Client](https://meteorclient.com) addon for playing chess with other players on any Minecraft server, using
nothing but private messages. Both players need the addon installed; the server needs nothing.

## How it works

- Challenges, moves, resignations and draw offers are sent as short encoded tokens over `/msg`.
  Each token carries a game id, the sender's name and a checksum, so stray chat is never mistaken for a move.
- Incoming chess tokens are hidden from your chat, so a game doesn't flood it.
- The board opens in your web browser. The addon runs a small HTTP server bound to `127.0.0.1` on a random port,
  protected by a per-session key, that serves the board page and relays moves to and from the game.
- Move legality, checkmate and stalemate are checked in the browser with
  [chess.js](https://github.com/jhlywa/chess.js); the board is drawn with
  [chessboard.js](https://github.com/oakmac/chessboardjs).

## Commands

Commands use Meteor's prefix, `.` by default.

| Command                | Description                                                   |
|------------------------|---------------------------------------------------------------|
| `.chess <player>`      | Challenge a player. Challenges expire after 2 minutes.        |
| `.chess accept`        | Accept the latest challenge (or click **[Accept]** in chat).  |
| `.chess decline`       | Decline the latest challenge (or click **[Decline]** in chat).|
| `.chess open`          | Reopen the board in your browser.                             |
| `.chess resign`        | Resign the current game.                                      |
| `.chess`               | Show the current game's status.                               |

Colours are picked at random when a challenge is accepted. Draw offers are made from the board page.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Meteor Client](https://meteorclient.com) for Minecraft 26.3.
2. Put the Chat Chess JAR in your `mods` folder next to Meteor Client.

Requires Java 25.

## Building

```bash
./gradlew build
```

The JAR is written to `build/libs`. To test in a development client, run the `Minecraft Client` run configuration in
your IDE (or `./gradlew runClient`).

Versions of Minecraft, Fabric Loader, Loom and Meteor are set in `gradle/libs.versions.toml`.

## Project structure

```text
src/main
├── java/dev/leonetic/chatchess
│   ├── ChatChess.java          addon entrypoint, registers the command
│   ├── commands/Chess.java     the .chess command, game state and chat handling
│   ├── chess/ChessWire.java    encodes/decodes chess messages sent over /msg
│   ├── chess/ChessServer.java  local HTTP server for the browser board
│   └── mixin/                  hides chess messages from chat
└── resources
    ├── assets/chatchess/chess  board page, chess.js, chessboard.js, piece images
    ├── chatchess.mixins.json
    └── fabric.mod.json
```

## License

Chat Chess is available under the CC0 license (see `LICENSE`). Bundled third-party libraries keep their own licenses:
see `LICENSE-chess.js` and `LICENSE-chessboardjs` in `src/main/resources/assets/chatchess/chess`.
