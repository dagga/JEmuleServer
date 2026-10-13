package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.LoginHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** C16: HighID only for clients answering the server's test connection (OP_HELLO -> OP_HELLOANSWER). */
class LowIdCompatibilityTest {

    static final ServerConfig CONFIG = new ServerConfig(
            14664, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
            null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 2);

    /** Fake eMule client listening on a port; answers OP_HELLO like CUpDownClient::SendHelloAnswer if asked to. */
    static ServerSocket fakeClient(boolean answer, AtomicReference<byte[]> receivedHello) throws Exception {
        return fakeClient(answer, new byte[16], receivedHello);
    }

    /** Same, answering with the given user hash. */
    static ServerSocket fakeClient(boolean answer, byte[] userHash, AtomicReference<byte[]> receivedHello) throws Exception {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                Packet hello = Packet.read(s.getInputStream(), 65536);
                receivedHello.set(hello.data());
                if (answer) {
                    ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
                    b.put(userHash).putInt(0).putShort((short) ss.getLocalPort()).putInt(0).putInt(0).putShort((short) 0);
                    new Packet(Packet.PROTOCOL_ED2K, (byte) 0x4C, java.util.Arrays.copyOf(b.array(), b.position())).write(s.getOutputStream(), false);
                } else {
                    Thread.sleep(3000);
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    static int login(ClientRegistry registry, int listenPort) throws Exception {
        return login(registry, listenPort, new byte[16]);
    }

    static int login(ClientRegistry registry, int listenPort, byte[] userHash) throws Exception {
        FakeContext ctx = new FakeContext(new DummySocket(InetAddress.getLoopbackAddress(), 51000), CONFIG, registry, new FileIndex(null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                loginRequest(userHash, listenPort, "tester", EMULE_DEFAULT_SRVCAPS)), out);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        while (in.available() > 0) {
            Packet p = Packet.read(in, 65536);
            if (p.opcode() == OpCode.ID_CHANGE.value) {
                ByteBuffer b = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
                int id = b.getInt();
                b.getInt();
                b.getInt();
                assertEquals(0x0100007F, b.getInt(), "IP as seen by the server, used by LowID clients");
                assertEquals(id, (int) ctx.getState().clientId());
                return id;
            }
        }
        throw new AssertionError("no OP_IDCHANGE");
    }

    @Test
    @Timeout(10)
    void reachableClientGetsItsIpAsHighId() throws Exception {
        AtomicReference<byte[]> hello = new AtomicReference<>();
        try (ServerSocket ss = fakeClient(true, hello)) {
            assertEquals(0x0100007F, login(new ClientRegistry(), ss.getLocalPort()));
        }
        // The test connection carries a valid OP_HELLO, parsed like CUpDownClient::ProcessHelloPacket
        ByteBuffer b = ByteBuffer.wrap(hello.get()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16, b.get());
        b.position(1 + 16 + 4 + 2);
        List<Tag> tags = Tag.readList(b);
        assertEquals(2, tags.size());
        assertEquals(6, b.remaining(), "<server IP 4><server port 2>");
    }

    @Test
    @Timeout(10)
    void silentOrClosedClientGetsALowId() throws Exception {
        ClientRegistry registry = new ClientRegistry();
        AtomicReference<byte[]> hello = new AtomicReference<>();
        int id1;
        try (ServerSocket ss = fakeClient(false, hello)) {
            id1 = login(registry, ss.getLocalPort());
        }
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0)) {
            closedPort = tmp.getLocalPort();
        }
        int id2 = login(registry, closedPort);
        assertTrue(id1 > 0 && id1 < 0x01000000, "LowID expected: " + id1);
        assertTrue(id2 > 0 && id2 < 0x01000000, "LowID expected: " + id2);
        assertNotEquals(id1, id2, "LowIDs are unique");
        assertEquals(2, registry.lowIdCount());
    }

    @Test
    @Timeout(10)
    void portAnsweredByAnotherClientGetsALowId() throws Exception {
        // Two clients behind the same NAT: the announced port is forwarded to the other one, which answers
        byte[] otherClient = new byte[16];
        otherClient[0] = 1;
        byte[] loggingIn = new byte[16];
        loggingIn[0] = 2;
        AtomicReference<byte[]> hello = new AtomicReference<>();
        int id;
        try (ServerSocket ss = fakeClient(true, otherClient, hello)) {
            id = login(new ClientRegistry(), ss.getLocalPort(), loggingIn);
        }
        assertNotNull(hello.get(), "the test connection was made");
        assertTrue(id > 0 && id < 0x01000000, "LowID expected: " + id);
    }
}
