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
import org.jemule.security.FloodProtector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private final Socket socket;
    private final ServerConfig config;
    private final ClientRegistry registry;
    private final FileIndex fileIndex;
    private final FloodProtector floodProtector;
    private ClientState state;

    public ClientHandler(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex, FloodProtector floodProtector) {
        this.socket = socket;
        this.config = config;
        this.registry = registry;
        this.fileIndex = fileIndex;
        this.floodProtector = floodProtector;
    }

    @Override
    public void run() {
        try (var in = socket.getInputStream(); var out = socket.getOutputStream()) {
            log.info("Client connected: {}", socket.getRemoteSocketAddress());
            if (!floodProtector.allow(socket.getInetAddress())) {
                log.warn("Flood blocked: {}", socket.getInetAddress());
                return;
            }

            Packet p = Packet.read(in, config.maxPacketSize());
            if (p.protocol() != Packet.PROTOCOL_ED2K && p.protocol() != Packet.PROTOCOL_EMULE && p.protocol() != Packet.PROTOCOL_ZLIB) {
                throw new IOException("Unsupported protocol: " + String.format("0x%02X", p.protocol()));
            }
            if (p.opcode() != OpCode.LOGIN_REQUEST.value && p.opcode() != OpCode.CLIENT_LOGIN.value) {
                throw new IOException("Unexpected initial opcode: " + String.format("0x%02X", p.opcode()));
            }

            handleLogin(p.data(), out);

            while (!socket.isClosed()) {
                try {
                    Packet nextP = Packet.read(in, config.maxPacketSize());
                    if (floodProtector.allow(socket.getInetAddress())) processPacket(nextP, out);
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

    private void handleLogin(byte[] data, OutputStream out) throws IOException {
        log.info("Handling login request, data size: {} bytes", data.length);

        // Use the IP address as seen by the server (127.0.0.1 for local tests)
        int ipInt = ClientState.ipToInt(socket.getInetAddress());

        // Client ID: For HighID, it's just the IP.
        // HOWEVER, aMule rejects 127.0.0.1 (16777343) as a valid HighID.
        // We must provide a "fake" public IP for the ID to be accepted.
        // Let's use 1.2.3.4 (0x04030201) for testing.
        int clientId = 0x01020304;

        state = new ClientState(socket.getInetAddress(), socket.getPort(), clientId, System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()));
        registry.add(state);

        // Standard eMule Handshake order (Strict Lugdunum/aMule style):
        // 1. Server Ident (0x41)
        sendServerIdent(out);

        // 2. Server Message (0x38) - MotD (Mandatory before ID Change for some)
        sendServerMessage(out, "Welcome to JEmuleServer 0.1.0\n" +
                "Your HighID is: " + Integer.toUnsignedString(clientId) + "\n" +
                "Enjoy the extended protocol support!");

        // 3. Login Accepted / ID Change (0x40) - Standard Lugdunum ID Change
        // OpCode 0x40 is often used by servers to finalize the ID assignment.
        ByteBuffer resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        resp.putInt(clientId);
        new Packet(Packet.PROTOCOL_ED2K, (byte) 0x40, resp.array()).write(out, false);

        // Also send the old 0x1B for compatibility
        new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_ACCEPTED.value, resp.array()).write(out, false);

        log.info("Logged in ID: {} (Sent 0x40 and 0x1B)", clientId);

        // 4. Server Status (0x34) - Finalizes the state in aMule
        sendServerStatus(out);
    }

    private void sendServerMessage(OutputStream out, String msg) throws IOException {
        byte[] content = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) content.length);
        buf.put(content);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_MESSAGE.value, buf.array()).write(out, false);
    }

    private void sendServerIdent(OutputStream out) throws IOException {
        byte[] hash = new byte[16]; // Empty hash
        // IP in eD2k is usually sent as 4 bytes in network byte order?
        // Wait, eDonkey spec says IP and Port in ServerIdent are in network byte order (Big-Endian).
        // But our ByteBuffer is set to LITTLE_ENDIAN.

        // 127.0.0.1 in Big-Endian is 0x7F 00 00 01
        // Port 4661 in Big-Endian is 0x12 35

        int ip = 0x7F000001;
        short port = (short) config.port();

        String name = "JEmuleServer";
        String desc = "Experimental eMule Server";

        int totalSize = 16 + 4 + 2 + 4;
        totalSize += (1 + 2 + 1 + 2 + name.length());
        totalSize += (1 + 2 + 1 + 2 + desc.length());
        totalSize += (1 + 2 + 1 + 4); // Version
        totalSize += (1 + 2 + 1 + 4); // TCPFlags
        totalSize += (1 + 2 + 1 + 4); // AuxPort (UDP)

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        // Write IP and Port in BIG_ENDIAN specifically for this part of the packet
        buf.put((byte) 0x7F);
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);
        buf.put((byte) 0x01);
        buf.putShort(Short.reverseBytes(port)); // reverse since buf is LE

        buf.putInt(5); // 5 tags

        // Tag 1: Server Name (ID 0x01)
        buf.put((byte) 0x02);
        buf.putShort((short) 1);
        buf.put((byte) 0x01);
        buf.putShort((short) name.length());
        buf.put(name.getBytes(StandardCharsets.UTF_8));

        // Tag 2: Server Description (ID 0x0B)
        buf.put((byte) 0x02);
        buf.putShort((short) 1);
        buf.put((byte) 0x0B);
        buf.putShort((short) desc.length());
        buf.put(desc.getBytes(StandardCharsets.UTF_8));

        // Tag 3: Version (ID 0x11)
        buf.put((byte) 0x03);
        buf.putShort((short) 1);
        buf.put((byte) 0x11);
        buf.putInt(60);

        // Tag 4: TCP Flags (ID 0x90) -> Support Compression, Obfuscation, etc.
        buf.put((byte) 0x03);
        buf.putShort((short) 1);
        buf.put((byte) 0x90);
        buf.putInt(0x01 | 0x08); // ZLIB + OBFUSCATION support bits

        // Tag 5: Aux Port / UDP Port (ID 0x91)
        buf.put((byte) 0x03);
        buf.putShort((short) 1);
        buf.put((byte) 0x91);
        buf.putInt(port);

        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_IDENT.value, buf.array()).write(out, false);
    }

    private void sendServerStatus(OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(registry.size());
        buf.putInt(fileIndex.fileCount());
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SERVER_STATUS.value, buf.array()).write(out, false);
    }

    private void processPacket(Packet p, OutputStream out) throws IOException {
        state.lastActivity().set(System.currentTimeMillis());
        OpCode op = OpCode.fromByte(p.opcode());
        if (op == null) return;

        switch (op) {
            case SEARCH_REQUEST -> handleSearch(p.data(), out);
            case PUBLISH_FILES -> handlePublish(p.data(), out);
            case GET_SOURCES -> handleGetSources(p.data(), out);
            default -> log.debug("Unhandled: {}", op);
        }
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
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SEARCH_RESULT.value, baos.toByteArray()).write(out, true);
    }

    private void handlePublish(byte[] data, OutputStream out) throws IOException {
        String[] p = new String(data, StandardCharsets.UTF_8).split("\\|");
        if (p.length >= 4) {
            FileMetadata meta = new FileMetadata(p[0], p[1], Long.parseLong(p[2]), p[3]);
            meta.sources().put(String.valueOf(state.clientId()), state);
            fileIndex.addFile(meta);
            log.info("Published: {}", p[1]);
        }
        new Packet(Packet.PROTOCOL_ED2K, OpCode.PUBLISH_ACK.value, new byte[0]).write(out, false);
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
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SOURCES_RESULT.value, baos.toByteArray()).write(out, true);
    }
}
