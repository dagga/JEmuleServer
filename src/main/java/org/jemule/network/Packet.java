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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Represents an ed2k/eMule network packet.
 * Handles serialization, deserialization, and optional ZLIB compression.
 *
 * @param protocol The protocol identifier (0xE3, 0xC5, or 0xD4).
 * @param opcode   The operation code within the protocol.
 * @param data     The payload data of the packet.
 */
public record Packet(byte protocol, byte opcode, byte[] data) {
    private static final Logger log = LoggerFactory.getLogger(Packet.class);
    /** Protocol ID for standard eDonkey2000 (ed2k) packets. */
    public static final byte PROTOCOL_ED2K = (byte) 0xE3;
    /** Protocol ID for eMule extended packets. */
    public static final byte PROTOCOL_EMULE = (byte) 0xC5;
    /** Protocol ID for ZLIB compressed packets. */
    public static final byte PROTOCOL_ZLIB = (byte) 0xD4;
    /** Protocol ID for base eMule packets. */
    public static final byte PROTOCOL_BASE = (byte) 0x01;
    /** Protocol ID for Kademlia packets. */
    public static final byte PROTOCOL_KAD = (byte) 0xE4;
    /** Protocol ID for ZLIB compressed Kademlia packets. */
    public static final byte PROTOCOL_KAD_ZLIB = (byte) 0xE5;
    /** Fixed size of the packet header (1 byte protocol + 4 bytes length + 1 byte opcode). */
    public static final int HEADER_SIZE = 6;

    /**
     * Reads a packet from an input stream.
     *
     * @param in            The input stream to read from.
     * @param maxPacketSize The maximum allowed packet length to prevent DoS.
     * @return A new {@link Packet} instance.
     * @throws IOException If a network error occurs or packet is malformed.
     */
    public static Packet read(InputStream in, int maxPacketSize) throws IOException {
        byte[] header = in.readNBytes(HEADER_SIZE);
        if (header.length < HEADER_SIZE) {
            StringBuilder sb = new StringBuilder();
            for (byte b : header) sb.append(String.format("%02X ", b));
            throw new EOFException("Incomplete header (received " + header.length + " bytes: " + sb.toString().trim() + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte protocol = buf.get();
        int length = buf.getInt();
        byte opcode = buf.get();

        if (length < 1 || length > maxPacketSize)
            throw new IOException("Invalid packet length: " + length);

        int payloadLength = length - 1;
        byte[] payload = in.readNBytes(payloadLength);
        if (payload.length < payloadLength) {
            // Build a short hex preview of what we did receive for diagnostics
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(payload.length, 64); i++) {
                sb.append(String.format("%02X", payload[i]));
                if (i < Math.min(payload.length, 64) - 1) sb.append(' ');
            }
            throw new EOFException("Incomplete payload: expected=" + payloadLength + " received=" + payload.length + " preview=[" + sb.toString() + "]");
        }

        byte[] data = (protocol == PROTOCOL_ZLIB || protocol == PROTOCOL_KAD_ZLIB) ? decompress(payload) : payload;
        return new Packet(protocol, opcode, data);
    }

    /**
     * Writes this packet to an output stream.
     *
     * @param out            The output stream to write to.
     * @param useCompression Whether to use ZLIB compression if the payload is large enough.
     * @throws IOException If a network error occurs.
     */
    public void write(OutputStream out, boolean useCompression) throws IOException {
        byte proto = protocol;
        byte[] payload = data;
        if (useCompression && data.length > 64) {
            payload = compress(data);
            proto = (protocol == PROTOCOL_KAD) ? PROTOCOL_KAD_ZLIB : PROTOCOL_ZLIB;
        }

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(proto);
        buf.putInt(payload.length + 1);
        buf.put(opcode);
        buf.put(payload);
        out.write(buf.array());
        out.flush();
        if (log.isDebugEnabled()) {
            byte[] bytes = buf.array();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 256); i++) {
                sb.append(String.format("%02X", bytes[i]));
                if (i < bytes.length - 1) sb.append(' ');
            }
            log.debug("Sent packet proto=0x{} opcode=0x{} len={} bytes={}{}",
                    String.format("%02X", proto & 0xFF), String.format("%02X", opcode & 0xFF), payload.length + 1,
                    sb.toString(), bytes.length > 256 ? " ..." : "");

            // Also append to a debug file for easier collection (non-fatal on error)
            try {
                String line = java.time.Instant.now().toString() + " " +
                        String.format("proto=0x%02X opcode=0x%02X len=%d ", proto & 0xFF, opcode & 0xFF, payload.length + 1) + sb.toString() + System.lineSeparator();
                java.nio.file.Path p = java.nio.file.Path.of("build/packet-debug.log");
                java.nio.file.Files.createDirectories(p.getParent() != null ? p.getParent() : java.nio.file.Path.of("build"));
                java.nio.file.Files.writeString(p, line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                // ignore file write errors
            }
        }
    }

    private static byte[] compress(byte[] input) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(input);
        d.finish();
        byte[] buf = new byte[input.length * 2];
        int len = d.deflate(buf);
        d.end();
        return Arrays.copyOf(buf, len);
    }

    private static byte[] decompress(byte[] input) throws IOException {
        Inflater i = new Inflater();
        i.setInput(input);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length * 2);
        byte[] buf = new byte[1024];
        try {
            while (!i.finished()) {
                int count = i.inflate(buf);
                bos.write(buf, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IOException("Decompression failed", e);
        } finally {
            i.end();
        }
        return bos.toByteArray();
    }
}
