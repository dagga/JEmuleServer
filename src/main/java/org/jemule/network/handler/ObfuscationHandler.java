package org.jemule.network.handler;

import org.jemule.network.Packet;
import org.jemule.security.Obfuscation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Server side of eMule's obfuscated client -&gt; server TCP handshake (EncryptedStreamSocket.cpp, "Basic Obfuscated
 * Handshake Protocol Client &lt;-&gt; Server"):
 * <pre>
 * Client: &lt;marker 1 (not E3/C5/D4)&gt;&lt;g^a 96&gt;&lt;random padding 0-15&gt;                       (plain)
 * Server: &lt;g^b 96&gt; (plain) RC4[&lt;MAGICVALUE_SYNC 4&gt;&lt;methods supported 1&gt;&lt;method preferred 1&gt;&lt;padLen 1&gt;&lt;padding&gt;]
 * Client: RC4[&lt;MAGICVALUE_SYNC 4&gt;&lt;method selected 1&gt;&lt;padLen 1&gt;&lt;padding&gt;]   (sent together with its first packet)
 * S = (g^a)^b mod p (96 bytes, big-endian); server send key = MD5(S, 203), receive key = MD5(S, 34);
 * the first 1024 bytes of each RC4 stream are discarded. All integers are little-endian.
 * </pre>
 * The client's padding is not encrypted and its length is not transmitted: the server locates the start of the
 * encrypted stream by looking for the magic value at each of the 16 possible offsets.
 */
public class ObfuscationHandler {
    private static final Logger log = LoggerFactory.getLogger(ObfuscationHandler.class);

    static final int PRIMESIZE_BYTES = 96;
    static final int DHAGREEMENT_A_BITS = 128;
    static final int MAGICVALUE_SYNC = 0x835E6FC4;
    static final byte MAGICVALUE_REQUESTER = 34;
    static final byte MAGICVALUE_SERVER = (byte) 203;
    static final byte ENM_OBFUSCATION = 0x00;
    static final int MAX_CLIENT_PADDING = 15;

    static final BigInteger DH768_P = new BigInteger(1, new byte[] {
            (byte)0xF2,(byte)0xBF,(byte)0x52,(byte)0xC5,(byte)0x5F,(byte)0x58,(byte)0x7A,(byte)0xDD,(byte)0x53,(byte)0x71,(byte)0xA9,(byte)0x36,
            (byte)0xE8,(byte)0x86,(byte)0xEB,(byte)0x3C,(byte)0x62,(byte)0x17,(byte)0xA3,(byte)0x3E,(byte)0xC3,(byte)0x4C,(byte)0xB4,(byte)0x0D,
            (byte)0xC7,(byte)0x3A,(byte)0x41,(byte)0xA6,(byte)0x43,(byte)0xAF,(byte)0xFC,(byte)0xE7,(byte)0x21,(byte)0xFC,(byte)0x28,(byte)0x63,
            (byte)0x66,(byte)0x53,(byte)0x5B,(byte)0xDB,(byte)0xCE,(byte)0x25,(byte)0x9F,(byte)0x22,(byte)0x86,(byte)0xDA,(byte)0x4A,(byte)0x91,
            (byte)0xB2,(byte)0x07,(byte)0xCB,(byte)0xAA,(byte)0x52,(byte)0x55,(byte)0xD4,(byte)0xF6,(byte)0x1C,(byte)0xCE,(byte)0xAE,(byte)0xD4,
            (byte)0x5A,(byte)0xD5,(byte)0xE0,(byte)0x74,(byte)0x7D,(byte)0xF7,(byte)0x78,(byte)0x18,(byte)0x28,(byte)0x10,(byte)0x5F,(byte)0x34,
            (byte)0x0F,(byte)0x76,(byte)0x23,(byte)0x87,(byte)0xF8,(byte)0x8B,(byte)0x28,(byte)0x91,(byte)0x42,(byte)0xFB,(byte)0x42,(byte)0x68,
            (byte)0x8F,(byte)0x05,(byte)0x15,(byte)0x0F,(byte)0x54,(byte)0x8B,(byte)0x5F,(byte)0x43,(byte)0x6A,(byte)0xF7,(byte)0x0D,(byte)0xF3
    });
    private static final BigInteger G = BigInteger.TWO;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Detects an obfuscated connection from its first byte and performs the handshake.
     *
     * @return the stream to read packets from (decrypting if the connection is obfuscated); for an obfuscated
     *         connection the encrypting output stream is installed with {@link ClientContext#setWrappedOut}
     */
    public InputStream negotiateObfuscation(ClientContext context, InputStream in, OutputStream out) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(in, 1);
        int first = pin.read();
        if (first == -1) return pin;
        pin.unread(first);
        if (first == (Packet.PROTOCOL_ED2K & 0xFF) || first == (Packet.PROTOCOL_EMULE & 0xFF) || first == (Packet.PROTOCOL_ZLIB & 0xFF)) {
            return pin; // plain ed2k connection
        }

        SocketAddress remote = context.getSocket().getRemoteSocketAddress();
        pin.read(); // semi random "not protocol" marker
        byte[] ga = pin.readNBytes(PRIMESIZE_BYTES);
        if (ga.length < PRIMESIZE_BYTES) {
            throw new EOFException("Obfuscation handshake: incomplete DH value (" + ga.length + " bytes)");
        }

        // Key agreement
        BigInteger b = new BigInteger(DHAGREEMENT_A_BITS, RANDOM);
        byte[] gb = toFixedLength(G.modPow(b, DH768_P));
        byte[] s = toFixedLength(new BigInteger(1, ga).modPow(b, DH768_P));
        Obfuscation.RC4 sendKey = rc4(s, MAGICVALUE_SERVER);

        // Answer: <g^b> RC4[<magic><methods supported><method preferred><padLen><padding>]
        int padLen = RANDOM.nextInt(16);
        ByteBuffer answer = ByteBuffer.allocate(4 + 3 + padLen).order(ByteOrder.LITTLE_ENDIAN);
        answer.putInt(MAGICVALUE_SYNC).put(ENM_OBFUSCATION).put(ENM_OBFUSCATION).put((byte) padLen);
        byte[] padding = new byte[padLen];
        RANDOM.nextBytes(padding);
        answer.put(padding);
        byte[] encrypted = answer.array();
        sendKey.crypt(encrypted);
        byte[] response = new byte[PRIMESIZE_BYTES + encrypted.length];
        System.arraycopy(gb, 0, response, 0, PRIMESIZE_BYTES);
        System.arraycopy(encrypted, 0, response, PRIMESIZE_BYTES, encrypted.length);
        out.write(response);
        out.flush();

        // The client's unencrypted padding (0-15 bytes) precedes its encrypted answer: find where the
        // encrypted stream starts by decrypting the magic value at each possible offset.
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        received.writeBytes(pin.readNBytes(4));
        Obfuscation.RC4 receiveKey = null;
        for (int offset = 0; offset <= MAX_CLIENT_PADDING; offset++) {
            while (received.size() < offset + 4) {
                int next = pin.read();
                if (next == -1) throw new EOFException("Obfuscation handshake: connection closed");
                received.write(next);
            }
            Obfuscation.RC4 candidate = rc4(s, MAGICVALUE_REQUESTER);
            byte[] magic = Arrays.copyOfRange(received.toByteArray(), offset, offset + 4);
            candidate.crypt(magic);
            if (ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN).getInt() == MAGICVALUE_SYNC) {
                receiveKey = candidate;
                break;
            }
        }
        if (receiveKey == null) {
            throw new IOException("Obfuscation handshake failed: magic value not received from " + sanitize(remote));
        }

        InputStream encryptedIn = new ObfuscatedInputStream(pin, receiveKey);
        int method = encryptedIn.read();
        int clientPadLen = encryptedIn.read();
        if (method == -1 || clientPadLen == -1 || encryptedIn.readNBytes(clientPadLen).length < clientPadLen) {
            throw new EOFException("Obfuscation handshake: incomplete client answer");
        }
        if (method != ENM_OBFUSCATION) {
            throw new IOException("Obfuscation handshake: unsupported encryption method " + method);
        }

        log.info("Obfuscated connection established with {}", sanitize(remote));
        context.setObfuscated(true);
        context.setWrappedOut(new ObfuscatedOutputStream(out, sendKey));
        return encryptedIn;
    }

    /** RC4 keyed with MD5(S, magic), first 1024 bytes discarded (RC4CreateKey without bSkipDiscard). */
    static Obfuscation.RC4 rc4(byte[] s, byte magic) {
        Obfuscation.RC4 rc4 = new Obfuscation.RC4(Obfuscation.md5(s, new byte[] {magic}));
        rc4.crypt(new byte[1024]);
        return rc4;
    }

    /** Big-endian unsigned encoding on exactly 96 bytes, like CryptoPP::Integer::Encode. */
    static byte[] toFixedLength(BigInteger value) {
        byte[] in = value.toByteArray();
        byte[] out = new byte[PRIMESIZE_BYTES];
        int copyLen = Math.min(in.length, PRIMESIZE_BYTES);
        System.arraycopy(in, in.length - copyLen, out, PRIMESIZE_BYTES - copyLen, copyLen);
        return out;
    }

    private static String sanitize(SocketAddress addr) {
        return addr != null ? HandlerUtils.sanitize(addr.toString()) : "unknown";
    }

    public static class ObfuscatedInputStream extends InputStream {
        private final InputStream in;
        private final Obfuscation.RC4 rc4;

        public ObfuscatedInputStream(InputStream in, Obfuscation.RC4 rc4) {
            this.in = in;
            this.rc4 = rc4;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b == -1) return -1;
            byte[] data = {(byte) b};
            rc4.crypt(data);
            return data[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len);
            if (r > 0) rc4.crypt(b, off, r);
            return r;
        }
    }

    public static class ObfuscatedOutputStream extends OutputStream {
        private final OutputStream out;
        private final Obfuscation.RC4 rc4;

        public ObfuscatedOutputStream(OutputStream out, Obfuscation.RC4 rc4) {
            this.out = out;
            this.rc4 = rc4;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            byte[] data = {(byte) b};
            rc4.crypt(data);
            out.write(data[0]);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            byte[] copy = Arrays.copyOfRange(b, off, off + len);
            rc4.crypt(copy);
            out.write(copy);
        }

        @Override
        public synchronized void flush() throws IOException {
            out.flush();
        }
    }
}
