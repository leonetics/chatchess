package dev.leonetic.chatchess.chess;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class ChessWire {
    public enum Type { INVITE, ACCEPT, DECLINE, START, MOVE, RESIGN, DRAW_OFFER, DRAW_ACCEPT }

    public record Message(Type type, int game, String from, String[] args) {
        public String arg(int i) {
            return i < args.length ? args[i] : "";
        }
    }

    private static final byte MAGIC = (byte) 0xC5;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    // anything outside the base64url alphabet splits words, so the whisper's own format around it doesn't matter
    private static final Pattern SPLIT = Pattern.compile("[^A-Za-z0-9_-]+");
    // nonce + magic + type + game + a 1 char body + crc
    private static final int MIN_BYTES = 1 + 1 + 1 + 4 + 1 + 2;

    private ChessWire() {}

    public static String encode(Type type, int game, String from, String... args) {
        StringBuilder body = new StringBuilder(from);
        for (String arg : args) body.append('|').append(arg);
        byte[] text = body.toString().getBytes(StandardCharsets.US_ASCII);

        ByteBuffer plain = ByteBuffer.allocate(1 + 1 + 4 + text.length + 2);
        plain.put(MAGIC).put((byte) type.ordinal()).putInt(game).put(text);
        plain.putShort((short) crc(plain.array(), plain.position()));

        byte nonce = (byte) ThreadLocalRandom.current().nextInt(256);
        byte[] out = new byte[plain.capacity() + 1];
        out[0] = nonce;
        System.arraycopy(plain.array(), 0, out, 1, plain.capacity());
        xor(out, nonce);
        return ENCODER.encodeToString(out);
    }

    public static Message find(String text) {
        if (text == null || text.length() < 12) return null;
        for (String word : SPLIT.split(text)) {
            Message message = decode(word);
            if (message != null) return message;
        }
        return null;
    }

    public static Message decode(String word) {
        // 4/3 of MIN_BYTES, rounded down: shorter can't be a message
        if (word.length() < MIN_BYTES * 4 / 3) return null;
        byte[] bytes;
        try {
            bytes = DECODER.decode(word);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length < MIN_BYTES) return null;
        xor(bytes, bytes[0]);
        if (bytes[1] != MAGIC) return null;

        int end = bytes.length - 2;
        int crc = ((bytes[end] & 0xFF) << 8) | (bytes[end + 1] & 0xFF);
        byte[] plain = new byte[end - 1];
        System.arraycopy(bytes, 1, plain, 0, plain.length);
        if (crc(plain, plain.length) != crc) return null;

        int typeIndex = plain[1] & 0xFF;
        if (typeIndex >= Type.values().length) return null;
        int game = ByteBuffer.wrap(plain, 2, 4).getInt();
        String[] fields = new String(plain, 6, plain.length - 6, StandardCharsets.US_ASCII).split("\\|", -1);
        String[] args = new String[fields.length - 1];
        System.arraycopy(fields, 1, args, 0, args.length);
        return new Message(Type.values()[typeIndex], game, fields[0], args);
    }

    // xorshift keystream from the nonce over everything after it
    private static void xor(byte[] bytes, byte nonce) {
        int state = 0x9E3779B9 ^ ((nonce & 0xFF) * 0x01000193);
        for (int i = 1; i < bytes.length; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            bytes[i] ^= (byte) state;
        }
    }

    private static int crc(byte[] bytes, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, length);
        return (int) (crc.getValue() & 0xFFFF);
    }
}
