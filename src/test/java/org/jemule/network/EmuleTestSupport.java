package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.event.EventManager;
import org.jemule.network.handler.ClientContext;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helpers that build packets exactly like the eMule 0.50a client does, so that tests check the server
 * against the real client wire format instead of the server's own interpretation of it.
 */
public final class EmuleTestSupport {

    private EmuleTestSupport() {}

    /** A socket that only reports a remote address/port (no real connection). */
    public static class DummySocket extends Socket {
        private final InetAddress remote;
        private final int port;

        public DummySocket(InetAddress remote, int port) {
            this.remote = remote;
            this.port = port;
        }

        @Override public InetAddress getInetAddress() { return remote; }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getPort() { return port; }
        @Override public java.net.SocketAddress getRemoteSocketAddress() { return new java.net.InetSocketAddress(remote, port); }
    }

    /** Minimal ClientContext backed by real server components. */
    public static class FakeContext implements ClientContext {
        private final Socket socket;
        private final ServerConfig config;
        private final ClientRegistry registry;
        private final FileIndex fileIndex;
        private final FakeFileDetector fakeFileDetector = new FakeFileDetector();
        private final EventManager eventManager = new EventManager();
        private final ClientFactory clientFactory = new ClientFactory();
        private ClientState state;
        private OutputStream wrappedOut;

        public FakeContext(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex) {
            this.socket = socket;
            this.config = config;
            this.registry = registry;
            this.fileIndex = fileIndex;
        }

        @Override public Socket getSocket() { return socket; }
        @Override public ServerConfig getConfig() { return config; }
        @Override public ClientRegistry getRegistry() { return registry; }
        @Override public FileIndex getFileIndex() { return fileIndex; }
        @Override public FloodProtector getFloodProtector() { return null; }
        @Override public FakeFileDetector getFakeFileDetector() { return fakeFileDetector; }
        @Override public EventManager getEventManager() { return eventManager; }
        @Override public ClientFactory getClientFactory() { return clientFactory; }
        @Override public ClientState getState() { return state; }
        @Override public void setState(ClientState s) { this.state = s; }
        @Override public OutputStream getWrappedOut() { return wrappedOut; }
        @Override public void setWrappedOut(OutputStream out) { this.wrappedOut = out; }
        @Override public void setObfuscated(boolean obfuscated) { }
        @Override public void disconnect() throws IOException { }
    }

    /** Creates a context with a logged-in client state (ID = IP, listen port given). */
    public static FakeContext loggedInContext(String ip, int socketPort, ServerConfig config, ClientRegistry registry, FileIndex index) throws IOException {
        InetAddress addr = InetAddress.getByName(ip);
        FakeContext ctx = new FakeContext(new DummySocket(addr, socketPort), config, registry, index);
        ClientState st = new ClientState(addr, socketPort, ClientState.ipToLong(addr), System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        ctx.setState(st);
        registry.add(st, p -> {});
        return ctx;
    }

    public static byte[] hash(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /**
     * Tag writer reproducing CTag::WriteNewEd2kTag (packets.cpp): integers are compacted to the smallest
     * unsigned type, strings of 1..16 bytes use TAGTYPE_STR1..STR16.
     */
    public static final class NewTags {
        private final ByteBuffer buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        private int count;

        public NewTags str(int id, String value) {
            byte[] v = value.getBytes(StandardCharsets.UTF_8);
            if (v.length >= 1 && v.length <= 16) {
                buf.put((byte) ((0x11 + v.length - 1) | 0x80)).put((byte) id).put(v);
            } else {
                buf.put((byte) (0x02 | 0x80)).put((byte) id).putShort((short) v.length).put(v);
            }
            count++;
            return this;
        }

        public NewTags num(int id, long value) {
            if (value <= 0xFF) {
                buf.put((byte) (0x09 | 0x80)).put((byte) id).put((byte) value);
            } else if (value <= 0xFFFF) {
                buf.put((byte) (0x08 | 0x80)).put((byte) id).putShort((short) value);
            } else if (value <= 0xFFFFFFFFL) {
                buf.put((byte) (0x03 | 0x80)).put((byte) id).putInt((int) value);
            } else {
                buf.put((byte) (0x0B | 0x80)).put((byte) id).putLong(value);
            }
            count++;
            return this;
        }

        public byte[] toBytes() {
            ByteBuffer out = ByteBuffer.allocate(4 + buf.position()).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(count).put(buf.array(), 0, buf.position());
            return out.array();
        }
    }

    /** One entry of OP_OFFERFILES: <hash 16><ID 4><port 2><tags>. */
    public static byte[] offeredFile(byte[] hash, int clientId, int port, NewTags tags) {
        byte[] t = tags.toBytes();
        ByteBuffer b = ByteBuffer.allocate(16 + 6 + t.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(hash).putInt(clientId).putShort((short) port).put(t);
        return b.array();
    }

    /** OP_OFFERFILES payload: <count 4>(file)*. */
    public static byte[] offerFiles(byte[]... files) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(files.length).array());
        for (byte[] f : files) bos.writeBytes(f);
        return bos.toByteArray();
    }

    /** Typical complete-file offer from a client connected to a server announcing SRV_TCPFLG_COMPRESSION. */
    public static byte[] completeFile(byte[] hash, String name, long size) {
        NewTags tags = new NewTags().str(0x01, name).num(0x02, size & 0xFFFFFFFFL);
        if (size > 0xFFFFFFFFL) tags.num(0x3A, size >>> 32);
        tags.num(0x03, 2); // FT_FILETYPE = ED2KFT_VIDEO (SRV_TCPFLG_TYPETAGINTEGER)
        return offeredFile(hash, 0xFBFBFBFB, 0xFBFB, tags);
    }
}
