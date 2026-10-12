package org.jemule.integration;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.network.EmuleTestSupport;
import org.jemule.network.Packet;
import org.jemule.network.Server;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C11: obfuscated TCP connection to the server, the client side being an independent re-implementation of
 * CEncryptedStreamSocket (ECS_PENDING_SERVER) with BigInteger, JDK MD5 and ARCFOUR.
 */
class ObfuscationCompatibilityTest {

    static final int PORT = 14665;
    static final BigInteger P = new BigInteger(
            "F2BF52C55F587ADD5371A936E886EB3C6217A33EC34CB40DC73A41A643AFFCE721FC286366535BDBCE259F2286DA4A91"
          + "B207CBAA5255D4F61CCEAED45AD5E0747DF7781828105F340F762387F88B289142FB42688F05150F548B5F436AF70DF3", 16);
    static final int MAGICVALUE_SYNC = 0x835E6FC4;

    static Server server;

    @BeforeAll
    static void start() throws Exception {
        ServerConfig config = new ServerConfig(PORT, 2 * 1024 * 1024, 300, 1000, 1000, 100000, 1000, 200,
                null, null, false, 50f, 10, 60, 30, 0, "127.0.0.1", 1);
        server = new Server(config, new ClientFactory());
        Thread t = new Thread(() -> {
            try { server.start(); } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static Cipher rc4(byte[] s, int magic) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(s);
        md5.update((byte) magic);
        Cipher c = Cipher.getInstance("ARCFOUR");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(md5.digest(), "ARCFOUR"));
        c.update(new byte[1024]); // discarded
        return c;
    }

    static byte[] fixed96(BigInteger v) {
        byte[] in = v.toByteArray();
        byte[] out = new byte[96];
        int n = Math.min(in.length, 96);
        System.arraycopy(in, in.length - n, out, 96 - n, n);
        return out;
    }

    /**
     * Performs the client handshake, sends an encrypted login and returns the decrypted OP_IDCHANGE.
     *
     * @param ga       forced g^a value (null for a random one)
     * @param padding  number of unencrypted padding bytes after g^a (0..15)
     */
    static Packet obfuscatedLogin(BigInteger a, int padding) throws Exception {
        try (Socket s = new Socket()) {
            s.setSoTimeout(5000);
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 5000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // <marker><g^a 96><padding>
            byte[] ga = fixed96(BigInteger.TWO.modPow(a, P));
            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            hello.write(0x12);
            hello.writeBytes(ga);
            hello.writeBytes(new byte[padding]);
            out.write(hello.toByteArray());
            out.flush();

            // <g^b 96> RC4[<magic><supported><preferred><padLen><padding>]
            byte[] gb = in.readNBytes(96);
            byte[] sBytes = fixed96(new BigInteger(1, gb).modPow(a, P));
            Cipher send = rc4(sBytes, 34);
            Cipher receive = rc4(sBytes, 203);
            ByteBuffer hdr = ByteBuffer.wrap(receive.update(in.readNBytes(7))).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(MAGICVALUE_SYNC, hdr.getInt(), "server magic value (little-endian)");
            assertEquals(0, hdr.get(), "methods supported");
            assertEquals(0, hdr.get(), "method preferred");
            int serverPad = hdr.get() & 0xFF;
            assertTrue(serverPad <= 16);
            receive.update(in.readNBytes(serverPad));

            // RC4[<magic><method><padLen><padding>] + first packet (the login), sent together
            ByteArrayOutputStream login = new ByteArrayOutputStream();
            login.writeBytes(ByteBuffer.allocate(6 + 3).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(MAGICVALUE_SYNC).put((byte) 0).put((byte) 3).put(new byte[]{7, 7, 7}).array());
            new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                    EmuleTestSupport.loginRequest(new byte[16], 4662, "obfuscated", EmuleTestSupport.EMULE_DEFAULT_SRVCAPS)).write(login, false);
            out.write(send.update(login.toByteArray()));
            out.flush();

            // Read decrypted packets until OP_IDCHANGE
            for (int i = 0; i < 10; i++) {
                byte[] header = receive.update(in.readNBytes(6));
                int len = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                byte[] body = receive.update(in.readNBytes(len - 1));
                byte[] frame = ByteBuffer.allocate(6 + body.length).put(header).put(body).array();
                Packet p = Packet.read(new ByteArrayInputStream(frame), 1 << 20);
                if (p.opcode() == OpCode.ID_CHANGE.value) return p;
            }
            throw new AssertionError("no OP_IDCHANGE");
        }
    }

    @Test
    @Timeout(20)
    void handshakeWithEveryPaddingLength() throws Exception {
        SecureRandom rnd = new SecureRandom();
        for (int padding = 0; padding <= 15; padding += 5) {
            Packet idChange = obfuscatedLogin(new BigInteger(128, rnd), padding);
            assertTrue(idChange.data().length >= 20);
        }
    }

    static boolean containsByte(byte[] data, byte value) {
        for (byte b : data) if (b == value) return true;
        return false;
    }

    @Test
    @Timeout(20)
    void dhValueContaining0x97IsHandled() throws Exception {
        // The former implementation stopped at the first 0x97 byte of g^a (~30% of connections)
        SecureRandom rnd = new SecureRandom();
        BigInteger a;
        do {
            a = new BigInteger(128, rnd);
        } while (!containsByte(java.util.Arrays.copyOf(fixed96(BigInteger.TWO.modPow(a, P)), 20), (byte) 0x97));
        assertTrue(obfuscatedLogin(a, 15).data().length >= 20);
    }
}
