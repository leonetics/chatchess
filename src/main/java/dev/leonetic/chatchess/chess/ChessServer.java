package dev.leonetic.chatchess.chess;

import dev.leonetic.chatchess.ChatChess;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChessServer {
    public interface Api {
        String handle(String path, Map<String, String> params);
    }

    private static final Set<String> STATIC = Set.of(
        "index.html", "chess.js", "chessboard.min.js", "chessboard.min.css", "jquery.min.js",
        "img/wK.png", "img/wQ.png", "img/wR.png", "img/wB.png", "img/wN.png", "img/wP.png",
        "img/bK.png", "img/bQ.png", "img/bR.png", "img/bB.png", "img/bN.png", "img/bP.png");
    private static final int MAX_REQUEST = 16 * 1024;

    private final String key = UUID.randomUUID().toString().replace("-", "");
    private final Api api;
    private ServerSocket socket;

    public ChessServer(Api api) {
        this.api = api;
    }

    public synchronized String url() throws IOException {
        if (socket == null || socket.isClosed()) {
            socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::acceptLoop, "Chat Chess");
            thread.setDaemon(true);
            thread.start();
        }
        return "http://127.0.0.1:" + socket.getLocalPort() + "/?k=" + key;
    }

    private void acceptLoop() {
        ServerSocket server = socket;
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(5000);
                serve(client);
            } catch (IOException e) {
                if (!server.isClosed()) ChatChess.LOG.debug("Chess server request failed", e);
            } catch (RuntimeException e) {
                ChatChess.LOG.error("Chess server request failed", e);
            }
        }
    }

    private void serve(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null) return;
        int contentLength = 0;
        for (String header; (header = readLine(in)) != null && !header.isEmpty(); ) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                contentLength = Math.min(MAX_REQUEST, Integer.parseInt(header.substring(colon + 1).trim()));
            }
        }
        byte[] body = in.readNBytes(contentLength);

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String target = parts[1];
        int query = target.indexOf('?');
        String path = (query < 0 ? target : target.substring(0, query)).replaceFirst("^/+", "");
        Map<String, String> params = new HashMap<>();
        if (query >= 0) parse(target.substring(query + 1), params);
        if (body.length > 0) parse(new String(body, StandardCharsets.UTF_8), params);

        if (path.startsWith("api/")) {
            if (!key.equals(params.get("k"))) {
                respond(out, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String json = api.handle(path.substring(4), params);
            respond(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (path.isEmpty()) path = "index.html";
        if (!STATIC.contains(path)) {
            respond(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream resource = ChessServer.class.getResourceAsStream("/assets/chatchess/chess/" + path)) {
            if (resource == null) {
                respond(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(out, 200, contentType(path), resource.readAllBytes());
        }
    }

    private static void parse(String query, Map<String, String> params) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.0 " + status + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') return line.toString(StandardCharsets.US_ASCII).stripTrailing();
            if (line.size() >= MAX_REQUEST) throw new IOException("line too long");
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
    }
}
