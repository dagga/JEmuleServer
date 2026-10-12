package org.jemule.security;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;

/**
 * Obfuscation of the client &lt;-&gt; server UDP packets, as implemented by eMule's CEncryptedDatagramSocket
 * (EncryptedDatagramSocket.cpp, "Server Packets"):
 * <pre>
 * &lt;marker 1 (!= 0xE3)&gt;&lt;random key part 2&gt; RC4[&lt;MAGICVALUE_UDP_SYNC_SERVER 4&gt;&lt;padLen 1&gt;&lt;padding&gt;&lt;ed2k packet&gt;]
 * client -&gt; server key: MD5(&lt;base key 4&gt;&lt;0x6B&gt;&lt;random key part 2&gt;)
 * server -&gt; client key: MD5(&lt;base key 4&gt;&lt;0xA5&gt;&lt;random key part 2&gt;)
 * </pre>
 * No RC4 bytes are discarded for UDP. The base key is the server UDP key the client received in
 * OP_GLOBSERVSTATRES (one key per client IP, like ST_UDPKEY / ST_UDPKEYIP), or the challenge of a
 * "crypt ping" for the answer to that ping.
 */
public final class UdpObfuscation {
    static final int MAGICVALUE_UDP_SYNC_SERVER = 0x13EF24D5;
    static final byte MAGICVALUE_UDP_SERVERCLIENT = (byte) 0xA5;
    static final byte MAGICVALUE_UDP_CLIENTSERVER = (byte) 0x6B;
    static final int CRYPT_HEADER_WITHOUTPADDING = 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] SECRET = new byte[16];

    static {
        RANDOM.nextBytes(SECRET);
    }

    private UdpObfuscation() {}

    /** Server UDP key for a client IP (never 0: 0 means "no key" for eMule). */
    public static int serverKeyFor(InetAddress clientIp) {
        byte[] md5 = Obfuscation.md5(SECRET, clientIp.getAddress());
        int key = ByteBuffer.wrap(md5).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return key != 0 ? key : 1;
    }

    private static Obfuscation.RC4 key(int baseKey, byte magic, byte[] packet, int randomOffset) {
        byte[] keyData = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(baseKey).put(magic).put(packet[randomOffset]).put(packet[randomOffset + 1]).array();
        return new Obfuscation.RC4(Obfuscation.md5(keyData));
    }

    /**
     * Decrypts a packet sent by a client with CEncryptedDatagramSocket::EncryptSendServer.
     *
     * @return the plain ed2k packet (&lt;0xE3&gt;&lt;opcode&gt;...), or null if the packet is not encrypted with this key
     */
    public static byte[] decryptFromClient(byte[] packet, int baseKey) {
        if (packet.length <= CRYPT_HEADER_WITHOUTPADDING || packet[0] == (byte) 0xE3 || baseKey == 0) return null;
        Obfuscation.RC4 rc4 = key(baseKey, MAGICVALUE_UDP_CLIENTSERVER, packet, 1);
        byte[] header = java.util.Arrays.copyOfRange(packet, 3, 8);
        rc4.crypt(header);
        int magic = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (magic != MAGICVALUE_UDP_SYNC_SERVER) return null;
        int padLen = header[4] & 0x0F;
        int payloadLen = packet.length - CRYPT_HEADER_WITHOUTPADDING - padLen;
        if (payloadLen <= 0) return null;
        byte[] padding = new byte[padLen];
        rc4.crypt(padding);
        byte[] payload = java.util.Arrays.copyOfRange(packet, CRYPT_HEADER_WITHOUTPADDING + padLen, packet.length);
        rc4.crypt(payload);
        return payload;
    }

    /** Encrypts an answer the way CEncryptedDatagramSocket::DecryptReceivedServer expects it. */
    public static byte[] encryptForClient(byte[] plain, int baseKey) {
        byte[] out = new byte[CRYPT_HEADER_WITHOUTPADDING + plain.length];
        byte marker;
        do {
            marker = (byte) RANDOM.nextInt(256);
        } while (marker == (byte) 0xE3);
        out[0] = marker;
        out[1] = (byte) RANDOM.nextInt(256);
        out[2] = (byte) RANDOM.nextInt(256);
        Obfuscation.RC4 rc4 = key(baseKey, MAGICVALUE_UDP_SERVERCLIENT, out, 1);
        byte[] body = ByteBuffer.allocate(5 + plain.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGICVALUE_UDP_SYNC_SERVER).put((byte) 0).put(plain).array(); // no padding
        rc4.crypt(body);
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
