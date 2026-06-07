package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.event.EventManager;
import org.jemule.network.handler.ClientContext;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.SearchHandler;
import org.jemule.network.handler.SourceHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProtocolSequenceTest {

    static class FakeClientContext implements ClientContext {
        private final Socket socket;
        private final ServerConfig config;
        private final ClientRegistry registry;
        private final FileIndex fileIndex;
        private final EventManager eventManager;
        private final ClientFactory clientFactory;
        private org.jemule.core.ClientState state;
        private OutputStream wrappedOut;

        FakeClientContext(Socket socket, ServerConfig config, ClientFactory clientFactory, ClientRegistry registry, FileIndex fileIndex, EventManager eventManager) {
            this.socket = socket;
            this.config = config;
            this.clientFactory = clientFactory;
            this.registry = registry;
            this.fileIndex = fileIndex;
            this.eventManager = eventManager;
        }

        @Override public Socket getSocket() { return socket; }
        @Override public ServerConfig getConfig() { return config; }
        @Override public ClientRegistry getRegistry() { return registry; }
        @Override public FileIndex getFileIndex() { return fileIndex; }
        @Override public org.jemule.security.FloodProtector getFloodProtector() { return null; }
        @Override public org.jemule.security.FakeFileDetector getFakeFileDetector() { return null; }
        @Override public EventManager getEventManager() { return eventManager; }
        @Override public ClientFactory getClientFactory() { return clientFactory; }
        @Override public org.jemule.core.ClientState getState() { return state; }
        @Override public void setState(org.jemule.core.ClientState state) { this.state = state; }
        @Override public java.io.OutputStream getWrappedOut() { return wrappedOut; }
        @Override public void setWrappedOut(java.io.OutputStream out) { this.wrappedOut = out; }
        @Override public void setObfuscated(boolean obfuscated) { }
        @Override public void disconnect() throws IOException { }
    }

    static class DummySocket extends Socket {
        private final InetAddress remote;
        private final InetAddress local;
        private final int port;

        DummySocket(InetAddress remote, InetAddress local, int port) {
            this.remote = remote;
            this.local = local;
            this.port = port;
        }

        @Override public InetAddress getInetAddress() { return remote; }
        @Override public InetAddress getLocalAddress() { return local; }
        @Override public int getPort() { return port; }
    }

    static class CapturingDatagramSocket extends DatagramSocket {
        public final List<DatagramPacket> sent = new ArrayList<>();
        CapturingDatagramSocket() throws Exception { super(); }
        @Override public void send(DatagramPacket p) throws IOException {
            sent.add(new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort()));
        }
    }

    @Test
    void testLoginSequence() throws Exception {
        InetAddress remote = InetAddress.getByName("127.0.0.1");
        DummySocket s = new DummySocket(remote, InetAddress.getByName("127.0.0.1"), 55555);

        ClientFactory cf = new ClientFactory();
        ClientRegistry reg = new ClientRegistry();
        FileIndex fi = new FileIndex(null, new EventManager());
        EventManager em = new EventManager();

        FakeClientContext ctx = new FakeClientContext(s, ServerConfig.DEFAULT, cf, reg, fi, em);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ctx.setWrappedOut(out);

        LoginHandler loginHandler = new LoginHandler();
        Packet initial = new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, new byte[0]);

        loginHandler.handleLogin(ctx, initial, out);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Packet p_ver = Packet.read(in, 4096);
        Packet p1 = Packet.read(in, 4096);
        Packet p2 = Packet.read(in, 4096);
        Packet p3 = Packet.read(in, 4096);
        Packet p4 = Packet.read(in, 4096);
        Packet p5 = Packet.read(in, 4096);

        assertEquals(OpCode.SERVER_MESSAGE.value, p_ver.opcode());
        assertEquals(OpCode.SERVER_IDENT.value, p1.opcode());
        assertEquals(OpCode.ID_CHANGE.value, p2.opcode());
        assertEquals(OpCode.LOGIN_ACCEPTED.value, p3.opcode());
        assertEquals(OpCode.SERVER_MESSAGE.value, p4.opcode());
        assertEquals(OpCode.SERVER_STATUS.value, p5.opcode());
    }

    @Test
    void testSearchAndQueryMore() throws Exception {
        ClientFactory cf = new ClientFactory();
        ClientRegistry reg = new ClientRegistry();
        FileIndex fi = new FileIndex(null, new EventManager());
        EventManager em = new EventManager();
        InetAddress remote = InetAddress.getByName("127.0.0.1");
        DummySocket s = new DummySocket(remote, remote, 11111);
        FakeClientContext ctx = new FakeClientContext(s, ServerConfig.DEFAULT, cf, reg, fi, em);

        // Populate file index with 60 entries to force pagination
        for (int i = 0; i < 60; i++) {
            fi.addFile(new FileMetadata(String.format("H%02d", i), "TestFile" + i + ".txt", 1000 + i, "Text"));
        }

        // Create state and assign
        org.jemule.core.ClientState st = new org.jemule.core.ClientState(remote, 11111, 42, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        ctx.setState(st);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ctx.setWrappedOut(out);

        SearchHandler sh = new SearchHandler();
        byte[] simpleQuery = "TestFile".getBytes();
        sh.handleSearch(ctx, simpleQuery, out);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Packet first = Packet.read(in, 65536);
        assertEquals(OpCode.SEARCH_RESULT.value, first.opcode());

        // Simulate client requesting more results
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        sh.handleQueryMoreResult(ctx, out2);
        ByteArrayInputStream in2 = new ByteArrayInputStream(out2.toByteArray());
        Packet more = Packet.read(in2, 65536);
        assertEquals(OpCode.SEARCH_RESULT.value, more.opcode());
    }

    @Test
    void testGetSourcesTcpAndUdp() throws Exception {
        ClientFactory cf = new ClientFactory();
        ClientRegistry reg = new ClientRegistry();
        FileIndex fi = new FileIndex(null, new EventManager());
        EventManager em = new EventManager();
        InetAddress remote = InetAddress.getByName("127.0.0.1");
        DummySocket s = new DummySocket(remote, remote, 22222);
        FakeClientContext ctx = new FakeClientContext(s, ServerConfig.DEFAULT, cf, reg, fi, em);

        // Create a file and a source — hash must match hexBytes used in packets below
        String hash = "000102030405060708090a0b0c0d0e0f";
        FileMetadata meta = new FileMetadata(hash, "file.iso", 12345, "ISO");
        fi.addFile(meta);

        org.jemule.core.ClientState source = new org.jemule.core.ClientState(remote, 9000, 101, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        meta.sources().put(String.valueOf(source.clientId()), source);

        // TCP GET_SOURCES (16 bytes hash)
        byte[] hashBytes = new byte[16];
        for (int i = 0; i < 16; i++) hashBytes[i] = (byte) i;
        // Build a packet representing a 16-byte hash: convert our hex to bytes consistently
        byte[] hexBytes = new byte[16];
        for (int i = 0; i < 16; i++) hexBytes[i] = (byte) i;

        Packet p = new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, hexBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ctx.setWrappedOut(out);
        // Ensure requester state exists so handlers can access isZlibSupported() without NPE
        org.jemule.core.ClientState requester = new org.jemule.core.ClientState(remote, 22222, 42, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        ctx.setState(requester);

        SourceHandler sh = new SourceHandler();
        sh.handleGetSources(ctx, p, out);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Packet resp = Packet.read(in, 65536);
        assertEquals(OpCode.FOUND_SOURCES.value, resp.opcode());

        // UDP: invoke Server.handleUdp via reflection and capture responses
        org.jemule.network.Server server = new org.jemule.network.Server(ServerConfig.DEFAULT, cf);
        // Register the file and source on the Server's own FileIndex
        server.getFileIndex().addFile(meta);
        byte[] udpReq = new byte[18];
        udpReq[0] = Packet.PROTOCOL_ED2K;
        udpReq[1] = (byte) 0x9A; // OP_GLOBGETSOURCES
        // put 16-byte hash starting at 2
        System.arraycopy(hexBytes, 0, udpReq, 2, 16);

        DatagramPacket dp = new DatagramPacket(udpReq, udpReq.length, remote, 40000);
        CapturingDatagramSocket cds = new CapturingDatagramSocket();

        var m = org.jemule.network.Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, java.net.DatagramPacket.class);
        m.setAccessible(true);
        m.invoke(server, cds, dp);

        // Ensure at least one UDP response was sent
        assertFalse(cds.sent.isEmpty());
        DatagramPacket sent = cds.sent.get(0);
        byte[] sentData = sent.getData();
        assertEquals(Packet.PROTOCOL_ED2K, sentData[0]);
        // Opcode should be OP_GLOBFOUNDSOURCES (0x9B)
        assertEquals((byte) 0x9B, sentData[1]);
    }
}
