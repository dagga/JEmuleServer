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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public record Packet(byte protocol, byte opcode, byte[] data) {
    public static final byte PROTOCOL_ED2K = (byte) 0xE3;
    public static final byte PROTOCOL_EMULE = (byte) 0xC5;
    public static final byte PROTOCOL_ZLIB = (byte) 0xD4;
    public static final int HEADER_SIZE = 6;

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
        if (payload.length < payloadLength) throw new EOFException("Incomplete payload");

        byte[] data = (protocol == PROTOCOL_ZLIB) ? decompress(payload) : payload;
        return new Packet(protocol, opcode, data);
    }

    public void write(OutputStream out, boolean useCompression) throws IOException {
        byte proto = protocol;
        byte[] payload = data;
        if (useCompression && data.length > 64) {
            payload = compress(data);
            proto = PROTOCOL_ZLIB;
        }

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(proto);
        buf.putInt(payload.length + 1);
        buf.put(opcode);
        buf.put(payload);
        out.write(buf.array());
        out.flush();
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
