package org.jemule.network.handler;

public class HandlerUtils {

    public static String sanitize(String input) {
        if (input == null) return "";
        return input.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public static byte[] hashToBytes(String hex) {
        if (hex == null || hex.length() != 32) return new byte[16];
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    public static boolean isValidHash(String hash) {
        if (hash == null || hash.length() != 32) return false;
        return hash.matches("^[0-9a-fA-F]{32}$");
    }

    public static boolean isValidFilename(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.length() > 255) return false;
        for (char c : name.toCharArray()) {
            if (c < 32) return false;
        }
        return true;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}