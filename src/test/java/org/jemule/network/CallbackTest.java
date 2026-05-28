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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private final AtomicReference<Packet> receivedPacket2 = new AtomicReference<>();

    @BeforeEach
    void setup() throws IOException {
        ServerConfig config = new ServerConfig(
                4661, 2*1024*1024, 300, 50, 100, 1000, 5, 200, tempDir.resolve("db").toString(), null, true,
                50.0f, 10, 60, 300, 60
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
        registry.add(state1, p -> {}); // Requester messenger doesn't matter much here

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
        // Client 1 sends CALLBACK for Client 2
        ByteBuffer data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(222); // Target ID

        new org.jemule.network.handler.SourceHandler().handleCallback(handler1, data.array(), new ByteArrayOutputStream());

        // Check if Client 2 received the CALLBACK packet
        Packet p = receivedPacket2.get();
        assertNotNull(p, "Target client should have received a packet");
        assertEquals(Packet.PROTOCOL_ED2K, p.protocol());
        assertEquals(OpCode.CALLBACK.value, p.opcode());

        // Payload should be [Requester IP (4 bytes)][Requester Port (2 bytes)]
        ByteBuffer payload = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
        int ip = payload.getInt();
        short port = payload.getShort();

        assertEquals(ClientState.ipToInt(state1.address()), ip);
        assertEquals((short) state1.port(), Short.reverseBytes(port));
    }
}
