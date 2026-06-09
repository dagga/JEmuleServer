package org.jemule.network.handler;

import org.jemule.network.Packet;
import org.jemule.security.Obfuscation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;

public class ObfuscationHandler {
    private static final Logger log = LoggerFactory.getLogger(ObfuscationHandler.class);

    public InputStream negotiateObfuscation(ClientContext context, InputStream in, OutputStream out) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(in, 1024);
        byte[] probe = new byte[6];
        int read = 0;
        while (read < probe.length) {
            int r = pin.read(probe, read, probe.length - read);
            if (r == -1) break;
            read += r;
        }

        if (read == 0) return pin;

        int firstByte = probe[0] & 0xFF;
        if (firstByte == (Packet.PROTOCOL_ED2K & 0xFF) || firstByte == (Packet.PROTOCOL_EMULE & 0xFF) || firstByte == (Packet.PROTOCOL_ZLIB & 0xFF)) {
            pin.unread(probe, 0, read);
            return pin;
        }

        if (read < probe.length) {
            pin.unread(probe, 0, read);
            return pin;
        }

        // Search for 0x97 marker (standard eMule server obfuscation marker)
        int markerPos = -1;
        // eMule handshake: [random(1)] [clientNonce(4)] [padding(0-15)] [0x97]
        // But some implementations or versions might vary.
        // We'll read up to 64 bytes to be safe, as some clients might send more padding.
        int maxProbe = 64;
        byte[] fullProbe = new byte[maxProbe];
        System.arraycopy(probe, 0, fullProbe, 0, read);
        
        // Read more bytes (blocking) up to maxProbe to find the 0x97 marker
        while (read < maxProbe) {
            int r = pin.read(fullProbe, read, 1);
            if (r == -1) break;
            read += r;
            int b = fullProbe[read - 1] & 0xFF;
            if (b == 0x97 && read >= 5) { // ensure we have at least 1 + 4 before marker
                markerPos = read - 1;
                break;
            }
            // Fast path: if we already have a full ED2K header pattern by chance (very unlikely here), we'd have returned earlier.
        }

        if (markerPos == -1) {
            // Fallback scan (in case marker was earlier in buffer)
            for (int i = 0; i < read; i++) {
                if ((fullProbe[i] & 0xFF) == 0x97 && i >= 5) {
                    markerPos = i;
                    break;
                }
            }
        }

        if (markerPos == -1 || markerPos < 5) { // Needs at least 1 byte (any) + 4 bytes (nonce) before 0x97
            if (log.isDebugEnabled() && read > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(read, 32); i++) sb.append(String.format("%02X ", fullProbe[i]));
                log.debug("Obfuscation marker 0x97 not found in first {} bytes: {}", read, sb.toString().trim());
            }
            pin.unread(fullProbe, 0, read);
            return pin;
        }

        probe = fullProbe; // Use the full buffer for further processing

        // Push back everything AFTER the marker so the next read returns the encrypted method byte
        int afterLen = read - (markerPos + 1);
        if (afterLen > 0) {
            pin.unread(probe, markerPos + 1, afterLen);
            read -= afterLen; // logical position for our local buffer
        }

        SocketAddress remoteAddr = context.getSocket().getRemoteSocketAddress();
        log.info("Detected obfuscated handshake (marker at {}) from {}", markerPos, remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown");

        // The 4 bytes BEFORE the marker are the client random nonce
        byte[] clientRandom = new byte[4];
        System.arraycopy(probe, markerPos - 4, clientRandom, 0, 4);

        // eMule Server Obfuscation uses 0x97 as magic
        byte[] magic = {(byte) 0x97};
        
        // Keys are MD5(magic + clientRandom)
        byte[] key = Obfuscation.md5(magic, clientRandom);
        Obfuscation.RC4 receiveRC4 = new Obfuscation.RC4(key);
        Obfuscation.RC4 sendRC4 = new Obfuscation.RC4(key);

        // DISCARD first 1024 bytes of RC4 stream (standard eMule obfuscation)
        receiveRC4.crypt(new byte[1024]);
        sendRC4.crypt(new byte[1024]);

        InputStream encryptedIn = new ObfuscatedInputStream(pin, receiveRC4);

        // After 0x97, client sends its encryption method (0x00 for Obfuscation)
        // This is ALREADY ENCRYPTED.
        int method = encryptedIn.read();
        if (method != 0x00) {
            log.warn("Obfuscation failed for {}: invalid method 0x{}", 
                remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown",
                String.format("%02X", method));
            // We can't really unread because we've started decrypting
            throw new IOException("Invalid obfuscation method: " + method);
        }

        // Server responds with its own handshake: [padLen(1)] [random(padLen)] [0x00]
        SecureRandom rng = new SecureRandom();
        int serverPadLen = rng.nextInt(16);
        byte[] serverHandshake = new byte[1 + serverPadLen + 1];
        rng.nextBytes(serverHandshake);
        serverHandshake[0] = (byte) serverPadLen;
        serverHandshake[serverHandshake.length - 1] = 0x00; // method 0x00

        byte[] encryptedResponse = serverHandshake.clone();
        sendRC4.crypt(encryptedResponse);
        out.write(encryptedResponse);
        out.flush();

        log.info("Obfuscation handshake complete for {}", remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown");
        context.setObfuscated(true);
        context.setWrappedOut(new ObfuscatedOutputStream(out, sendRC4));
        return encryptedIn;
    }

    public InputStream handleObfuscatedHandshake(ClientContext context, int firstByte, InputStream in, OutputStream out) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        byte[] clientRandom = new byte[4];
        dis.readFully(clientRandom);

        SocketAddress remoteAddr = context.getSocket().getRemoteSocketAddress();
        if (Obfuscation.isReplay(clientRandom)) {
            throw new IOException("Replay attack detected from " + (remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown"));
        }

        int marker = dis.readUnsignedByte();
        if (marker != 0x97) {
            throw new IOException("Invalid obfuscation marker: 0x" + Integer.toHexString(marker));
        }

        byte[] magic = {(byte) 0x97};
        Obfuscation.RC4 receiveRC4 = new Obfuscation.RC4(Obfuscation.md5(magic, clientRandom));
        Obfuscation.RC4 sendRC4 = new Obfuscation.RC4(Obfuscation.md5(magic, clientRandom));

        receiveRC4.crypt(new byte[1024]);
        sendRC4.crypt(new byte[1024]);

        InputStream encryptedIn = new ObfuscatedInputStream(in, receiveRC4);
        
        int method = -1;
        for (int k = 0; k < 256; k++) {
            int b = encryptedIn.read();
            if (b == 0x00) {
                method = b;
                break;
            }
        }

        if (method != 0x00) throw new IOException("Unsupported encryption method or handshake timeout");

        SecureRandom rng = new SecureRandom();
        int serverPadLen = rng.nextInt(16);
        byte[] serverHandshake = new byte[1 + serverPadLen + 1];
        rng.nextBytes(serverHandshake);
        serverHandshake[0] = (byte) serverPadLen;
        serverHandshake[serverHandshake.length - 1] = 0x00;

        byte[] encryptedResponse = serverHandshake.clone();
        sendRC4.crypt(encryptedResponse);
        out.write(encryptedResponse);
        out.flush();

        log.info("Obfuscation handshake complete for {}", remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown");
        context.setObfuscated(true);
        context.setWrappedOut(new ObfuscatedOutputStream(out, sendRC4));
        return encryptedIn;
    }

    public static class ObfuscatedInputStream extends InputStream {
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

    public static class ObfuscatedOutputStream extends OutputStream {
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
}
