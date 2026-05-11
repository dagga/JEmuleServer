/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (herniatgmail.com)
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

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.jemule.security.FloodProtector;
import org.jemule.security.Obfuscation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ClientHandler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private final Socket socket;
    private final ServerConfig config;
    private final ClientRegistry registry;
    private final FileIndex fileIndex;
    private final FloodProtector floodProtector;
    private ClientState state;
    private Obfuscation.RC4 sendRC4;
    private Obfuscation.RC4 receiveRC4;
    private boolean obfuscated = false;

    public ClientHandler(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex, FloodProtector floodProtector) {
        this.socket = socket;
        this.config = config;
        this.registry = registry;
        this.fileIndex = fileIndex;
        this.floodProtector = floodProtector;
    }

    @Override
    public void run() {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            log.info("Client connected: {}", socket.getRemoteSocketAddress());
            if (!floodProtector.allow(socket.getInetAddress())) {
                log.warn("Flood blocked: {}", socket.getInetAddress());
                return;
            }

            // Negotiation / Obfuscation detection
            in = negotiateObfuscation(in, out);

            Packet p = Packet.read(in, config.maxPacketSize());
            if (p.protocol() != Packet.PROTOCOL_ED2K && p.protocol() != Packet.PROTOCOL_EMULE && p.protocol() != Packet.PROTOCOL_ZLIB) {
                throw new IOException("Unsupported protocol: " + String.format("0x%02X", p.protocol()));
            }

            handleLogin(p, out);

            while (!socket.isClosed()) {
                try {
                    Packet nextP = Packet.read(in, config.maxPacketSize());
                    if (floodProtector.allow(socket.getInetAddress())) processPacket(nextP, wrappedOut != null ? wrappedOut : out);
                } catch (EOFException e) {
                    break;
                }
            }
        } catch (IOException e) {
            log.error("IO Error: {}", e.getMessage());
        } finally {
            if (state != null) registry.remove(state);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            log.info("Disconnected: {}", socket.getRemoteSocketAddress());
        }
    }

    private InputStream negotiateObfuscation(InputStream in, OutputStream out) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(in, 1);
        int firstByte = pin.read();
        if (firstByte == -1) return pin;

        if (firstByte != (Packet.PROTOCOL_ED2K & 0xFF) &&
                firstByte != (Packet.PROTOCOL_EMULE & 0xFF) &&
                firstByte != (Packet.PROTOCOL_ZLIB & 0xFF)) {
            // Likely obfuscated handshake start
            log.info("Detected possible obfuscated handshake from {}", socket.getRemoteSocketAddress());
            return handleObfuscatedHandshake(firstByte, pin, out);
        }

        pin.unread(firstByte);
        return pin;
    }

    private InputStream handleObfuscatedHandshake(int firstByte, InputStream in, OutputStream out) throws IOException {
        // Obfuscated handshake:
        // Client -> Server: [1 byte random] [4 bytes random] [1 byte 0x97] [n bytes random] [1 byte padding len] [n bytes padding] [1 byte encryption method]
        // But eMule clients usually send a bigger chunk.
        // Let's use a DataInputStream for convenience
        DataInputStream dis = new DataInputStream(in);
        byte[] clientRandom = new byte[4];
        dis.readFully(clientRandom);
        int marker = dis.readUnsignedByte();
        if (marker != 0x97) {
            throw new IOException("Invalid obfuscation marker: 0x" + Integer.toHexString(marker));
        }

        // Keys creation
        byte[] magic = {(byte) 0x97};
        receiveRC4 = new Obfuscation.RC4(Obfuscation.md5(magic, clientRandom));
        sendRC4 = new Obfuscation.RC4(Obfuscation.md5(magic, clientRandom));

        // Discard first 1024 bytes
        receiveRC4.crypt(new byte[1024]);
        sendRC4.crypt(new byte[1024]);

        // Handshake is encrypted from now on
        // Read random bytes until padding len
        // We don't know exactly how many random bytes were sent before 0x97, 
        // but typically the client sends [1 random] [4 random] [0x97] [random...] [padding_len] [padding] [method]
        // Actually, the spec says: [Random byte] [4 random (used for key)] [0x97] [Random bytes] [1 byte PaddingLen] [Padding] [1 byte EncryptionMethod]
        // We need to read until we find a valid PaddingLen and EncryptionMethod.
        // Since we already read 6 bytes (1 + 4 + 1), we continue.
        
        // Use a wrapper to decrypt on the fly
        InputStream encryptedIn = new InputStream() {
            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b == -1) return -1;
                byte[] data = {(byte) b};
                receiveRC4.crypt(data);
                return data[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int r = in.read(b, off, len);
                if (r > 0) receiveRC4.crypt(b, off, r);
                return r;
            }
        };

        // Read remaining handshake (random + padding_len + padding + method)
        // This is tricky because "random bytes" length is variable. 
        // eMule usually sends a fixed amount or we read until we get the method.
        // Lugdunum uses some heuristic. 
        // For simplicity, we'll read a reasonable amount or wait for the method byte.
        // Usually, total handshake is around 20-50 bytes.
        
        // Actually, many implementations expect at least one byte of encryption method.
        // Let's skip random bytes. 0x00 is Obfuscation method.
        int method = -1;
        // Read up to 256 bytes to find the method
        for (int k = 0; k < 256; k++) {
            int b = encryptedIn.read();
            if (b == 0x00) { // Found EncryptionMethod = Obfuscation
                method = b;
                break;
            }
        }

        if (method != 0x00) throw new IOException("Unsupported encryption method or handshake timeout");

        // Server Response: [Random 1-256] [PaddingLen 1] [Padding] [EncryptionMethod 1]
        SecureRandom rng = new SecureRandom();
        int serverPadLen = rng.nextInt(16);
        byte[] serverHandshake = new byte[1 + serverPadLen + 1];
        rng.nextBytes(serverHandshake);
        serverHandshake[0] = (byte) serverPadLen;
        serverHandshake[serverHandshake.length - 1] = 0x00; // Method

        byte[] encryptedResponse = serverHandshake.clone();
        sendRC4.crypt(encryptedResponse);
        out.write(encryptedResponse);
        out.flush();

        log.info("Obfuscation handshake complete for {}", socket.getRemoteSocketAddress());
        obfuscated = true;

        // Wrap output stream as well
        final OutputStream originalOut = out;
        OutputStream encryptedOut = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                byte[] data = {(byte) b};
                sendRC4.crypt(data);
                originalOut.write(data[0]);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                byte[] copy = Arrays.copyOfRange(b, off, off + len);
                sendRC4.crypt(copy);
                originalOut.write(copy);
            }

            @Override
            public void flush() throws IOException {
                originalOut.flush();
            }
        };

        // Replace 'out' in handleLogin with this one. 
        // Since run() uses local variables for in/out, we need to return the wrapped in.
        // But handleLogin takes out. We'll need to pass the wrapped out.
        // I will refactor run() to use fields for streams or pass them around.
        
        this.wrappedOut = encryptedOut;
        return encryptedIn;
    }

    private OutputStream wrappedOut;

    private void handleLogin(Packet initialPacket, OutputStream out) throws IOException {
        if (wrappedOut != null) out = wrappedOut;
        byte[] data = initialPacket.data();
        log.info("Handling login request, data size: {} bytes", data.length);

        // ... truncated logic for ID assignment ...
        int clientId = 0x01020304;

        state = new ClientState(socket.getInetAddress(), socket.getPort(), clientId, System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()));

        // Check if initial packet was ZLIB, if so, client supports it
        if (initialPacket.protocol() == Packet.PROTOCOL_ZLIB) {
            state.setZlibSupported(true);
            log.info("Client supports ZLIB (detected from initial packet)");
        }

        registry.add(state);

        // Standard eMule Handshake order (Strict Lugdunum/aMule style):
        // 1. Server Ident (0x41)
        sendServerIdent(out);

        // 2. Server Message (0x38) - MotD (Mandatory before ID Change for some)
        sendServerMessage(out, "Welcome to 0.2.0 (JEmuleServer)\n" +
                "Your HighID is: " + Integer.toUnsignedString(clientId) + "\n" +
                "Enjoy the extended protocol support!");

        // 3. Login Accepted / ID Change (0x40) - Standard Lugdunum ID Change
        // OpCode 0x40 is often used by servers to finalize the ID assignment.
        ByteBuffer resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        resp.putInt(clientId);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.ID_CHANGE.value, resp.array()).write(out, state.isZlibSupported());

        // Also send the old 0x1B for compatibility
        new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_ACCEPTED.value, resp.array()).write(out, state.isZlibSupported());

        log.info("Logged in ID: {} (Sent 0x40 and 0x1B)", clientId);

        // 4. Server Status (0x34) - Finalizes the state in aMule
        sendServerStatus(out);
    }

    private void sendServerMessage(OutputStream out, String msg) throws IOException {
        byte[] content = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) content.length);
        buf.put(content);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_MESSAGE.value, buf.array()).write(out, state.isZlibSupported());
    }

    private void sendServerIdent(OutputStream out) throws IOException {
        byte[] hash = new byte[16]; // Empty hash
        short port = (short) config.port();

        String version = "0.2.0";
        String name = version + " (JEmuleServer)";
        String desc = "Experimental eMule Server";

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, name));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_VERSION, 60));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_EMULE_VERSION, 0x3C)); // 0x3C = 60, typical for eMule 0.4x/0.5x compatible
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_FLAGS, 0x01 | 0x08 | 0x10)); // ZLIB + OBFUSCATION + NEWTAGS support bits
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_AUX_PORT, (int) port));

        // Use a dynamic buffer to avoid manual size calculation errors
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        // Write IP and Port in BIG_ENDIAN specifically for this part of the packet
        // For local testing, we use 127.0.0.1
        buf.put((byte) 0x7F);
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);
        buf.put((byte) 0x01);
        buf.putShort(Short.reverseBytes(port)); // reverse since LE buffer

        Tag.writeList(buf, tags);

        buf.flip();
        byte[] response = new byte[buf.remaining()];
        buf.get(response);

        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_IDENT.value, response).write(out, state.isZlibSupported());
    }

    private void sendServerStatus(OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(registry.size());
        buf.putInt(fileIndex.fileCount());
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_STATUS.value, buf.array()).write(out, state.isZlibSupported());
    }

    private void processPacket(Packet p, OutputStream out) throws IOException {
        state.lastActivity().set(System.currentTimeMillis());
        OpCode op = OpCode.fromByte(p.protocol(), p.opcode());
        if (op == null) return;

        switch (op) {
            case SEARCH_REQUEST -> handleSearch(p.data(), out);
            case PUBLISH_FILES -> handlePublish(p.data(), out);
            case GET_SOURCES, GET_SOURCES_OBFU -> handleGetSources(p.data(), out);
            case EMULE_INFO -> handleEmuleInfo(p.data(), out);
            default -> log.debug("Unhandled: {} (Proto: 0x{:02X})", op, p.protocol());
        }
    }

    private void handleEmuleInfo(byte[] data, OutputStream out) throws IOException {
        log.debug("Received EMULE_INFO from {}", socket.getRemoteSocketAddress());
        // For now, just ACK it with basic server info or empty EMULE_INFO_ACK
        // eMule Info usually contains client capabilities.
        new Packet(Packet.PROTOCOL_EMULE, OpCode.EMULE_INFO_ACK.value, new byte[0]).write(out, state.isZlibSupported());
    }

    private void handleSearch(byte[] data, OutputStream out) throws IOException {
        String query = new String(data, StandardCharsets.UTF_8);
        var results = fileIndex.search(query, config.maxSearchResults());
        log.debug("Search '{}' -> {} results", query, results.size());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        for (var m : results) {
            dos.writeUTF(m.hash());
            dos.writeUTF(m.name());
            dos.writeLong(m.size());
            dos.writeUTF(m.type());
        }
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SEARCH_RESULT.value, baos.toByteArray()).write(out, state.isZlibSupported());
    }

    private void handlePublish(byte[] data, OutputStream out) throws IOException {
        String[] p = new String(data, StandardCharsets.UTF_8).split("\\|");
        if (p.length >= 4) {
            FileMetadata meta = new FileMetadata(p[0], p[1], Long.parseLong(p[2]), p[3]);
            meta.sources().put(String.valueOf(state.clientId()), state);
            fileIndex.addFile(meta);
            log.info("Published: {}", p[1]);
        }
        new Packet(Packet.PROTOCOL_ED2K, OpCode.PUBLISH_ACK.value, new byte[0]).write(out, state.isZlibSupported());
    }

    private void handleGetSources(byte[] data, OutputStream out) throws IOException {
        String hash = new String(data, StandardCharsets.UTF_8).trim();
        var sources = fileIndex.getSources(hash);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        for (var s : sources) {
            dos.writeInt(s.clientId());
            dos.writeInt(ClientState.ipToInt(s.address()));
            dos.writeShort(s.port());
        }
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SOURCES_RESULT.value, baos.toByteArray()).write(out, state.isZlibSupported());
    }
}
