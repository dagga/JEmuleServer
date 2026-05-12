/*
 * JEmuleServer - An experimental eMule server.
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

import org.jemule.Main;
import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.jemule.security.FloodProtector;
import org.jemule.security.Obfuscation;
import org.jemule.core.event.ClientEvent;
import org.jemule.core.event.EventManager;
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
    private final EventManager eventManager;
    private final ClientFactory clientFactory;
    private ClientState state;
    private Obfuscation.RC4 sendRC4;
    private Obfuscation.RC4 receiveRC4;
    private boolean obfuscated = false;

    public ClientHandler(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex, FloodProtector floodProtector, EventManager eventManager, ClientFactory clientFactory) {
        this.socket = socket;
        this.config = config;
        this.registry = registry;
        this.fileIndex = fileIndex;
        this.floodProtector = floodProtector;
        this.eventManager = eventManager;
        this.clientFactory = clientFactory;
    }

    /**
     * Entry point of the client handler thread.
     * Manages the lifecycle of a single client connection, from handshake to disconnection.
     */
    @Override
    public void run() {
        try {
            socket.setSoTimeout(30000);
            String remoteAddr = maskIp(socket.getRemoteSocketAddress().toString());
            log.info("Client connected: {}", remoteAddr);
            broadcastEvent(ClientEvent.CONNECTED, remoteAddr, "anonymous", "Client connected");

            if (!floodProtector.allow(socket.getInetAddress())) {
                log.warn("Flood blocked: {}", socket.getInetAddress());
                return;
            }

            InputStream in = negotiateObfuscation(socket.getInputStream(), socket.getOutputStream());
            Packet p = Packet.read(in, config.maxPacketSize());
            validateProtocol(p.protocol());

            OutputStream out = wrappedOut != null ? wrappedOut : socket.getOutputStream();
            handleLogin(p, out);

            while (!socket.isClosed()) {
                try {
                    Packet nextP = Packet.read(in, config.maxPacketSize());
                    if (floodProtector.allow(socket.getInetAddress())) {
                        processPacket(nextP, out);
                    }
                } catch (EOFException e) {
                    break;
                }
            }
        } catch (IOException e) {
            log.error("IO Error: {}", e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void validateProtocol(byte protocol) throws IOException {
        if (protocol != Packet.PROTOCOL_ED2K && protocol != Packet.PROTOCOL_EMULE && protocol != Packet.PROTOCOL_ZLIB) {
            throw new IOException("Unsupported protocol: " + String.format("0x%02X", protocol));
        }
    }

    private void broadcastEvent(String type, String addr, String id, String msg) {
        if (eventManager != null) {
            eventManager.broadcast(new ClientEvent(type, addr, id, msg));
        }
    }

    private void cleanup() {
        String remoteAddr = socket.getRemoteSocketAddress().toString();
        if (state != null) registry.remove(state);
        try {
            socket.close();
        } catch (IOException ignored) {}
        log.info("Disconnected: {}", remoteAddr);
        broadcastEvent(ClientEvent.DISCONNECTED, remoteAddr, 
            state != null ? String.valueOf(state.clientId()) : "anonymous", "Client disconnected");
    }

    /**
     * Determines if the connection is obfuscated and returns the appropriate input stream.
     *
     * @param in  The original input stream.
     * @param out The original output stream.
     * @return A {@link PushbackInputStream} or a decrypted stream if obfuscated.
     * @throws IOException If a network error occurs.
     */
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

    /**
     * Performs the eMule obfuscation handshake (RC4).
     *
     * @param firstByte The first byte already read from the stream.
     * @param in        The input stream.
     * @param out       The output stream.
     * @return A decrypted input stream wrapper.
     * @throws IOException If the handshake fails or an attack is detected.
     */
    private InputStream handleObfuscatedHandshake(int firstByte, InputStream in, OutputStream out) throws IOException {
        // Obfuscated handshake:
        // Client -> Server: [1 byte random] [4 bytes random] [1 byte 0x97] [n bytes random] [1 byte padding len] [n bytes padding] [1 byte encryption method]
        DataInputStream dis = new DataInputStream(in);
        byte[] clientRandom = new byte[4];
        dis.readFully(clientRandom);

        // Anti-replay check
        if (Obfuscation.isReplay(clientRandom)) {
            throw new IOException("Replay attack detected from " + socket.getRemoteSocketAddress());
        }

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

        // Wrap streams
        InputStream encryptedIn = new ObfuscatedInputStream(in, receiveRC4);
        
        // Read until EncryptionMethod = Obfuscation (0x00)
        int method = -1;
        for (int k = 0; k < 256; k++) {
            int b = encryptedIn.read();
            if (b == 0x00) {
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
        this.wrappedOut = new ObfuscatedOutputStream(out, sendRC4);
        return encryptedIn;
    }

    /**
     * Helper stream to decrypt data on the fly using RC4.
     */
    private static class ObfuscatedInputStream extends InputStream {
        private final InputStream in;
        private final Obfuscation.RC4 rc4;

        public ObfuscatedInputStream(InputStream in, Obfuscation.RC4 rc4) {
            this.in = in;
            this.rc4 = rc4;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b == -1) return -1;
            byte[] data = {(byte) b};
            rc4.crypt(data);
            return data[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = in.read(b, off, len);
            if (r > 0) rc4.crypt(b, off, r);
            return r;
        }
    }

    /**
     * Helper stream to encrypt data on the fly using RC4.
     */
    private static class ObfuscatedOutputStream extends OutputStream {
        private final OutputStream out;
        private final Obfuscation.RC4 rc4;

        public ObfuscatedOutputStream(OutputStream out, Obfuscation.RC4 rc4) {
            this.out = out;
            this.rc4 = rc4;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] data = {(byte) b};
            rc4.crypt(data);
            out.write(data[0]);
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

    private OutputStream wrappedOut;

    /**
     * Processes the initial login packet and establishes the client session.
     *
     * @param initialPacket The first packet received from the client.
     * @param out           The output stream to send responses.
     * @throws IOException If login fails or network error occurs.
     */
    private void handleLogin(Packet initialPacket, OutputStream out) throws IOException {
        if (wrappedOut != null) out = wrappedOut;
        byte[] data = initialPacket.data();
        log.info("Handling login request, data size: {} bytes", data.length);

        // Use a dynamic client ID generation (simple IP-based ID for now or random)
        // For eMule, high ID is often the IP address in little-endian if public.
        int clientId = ByteBuffer.wrap(socket.getInetAddress().getAddress()).order(ByteOrder.LITTLE_ENDIAN).getInt();

        state = clientFactory.createClient(socket.getInetAddress(), socket.getPort(), clientId);
        
        if (eventManager != null) {
            eventManager.broadcast(new ClientEvent(ClientEvent.LOGIN, socket.getInetAddress().getHostAddress(), "ID:" + clientId, "Client logged in with ID " + clientId));
        }

        // Check if initial packet was ZLIB, if so, client supports it
        if (initialPacket.protocol() == Packet.PROTOCOL_ZLIB) {
            state.setZlibSupported(true);
            log.info("Client supports ZLIB (detected from initial packet)");
        }

        registry.add(state);

        // Standard eMule Handshake order (Strict Lugdunum/aMule style):
        // 1. Server Ident (0x41)
        sendServerIdent(out);

        // 2. Server Message (0x38) - MotD
        sendServerMessage(out, "Welcome to " + Main.VERSION + " (JEmuleServer)\n" +
                "Your ID is: " + Integer.toUnsignedString(clientId) + "\n" +
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

    /**
     * Sends a text message to the client (typically used for MotD).
     *
     * @param out The output stream.
     * @param msg The message string.
     * @throws IOException If sending fails.
     */
    private void sendServerMessage(OutputStream out, String msg) throws IOException {
        byte[] content = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) content.length);
        buf.put(content);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_MESSAGE.value, buf.array()).write(out, state.isZlibSupported());
    }

    /**
     * Sends the server identification packet (OpCode 0x41) including tags.
     *
     * @param out The output stream.
     * @throws IOException If sending fails.
     */
    private void sendServerIdent(OutputStream out) throws IOException {
        byte[] hash = new byte[16]; // Empty hash
        short port = (short) config.port();

        String serverName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
        String serverVersion = Main.VERSION + " (JEmuleServer)";
        String desc = "Experimental eMule Server";

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, serverName));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_VERSION, serverVersion));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_EMULE_VERSION, 0x3C)); // 0x3C = 60, typical for eMule 0.4x/0.5x compatible
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_FLAGS, 0x01 | 0x08 | 0x10)); // ZLIB + OBFUSCATION + NEWTAGS support bits
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_AUX_PORT, (int) port));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAX_USERS, config.maxUsers()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAX_FILES, config.maxFiles()));

        // Use a dynamic buffer to avoid manual size calculation errors
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        // Write IP and Port in BIG_ENDIAN specifically for this part of the packet
        byte[] addr = socket.getLocalAddress().getAddress();
        if (addr.length == 4) {
            buf.put(addr);
        } else {
            // Fallback to localhost if not IPv4
            buf.put((byte) 0x7F);
            buf.put((byte) 0x00);
            buf.put((byte) 0x00);
            buf.put((byte) 0x01);
        }
        buf.putShort(Short.reverseBytes(port)); // reverse since LE buffer

        Tag.writeList(buf, tags);

        buf.flip();
        byte[] response = new byte[buf.remaining()];
        buf.get(response);

        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_IDENT.value, response).write(out, state.isZlibSupported());
    }

    /**
     * Sends the current server status (OpCode 0x34) including user and file counts.
     *
     * @param out The output stream.
     * @throws IOException If sending fails.
     */
    private void sendServerStatus(OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(registry.size());
        buf.putInt(fileIndex.fileCount());
        buf.putInt(config.maxUsers()); // Soft/Hard Limit for users
        buf.putInt(config.maxFiles()); // Soft/Hard Limit for files
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_STATUS.value, buf.array()).write(out, state.isZlibSupported());
    }

    /**
     * Dispatches the received packet to the appropriate handler method.
     *
     * @param p   The received packet.
     * @param out The output stream for responses.
     * @throws IOException If handling fails.
     */
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

    /**
     * Handles eMule capability information packets (OpCode 0xC5:0x01).
     *
     * @param data The packet payload.
     * @param out  The output stream for the ACK.
     * @throws IOException If sending fails.
     */
    private void handleEmuleInfo(byte[] data, OutputStream out) throws IOException {
        log.debug("Received EMULE_INFO from {}", socket.getRemoteSocketAddress());
        // For now, just ACK it with basic server info or empty EMULE_INFO_ACK
        // eMule Info usually contains client capabilities.
        new Packet(Packet.PROTOCOL_EMULE, OpCode.EMULE_INFO_ACK.value, new byte[0]).write(out, state.isZlibSupported());
    }

    /**
     * Handles file search requests (OpCode 0x16).
     * Supports both complex (binary tree) and simple (textual) search fallbacks.
     *
     * @param data The search query data.
     * @param out  The output stream for results.
     * @throws IOException If sending results fails.
     */
    private void handleSearch(byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 1) {
            log.warn("Invalid search request: empty data");
            return;
        }

        List<FileMetadata> results;
        try {
            SearchQuery query = SearchQuery.parse(ByteBuffer.wrap(data));
            results = fileIndex.searchComplex(query, config.maxSearchResults());
            log.debug("Complex search -> {} results", results.size());
        } catch (Exception e) {
            log.warn("Failed to parse complex search, falling back to simple search: {}", e.getMessage());
            String queryStr = new String(data, StandardCharsets.UTF_8).trim();
            if (queryStr.length() < 3) {
                log.warn("Simple search query too short: '{}'", queryStr);
                results = List.of();
            } else {
                results = fileIndex.search(queryStr, config.maxSearchResults());
                log.debug("Simple search '{}' -> {} results", queryStr, results.size());
            }
        }

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

    /**
     * Handles file publication requests (OpCode 0x20).
     * Supports both the standard ed2k binary format (with Tags) and a simple pipe-separated fallback.
     *
     * @param data The publication data.
     * @param out  The output stream for ACK.
     * @throws IOException If sending ACK fails.
     */
    private void handlePublish(byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length == 0) {
            log.warn("Invalid publish request: empty data");
            return;
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            // In ed2k protocol, PUBLISH_FILES usually starts with the number of files (4 bytes)
            // But some simplified versions might send just one or use a different format.
            // Let's try to detect if it's binary or text.
            if (data[0] < 32) { // Likely binary (count or first byte of hash/type)
                int count = buf.getInt();
                log.debug("Standard binary publish: {} files", count);
                for (int i = 0; i < count; i++) {
                    byte[] hashBytes = new byte[16];
                    buf.get(hashBytes);
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashBytes) sb.append(String.format("%02x", b));
                    String hash = sb.toString();

                    List<Tag> tags = Tag.readList(buf);
                    String name = "";
                    long size = 0;
                    String type = "";

                    for (Tag t : tags) {
                        switch (t.name()) {
                            case Tag.NAME_NAME -> name = (String) t.value();
                            case "\u0002" -> size = ((Number) t.value()).longValue(); // ID_FILESIZE
                            case "\u0003" -> type = (String) t.value(); // ID_FILETYPE
                            // Additional tags like format, bitrate, etc. can be ignored or added to meta
                        }
                    }

                    if (!name.isEmpty() && state.publishedFilesCount().get() < config.maxFilesPerUser()) {
                        FileMetadata meta = new FileMetadata(hash, name, size, type, tags);
                        meta.sources().put(String.valueOf(state.clientId()), state);
                        fileIndex.addFile(meta);
                        state.publishedFilesCount().incrementAndGet();
                        log.info("Published (binary): {}", name);
                    }
                }
            } else {
                // Fallback to simple pipe-separated format
                String raw = new String(data, StandardCharsets.UTF_8);
                String[] p = raw.split("\\|");
                if (p.length >= 4) {
                    String hash = p[0].trim();
                    String name = p[1].trim();
                    String sizeStr = p[2].trim();
                    String type = p[3].trim();

                    if (hash.length() == 32 && !name.isEmpty() && state.publishedFilesCount().get() < config.maxFilesPerUser()) {
                        long size = Long.parseLong(sizeStr);
                        if (size >= 0) {
                            FileMetadata meta = new FileMetadata(hash, name, size, type);
                            meta.sources().put(String.valueOf(state.clientId()), state);
                            fileIndex.addFile(meta);
                            state.publishedFilesCount().incrementAndGet();
                            log.info("Published (text): {}", name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse PUBLISH_FILES: {}", e.getMessage());
        }

        new Packet(Packet.PROTOCOL_ED2K, OpCode.PUBLISH_ACK.value, new byte[0]).write(out, state.isZlibSupported());
    }

    /**
     * Converts a 32-character hex string hash to 16 bytes.
     */
    private byte[] hashToBytes(String hex) {
        if (hex == null || hex.length() != 32) return new byte[16];
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /**
     * Handles requests for file sources (OpCode 0x15 or 0xC5:0x23).
     *
     * @param data The file hash.
     * @param out  The output stream for results.
     * @throws IOException If sending results fails.
     */
    private void handleGetSources(byte[] data, OutputStream out) throws IOException {
        String hash;
        byte[] hashBytes;
        if (data.length == 16) {
            hashBytes = data;
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format("%02x", b));
            hash = sb.toString();
        } else {
            hash = new String(data, StandardCharsets.UTF_8).trim();
            if (hash.length() != 32) {
                log.warn("Invalid hash length for GET_SOURCES: {}", hash.length());
                return;
            }
            hashBytes = hashToBytes(hash);
        }

        var sources = fileIndex.getSources(hash, state, config.maxSourcesPerFile());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.write(hashBytes);
        dos.writeByte((byte) Math.min(sources.size(), 255));
        for (var s : sources) {
            dos.writeInt(ClientState.ipToInt(s.address()));
            dos.writeShort((short) s.port());
        }
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SOURCES_RESULT.value, baos.toByteArray()).write(out, state.isZlibSupported());
    }

    /**
     * Masks the last part of an IP address for GDPR compliance.
     */
    private String maskIp(String addr) {
        if (addr == null) return "unknown";
        // Handles both "/1.2.3.4:port" and "1.2.3.4"
        String ipPart = addr;
        if (ipPart.startsWith("/")) ipPart = ipPart.substring(1);
        int colonIdx = ipPart.indexOf(':');
        String portPart = "";
        if (colonIdx != -1) {
            portPart = ipPart.substring(colonIdx);
            ipPart = ipPart.substring(0, colonIdx);
        }

        String[] parts = ipPart.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + "." + parts[2] + ".xxx" + portPart;
        }
        return addr; // Return as is for IPv6 or unknown format for now
    }
}
