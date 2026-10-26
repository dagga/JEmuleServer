/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package org.jemule.network;

import org.jemule.protocol.Tag;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A client session on another eD2K server, used by the index update: login as an eMule client (LowID), then
 * searches, one at a time, each answered by one OP_SEARCHRESULT. Synchronous: the thread of the server's update
 * drives it.
 */
final class ServerUpdateSession implements AutoCloseable {
    static final byte OP_LOGINREQUEST = 0x01;
    static final byte OP_OFFERFILES = 0x15;
    static final byte OP_SEARCHREQUEST = 0x16;
    static final byte OP_SEARCHRESULT = 0x33;
    static final byte OP_SERVERMESSAGE = 0x38;
    static final byte OP_IDCHANGE = 0x40;

    /** eMule 0.50a: protocol version and client version announced at login. */
    private static final int EDONKEY_VERSION = 0x3C;
    private static final int EMULE_VERSION = 50 << 10;
    /** SRVCAP_ZLIB, SRVCAP_NEWTAGS, SRVCAP_UNICODE, SRVCAP_LARGEFILES. */
    private static final int SERVER_FLAGS = 0x0001 | 0x0008 | 0x0010 | 0x0100;
    /** A port nothing listens on: the session is a LowID, the server never connects back. */
    static final int CLIENT_PORT = 4662;
    private static final int MAX_PACKET = 4 * 1024 * 1024;
    /**
     * A server message warning this client: flooding, a ban, too many or too fast searches. General words such as
     * "limit" are not enough: welcome messages say "No traffic limit. No time limit".
     */
    private static final Pattern WARNING = Pattern.compile(
            "flood|\\bbann(ed|ing)\\b|you (are|have been|will be) banned|too many|too fast|slow down|search limit"
                    + "|limit (reached|exceeded)|exceeded");

    /** What a search returned. */
    record Answer(byte[] payload, long millis) {}

    /** The server warned about the session (flood, ban, limit) after the first search. */
    static final class WarningException extends IOException {
        WarningException(String message) {
            super(message);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    /** True once a search was answered: the messages before it (welcome, rules) are not warnings. */
    private boolean answered;

    private ServerUpdateSession(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * Connects and logs in; returns once the server gave an ID.
     *
     * @throws IOException when the server cannot be reached, closes the session or gives no ID in time
     */
    static ServerUpdateSession open(ServerGossip.Endpoint endpoint, byte[] userHash, String name, int timeoutMillis)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(endpoint.address(), endpoint.tcpPort()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            ServerUpdateSession session = new ServerUpdateSession(socket);
            session.send(OP_LOGINREQUEST, login(userHash, name));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                Packet packet = session.read();
                if (packet.opcode() == OP_IDCHANGE) {
                    session.send(OP_OFFERFILES, new byte[4]); // no shared file
                    return session;
                }
            }
            throw new SocketTimeoutException("no ID from the server");
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** {@code <user hash 16><client ID 4><port 2><tags>}, as eMule logs in. */
    static byte[] login(byte[] userHash, String name) {
        ByteBuffer buf = ByteBuffer.allocate(256 + name.length() * 3).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(userHash).putInt(0).putShort((short) CLIENT_PORT);
        Tag.writeList(buf, List.of(
                new Tag(Tag.TYPE_STRING, "\u0001", name),                 // CT_NAME
                new Tag(Tag.TYPE_INTEGER, "\u0011", EDONKEY_VERSION),     // CT_VERSION
                new Tag(Tag.TYPE_INTEGER, " ", SERVER_FLAGS),        // CT_SERVER_FLAGS
                new Tag(Tag.TYPE_INTEGER, "û", EMULE_VERSION)));     // CT_EMULE_VERSION
        return java.util.Arrays.copyOf(buf.array(), buf.position());
    }

    /**
     * Sends a search and waits for its OP_SEARCHRESULT.
     *
     * @throws WarningException when the server warns about the session instead
     * @throws IOException      when the session is cut or the answer does not come in time
     */
    Answer search(byte[] tree) throws IOException {
        long start = System.currentTimeMillis();
        send(OP_SEARCHREQUEST, tree);
        while (true) {
            Packet packet = read();
            if (packet.opcode() == OP_SEARCHRESULT) {
                answered = true;
                return new Answer(packet.data(), System.currentTimeMillis() - start);
            }
            if (packet.opcode() == OP_SERVERMESSAGE && answered) {
                String message = message(packet.data());
                if (isWarning(message)) throw new WarningException(message);
            }
        }
    }

    static boolean isWarning(String message) {
        return WARNING.matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    /** {@code <length 2><text>}. */
    private static String message(byte[] data) {
        if (data.length < 2) return "";
        int length = Math.min(data.length - 2, ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF);
        return new String(data, 2, length, StandardCharsets.UTF_8);
    }

    private void send(byte opcode, byte[] payload) throws IOException {
        new Packet(Packet.PROTOCOL_ED2K, opcode, payload).write(out, false);
        out.flush();
    }

    private Packet read() throws IOException {
        return Packet.read(in, MAX_PACKET);
    }

    boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
