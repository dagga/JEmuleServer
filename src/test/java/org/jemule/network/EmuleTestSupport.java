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
        private org.jemule.kad.KadSearchBridge kadSearchBridge;
        private ClientState state;
        private OutputStream wrappedOut;

        public FakeContext(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex) {
            this.socket = socket;
            this.config = config;
            this.registry = registry;
            this.fileIndex = fileIndex;
        }

        public void setKadSearchBridge(org.jemule.kad.KadSearchBridge bridge) {
            this.kadSearchBridge = bridge;
        }

        @Override public org.jemule.kad.KadSearchBridge getKadSearchBridge() { return kadSearchBridge; }

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

    /** Default eMule TCP listen port announced in the login request of test clients. */
    public static final int LISTEN_PORT = 4662;

    /** Creates a context with a logged-in client state (ID = IP, listen port {@link #LISTEN_PORT}, given socket port). */
    public static FakeContext loggedInContext(String ip, int socketPort, ServerConfig config, ClientRegistry registry, FileIndex index) throws IOException {
        InetAddress addr = InetAddress.getByName(ip);
        FakeContext ctx = new FakeContext(new DummySocket(addr, socketPort), config, registry, index);
        ClientState st = new ClientState(addr, socketPort, ClientState.ipToLong(addr), System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
        st.setListenPort(LISTEN_PORT);
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

    /**
     * OP_LOGINREQUEST payload as built by CServerConnect::ConnectionEstablished, tags written with
     * CTag::WriteTagToFile (old format: type, uint16 name length = 1, name ID).
     */
    public static byte[] loginRequest(byte[] userHash, int listenPort, String nick, int srvCaps) {
        byte[] nickBytes = nick.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(64 + nickBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(userHash).putInt(0).putShort((short) listenPort).putInt(4);
        b.put((byte) 0x02).putShort((short) 1).put((byte) 0x01).putShort((short) nickBytes.length).put(nickBytes); // CT_NAME
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0x11).putInt(0x3C);                                     // CT_VERSION
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0x20).putInt(srvCaps);                                   // CT_SERVER_FLAGS
        b.put((byte) 0x03).putShort((short) 1).put((byte) 0xFB).putInt((0 << 17) | (50 << 10) | (1 << 7));        // CT_EMULE_VERSION 0.50b
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }

    /** SRVCAP flags sent by eMule 0.50a with default preferences (crypt layer supported). */
    public static final int EMULE_DEFAULT_SRVCAPS = 0x0001 | 0x0008 | 0x0100 | 0x0010 | 0x0200;

    /**
     * Search tree writer reproducing CSearchExprTarget (SearchResultsWnd.cpp). Operators are prefix:
     * write AND, then both operands.
     */
    public static final class SearchTree {
        private final ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

        public SearchTree and() { buf.put((byte) 0).put((byte) 0x00); return this; }
        public SearchTree or() { buf.put((byte) 0).put((byte) 0x01); return this; }
        public SearchTree not() { buf.put((byte) 0).put((byte) 0x02); return this; }

        /** Keyword (file name), parameter type 1. */
        public SearchTree keyword(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            buf.put((byte) 1).putShort((short) b.length).put(b);
            return this;
        }

        /** String meta tag (FT_FILETYPE, FT_FILEFORMAT, FT_MEDIA_CODEC...), parameter type 2. */
        public SearchTree meta(int tagId, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            buf.put((byte) 2).putShort((short) b.length).put(b).putShort((short) 1).put((byte) tagId);
            return this;
        }

        /** Numeric meta tag, parameter type 3 (uint32) or 8 (uint64, only when the server supports large files). */
        public SearchTree num(int tagId, int operator, long value) {
            if (value > 0xFFFFFFFFL) {
                buf.put((byte) 8).putLong(value);
            } else {
                buf.put((byte) 3).putInt((int) value);
            }
            buf.put((byte) operator).putShort((short) 1).put((byte) tagId);
            return this;
        }

        public byte[] toBytes() {
            byte[] out = new byte[buf.position()];
            System.arraycopy(buf.array(), 0, out, 0, out.length);
            return out;
        }
    }

    /** One result of OP_SEARCHRESULT / OP_GLOBSEARCHRES, decoded like CSearchFile's constructor. */
    public record SearchResult(String hash, int clientId, int port, java.util.List<org.jemule.protocol.Tag> tags) {
        public Object tag(String name) {
            return tags.stream().filter(t -> t.name().equals(name)).map(org.jemule.protocol.Tag::value).findFirst().orElse(null);
        }
    }

    public static SearchResult readSearchResult(ByteBuffer buf) {
        byte[] h = new byte[16];
        buf.get(h);
        int id = buf.getInt();
        int port = Short.toUnsignedInt(buf.getShort());
        return new SearchResult(HexFormat.of().formatHex(h), id, port, org.jemule.protocol.Tag.readList(buf));
    }

    /** Decoded OP_SEARCHRESULT: results plus the optional trailing "more results" byte (-1 if absent). */
    public record SearchAnswer(java.util.List<SearchResult> results, int more, int trailingBytes) {}

    /** Mirrors CSearchList::ProcessSearchAnswer (server variant). */
    public static SearchAnswer readSearchAnswer(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buf.getInt());
        if (count > payload.length) throw new AssertionError("Implausible result count " + count + " (endianness?)");
        java.util.List<SearchResult> results = new java.util.ArrayList<>();
        for (long i = 0; i < count; i++) results.add(readSearchResult(buf));
        int more = -1;
        int trailing = buf.remaining();
        if (trailing == 1) more = Byte.toUnsignedInt(buf.get());
        return new SearchAnswer(results, more, trailing);
    }
}
