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

import org.jemule.network.handler.ObfuscationHandler;
import org.jemule.security.Obfuscation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;

/**
 * Initiator side of eMule's obfuscated client &lt;-&gt; client TCP handshake (EncryptedStreamSocket.cpp, "Basic
 * Obfuscated Handshake Protocol Client &lt;-&gt; Client"), as client A, the one that connects:
 * <pre>
 * keys:    send = MD5(&lt;target user hash 16&gt;&lt;34 1&gt;&lt;random key part 4&gt;)
 *          receive = MD5(&lt;target user hash 16&gt;&lt;203 1&gt;&lt;random key part 4&gt;), first 1024 RC4 bytes discarded
 * A -&gt; B: &lt;marker 1 (not E3/C5/D4)&gt;&lt;random key part 4&gt;                                       (plain)
 *          RC4[&lt;MAGICVALUE_SYNC 4&gt;&lt;methods supported 1&gt;&lt;method preferred 1&gt;&lt;padLen 1&gt;&lt;padding&gt;]
 * B -&gt; A: RC4[&lt;MAGICVALUE_SYNC 4&gt;&lt;method selected 1&gt;&lt;padLen 1&gt;&lt;padding&gt;]
 * </pre>
 * The keys come from the user hash of the client being dialled: only that client can decrypt the request, so a
 * different client behind the same port fails the handshake. All integers are little-endian.
 */
public final class ClientObfuscation {
    private static final int MAX_PADDING = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientObfuscation() {}

    /** The streams of an established obfuscated connection. */
    public record Streams(InputStream in, OutputStream out) {}

    /** The handshake did not complete: the peer closed, timed out, or did not answer with the magic value. */
    public static final class HandshakeException extends IOException {
        HandshakeException(String message) {
            super(message);
        }
    }

    /**
     * Runs the handshake as client A on a connected socket's streams and returns the streams to exchange packets on.
     *
     * @param targetHash user hash of the client being dialled
     * @throws HandshakeException when the peer does not complete the handshake
     */
    public static Streams negotiate(InputStream rawIn, OutputStream rawOut, byte[] targetHash) throws IOException {
        int randomKeyPart = RANDOM.nextInt();
        Obfuscation.RC4 sendKey = key(targetHash, ObfuscationHandler.MAGICVALUE_REQUESTER, randomKeyPart);
        Obfuscation.RC4 receiveKey = key(targetHash, ObfuscationHandler.MAGICVALUE_SERVER, randomKeyPart);

        int padLen = RANDOM.nextInt(MAX_PADDING + 1);
        ByteBuffer request = ByteBuffer.allocate(5 + 7 + padLen).order(ByteOrder.LITTLE_ENDIAN);
        request.put(marker()).putInt(randomKeyPart);
        request.putInt(ObfuscationHandler.MAGICVALUE_SYNC)
                .put(ObfuscationHandler.ENM_OBFUSCATION)  // methods supported
                .put(ObfuscationHandler.ENM_OBFUSCATION)  // method preferred
                .put((byte) padLen);
        byte[] padding = new byte[padLen];
        RANDOM.nextBytes(padding);
        request.put(padding);
        byte[] bytes = request.array();
        sendKey.crypt(bytes, 5, bytes.length - 5);
        rawOut.write(bytes);
        rawOut.flush();

        InputStream in = new ObfuscationHandler.ObfuscatedInputStream(rawIn, receiveKey);
        try {
            byte[] answer = in.readNBytes(6);
            if (answer.length < 6) throw new HandshakeException("connection closed during the obfuscated handshake");
            ByteBuffer header = ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN);
            if (header.getInt() != ObfuscationHandler.MAGICVALUE_SYNC) {
                throw new HandshakeException("wrong magic value in the obfuscated handshake answer");
            }
            byte method = header.get();
            int answerPadLen = Byte.toUnsignedInt(header.get());
            if (method != ObfuscationHandler.ENM_OBFUSCATION) {
                throw new HandshakeException("unsupported encryption method " + method);
            }
            if (in.readNBytes(answerPadLen).length < answerPadLen) {
                throw new HandshakeException("connection closed in the obfuscated handshake padding");
            }
        } catch (HandshakeException e) {
            throw e;
        } catch (IOException e) {
            throw new HandshakeException("no answer to the obfuscated handshake: " + e.getMessage());
        }
        return new Streams(in, new ObfuscationHandler.ObfuscatedOutputStream(rawOut, sendKey));
    }

    /** RC4 keyed with MD5(&lt;hash 16&gt;&lt;magic 1&gt;&lt;random key part 4&gt;), first 1024 bytes discarded. */
    static Obfuscation.RC4 key(byte[] targetHash, byte magic, int randomKeyPart) {
        byte[] keyData = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN)
                .put(targetHash, 0, 16).put(magic).putInt(randomKeyPart).array();
        Obfuscation.RC4 rc4 = new Obfuscation.RC4(Obfuscation.md5(keyData));
        rc4.crypt(new byte[1024]);
        return rc4;
    }

    /** First byte: anything but a protocol marker, so that the peer reads an obfuscated connection. */
    private static byte marker() {
        byte marker;
        do {
            marker = (byte) RANDOM.nextInt(256);
        } while (marker == Packet.PROTOCOL_ED2K || marker == Packet.PROTOCOL_EMULE || marker == Packet.PROTOCOL_ZLIB);
        return marker;
    }
}
