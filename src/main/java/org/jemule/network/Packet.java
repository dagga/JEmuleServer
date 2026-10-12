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

import org.jemule.network.handler.HandlerUtils; // Import added for debugging
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
            if (log.isDebugEnabled()) {
                log.debug("Incomplete header received: {} bytes. Raw: {}", header.length, HandlerUtils.bytesToHex(header));
            }
            throw new EOFException("Incomplete header (received " + header.length + " bytes)");
        }

        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte protocol = buf.get();
        int length = buf.getInt(); // Length field encodes payload size + 1 (opcode)
        byte opcode = buf.get();

        if (log.isDebugEnabled()) {
            log.debug("Reading packet header: Raw={} Proto=0x{} Length={} Opcode=0x{}",
                    HandlerUtils.bytesToHex(header), String.format("%02X", protocol & 0xFF), length, String.format("%02X", opcode & 0xFF));
        }

        if (length < 1 || length > maxPacketSize) // length must be at least 1 (opcode only)
            throw new IOException("Invalid packet length: " + length);

        int payloadLength = length - 1; // payload size (length includes opcode)
        byte[] payload = in.readNBytes(payloadLength);
        if (payload.length < payloadLength) {
            // Build a short hex preview of what we did receive for diagnostics
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(payload.length, 64); i++) {
                sb.append(String.format("%02X", payload[i]));
                if (i < Math.min(payload.length, 64) - 1) sb.append(' ');
            }
            throw new EOFException("Incomplete payload: expected=" + payloadLength + " received=" + payload.length + " preview=[" + sb + "]");
        }

        byte[] data = (protocol == PROTOCOL_ZLIB || protocol == PROTOCOL_KAD_ZLIB) ? decompress(payload, maxPacketSize) : payload;
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
        // Like eMule's Packet::PackPacket: only ed2k packets, and only when compression actually saves bytes
        if (useCompression && data.length > 64 && (protocol == PROTOCOL_ED2K || protocol == PROTOCOL_KAD)) {
            byte[] compressed = compress(data);
            if (compressed.length < data.length) {
                payload = compressed;
                proto = (protocol == PROTOCOL_KAD) ? PROTOCOL_KAD_ZLIB : PROTOCOL_ZLIB;
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(proto);
        buf.putInt(payload.length + 1); // length is payload + 1 (opcode)
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
                    String.format("%02X", proto & 0xFF), String.format("%02X", opcode & 0xFF), payload.length,
                    sb, bytes.length > 256 ? " ..." : "");
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

    /**
     * Inflates a 0xD4 payload. A truncated or corrupted stream, or one inflating beyond {@code maxSize}
     * (zip bomb), is rejected instead of looping forever.
     */
    static byte[] decompress(byte[] input, int maxSize) throws IOException {
        Inflater i = new Inflater();
        i.setInput(input);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(input.length * 4, maxSize));
        byte[] buf = new byte[8192];
        try {
            while (!i.finished()) {
                int count = i.inflate(buf);
                if (count == 0 && (i.needsInput() || i.needsDictionary())) {
                    throw new IOException("Truncated or invalid zlib stream");
                }
                if (bos.size() + count > maxSize) {
                    throw new IOException("Decompressed packet exceeds " + maxSize + " bytes");
                }
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