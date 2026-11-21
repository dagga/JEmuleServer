package org.jemule.network;

import org.jemule.security.Obfuscation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A listening eMule client for the HighID test and the IPv6 dial-back: it answers OP_HELLO with OP_HELLOANSWER
 * carrying its user hash, as client B of eMule's client &lt;-&gt; client obfuscation (EncryptedStreamSocket.cpp),
 * written from the protocol description, independently of {@link ClientObfuscation}:
 * <pre>
 * A: &lt;marker 1&gt;&lt;random key part 4&gt; RC4[&lt;magic 4&gt;&lt;supported 1&gt;&lt;preferred 1&gt;&lt;padLen 1&gt;&lt;padding&gt;]
 * B: RC4[&lt;magic 4&gt;&lt;selected 1&gt;&lt;padLen 1&gt;&lt;padding&gt;]
 * B receive key = MD5(&lt;own hash&gt;&lt;34&gt;&lt;key part&gt;), send key = MD5(&lt;own hash&gt;&lt;203&gt;&lt;key part&gt;)
 * </pre>
 * Every accepted connection is recorded as {@code "plain"}, {@code "obfuscated"} or {@code "refused"}.
 */
public final class FakeEmuleClient implements AutoCloseable {
    /** The client's crypt layer setting. */
    public enum Crypt {
        /** No crypt layer: an obfuscated connection is read as a bad packet and closed. */
        NONE,
        /** Supported, not required (aMule's default): both kinds of connections are accepted. */
        SUPPORTED,
        /** Required, strict: every plain connection is refused, the server's test included. */
        REQUIRED
    }

    private static final int MAGIC_SYNC = 0x835E6FC4;

    private final ServerSocket socket;
    private final byte[] userHash;
    private final Crypt crypt;
    private final List<String> connections = new CopyOnWriteArrayList<>();

    public FakeEmuleClient(InetAddress bind, byte[] userHash, Crypt crypt) throws IOException {
        this.socket = new ServerSocket();
        this.socket.bind(new InetSocketAddress(bind, 0), 4);
        this.userHash = userHash.clone();
        this.crypt = crypt;
        Thread t = new Thread(this::acceptLoop, "fake-emule-client");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** The kind of each connection received, in order. */
    public List<String> connections() {
        return List.copyOf(connections);
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try (Socket s = socket.accept()) {
                s.setSoTimeout(5000);
                serve(s);
            } catch (IOException ignored) {
                // closed, or the peer went away
            }
        }
    }

    private void serve(Socket s) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(s.getInputStream(), 1);
        OutputStream out = s.getOutputStream();
        int first = pin.read();
        if (first == -1) return;
        boolean plain = first == 0xE3 || first == 0xC5 || first == 0xD4;
        if (plain ? crypt == Crypt.REQUIRED : crypt == Crypt.NONE) {
            connections.add("refused");
            return;
        }
        InputStream in;
        if (plain) {
            pin.unread(first);
            in = pin;
            connections.add("plain");
        } else {
            byte[] keyPart = pin.readNBytes(4);
            Obfuscation.RC4 receive = key((byte) 34, keyPart);
            Obfuscation.RC4 send = key((byte) 203, keyPart);
            byte[] request = pin.readNBytes(7);
            receive.crypt(request);
            ByteBuffer r = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);
            if (request.length < 7 || r.getInt() != MAGIC_SYNC) {
                connections.add("refused"); // not keyed with this client's hash
                return;
            }
            r.get(); // methods supported
            r.get(); // method preferred
            int padLen = Byte.toUnsignedInt(r.get());
            byte[] padding = pin.readNBytes(padLen);
            receive.crypt(padding);

            byte[] answer = ByteBuffer.allocate(4 + 2 + 3).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(MAGIC_SYNC).put((byte) 0).put((byte) 3).put(new byte[] {1, 2, 3}).array();
            send.crypt(answer);
            out.write(answer);
            out.flush();
            connections.add("obfuscated");
            in = new Decrypting(pin, receive);
            out = new Encrypting(out, send);
        }
        Packet hello = Packet.read(in, 65536);
        if (hello.opcode() != 0x01) return;
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.put(userHash).putInt(0).putShort((short) port()).putInt(0).putInt(0).putShort((short) 0);
        new Packet(Packet.PROTOCOL_ED2K, (byte) 0x4C, Arrays.copyOf(b.array(), b.position())).write(out, false);
        in.read(); // wait for the server to close
    }

    private Obfuscation.RC4 key(byte magic, byte[] keyPart) {
        byte[] data = new byte[21];
        System.arraycopy(userHash, 0, data, 0, 16);
        data[16] = magic;
        System.arraycopy(keyPart, 0, data, 17, 4);
        Obfuscation.RC4 rc4 = new Obfuscation.RC4(Obfuscation.md5(data));
        rc4.crypt(new byte[1024]);
        return rc4;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static final class Decrypting extends InputStream {
        private final InputStream in;
        private final Obfuscation.RC4 rc4;

        Decrypting(InputStream in, Obfuscation.RC4 rc4) {
            this.in = in;
            this.rc4 = rc4;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b == -1) return -1;
            byte[] one = {(byte) b};
            rc4.crypt(one);
            return one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) rc4.crypt(b, off, n);
            return n;
        }
    }

    private static final class Encrypting extends OutputStream {
        private final OutputStream out;
        private final Obfuscation.RC4 rc4;

        Encrypting(OutputStream out, Obfuscation.RC4 rc4) {
            this.out = out;
            this.rc4 = rc4;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] copy = Arrays.copyOfRange(b, off, off + len);
            rc4.crypt(copy);
            out.write(copy);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
