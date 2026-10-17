package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.event.EventManager;
import org.jemule.protocol.OpCode;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallbackTest {

    @TempDir
    Path tempDir;

    private ClientRegistry registry;
    private ClientHandler handler1;
    private ClientState state1;
    private ClientHandler handler2;
    private ClientState state2;
    private final AtomicReference<Packet> receivedPacket1 = new AtomicReference<>();
    private final AtomicReference<Packet> receivedPacket2 = new AtomicReference<>();

    @BeforeEach
    void setup() throws IOException {
        ServerConfig config = new ServerConfig(
                4661, 2*1024*1024, 300, 50, 100, 1000, 5, 200, tempDir.resolve("db").toString(), null, true,
                50.0f, 10, 60, 300, 60, null
        );

        FileIndex index = new FileIndex(null);
        registry = new ClientRegistry();
        FloodProtector flood = new FloodProtector(config.floodMaxRequestsPerSecond());
        FakeFileDetector fakes = new FakeFileDetector();
        EventManager events = new EventManager();
        ClientFactory factory = new ClientFactory();

        // Client 1 (The Requester)
        Socket socket1 = mock(Socket.class);
        when(socket1.getInetAddress()).thenReturn(InetAddress.getByName("1.1.1.1"));
        when(socket1.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress(InetAddress.getByName("1.1.1.1"), 1111));
        handler1 = new ClientHandler(socket1, config, registry, index, flood, fakes, events, factory);
        state1 = new ClientState(InetAddress.getByName("1.1.1.1"), 1111, 111, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        injectState(handler1, state1);
        registry.add(state1, receivedPacket1::set);

        // Client 2 (The Target)
        Socket socket2 = mock(Socket.class);
        when(socket2.getInetAddress()).thenReturn(InetAddress.getByName("2.2.2.2"));
        when(socket2.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress(InetAddress.getByName("2.2.2.2"), 2222));
        handler2 = new ClientHandler(socket2, config, registry, index, flood, fakes, events, factory);
        state2 = new ClientState(InetAddress.getByName("2.2.2.2"), 2222, 222, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        injectState(handler2, state2);
        registry.add(state2, p -> receivedPacket2.set(p));
    }

    private void injectState(ClientHandler handler, ClientState state) {
        try {
            Field field = ClientHandler.class.getDeclaredField("state");
            field.setAccessible(true);
            field.set(handler, state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testCallbackRelay() throws Exception {
        state1.setListenPort(4662);
        state1.setUserHash(EmuleTestSupport.hash("00112233445566778899aabbccddeeff"));
        state1.setServerCaps(EmuleTestSupport.EMULE_DEFAULT_SRVCAPS);
        state2.setListenPort(4663);
        state2.setUserHash(EmuleTestSupport.hash("ffeeddccbbaa99887766554433221100"));
        state2.setServerCaps(EmuleTestSupport.EMULE_DEFAULT_SRVCAPS);

        // Client 1 sends OP_CALLBACKREQUEST for Client 2
        ByteBuffer data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(222); // Target ID

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new org.jemule.network.handler.SourceHandler().handleCallback(handler1, data.array(), out);

        // Client 2 receives OP_CALLBACKREQUESTED, decoded like CServerSocket::ProcessPacket (size >= 23)
        Packet p = receivedPacket2.get();
        assertNotNull(p, "Target client should have received a packet");
        assertEquals(Packet.PROTOCOL_ED2K, p.protocol());
        assertEquals((byte) 0x35, p.opcode());
        assertEquals(23, p.data().length);

        ByteBuffer payload = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((int) ClientState.ipToLong(state1.address()), payload.getInt());
        assertEquals(4662, Short.toUnsignedInt(payload.getShort()), "requester's listen port");
        assertEquals(0x01, payload.get(), "crypt layer supported");
        byte[] hash = new byte[16];
        payload.get(hash);
        assertArrayEquals(state1.userHash(), hash);

        Packet reciprocal = Packet.read(new java.io.ByteArrayInputStream(out.toByteArray()), 1024);
        assertEquals(OpCode.CALLBACK_REQUESTED.value, reciprocal.opcode(),
                "LowID requester should receive the target endpoint to initiate a simultaneous connection");
        ByteBuffer reciprocalPayload = ByteBuffer.wrap(reciprocal.data()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals((int) ClientState.ipToLong(state2.address()), reciprocalPayload.getInt());
        assertEquals(4663, Short.toUnsignedInt(reciprocalPayload.getShort()), "target's listen port");
        assertEquals(0x01, reciprocalPayload.get(), "target crypt layer support");
        byte[] targetHash = new byte[16];
        reciprocalPayload.get(targetHash);
        assertArrayEquals(state2.userHash(), targetHash);
    }

    @Test
    void highIdRequesterKeepsTheOneWayCallbackBehavior() throws Exception {
        state1.setClientId(0x01020304L);
        state1.setListenPort(4662);
        state2.setListenPort(4663);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new org.jemule.network.handler.SourceHandler().handleCallback(handler1,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(222).array(), out);

        assertNotNull(receivedPacket2.get(), "LowID target should still receive the callback");
        assertNull(receivedPacket1.get(), "HighID requester should not receive a reciprocal callback");
        assertEquals(0, out.size(), "HighID requester should not receive a reciprocal callback");
    }

    @Test
    void testCallbackToUnknownClientFails() throws Exception {
        state1.setListenPort(4662);
        ByteBuffer data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(999);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new org.jemule.network.handler.SourceHandler().handleCallback(handler1, data.array(), out);

        Packet p = Packet.read(new java.io.ByteArrayInputStream(out.toByteArray()), 1024);
        assertEquals(Packet.PROTOCOL_ED2K, p.protocol());
        assertEquals((byte) 0x36, p.opcode(), "OP_CALLBACK_FAIL");
    }
}
