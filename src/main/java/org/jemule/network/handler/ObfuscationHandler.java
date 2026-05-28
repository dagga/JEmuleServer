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
        // First read 6 bytes — enough to check for a known protocol or
        // detect the common no-padding obfuscation handshake.
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

        // eMule obfuscation handshake: [userCount(1)] [randomKey(4)] [padding(0-15 zero bytes)] [0x97]
        // total length = 6..21 bytes; the 0x97 marker is always at the last position
        if ((probe[5] & 0xFF) == 0x97) {
            // Common case: no padding, marker at index 5 — proceed directly
        } else {
            // Probe has padding bytes before the marker; slurp up to 15 more bytes
            int more = Math.min(in.available(), 15);
            if (more > 0) {
                byte[] extra = new byte[6 + more];
                System.arraycopy(probe, 0, extra, 0, 6);
                int extraRead = pin.read(extra, 6, more);
                if (extraRead > 0) {
                    read = 6 + extraRead;
                    probe = extra;
                }
            }
            // Find the 0x97 marker scanning backward from the end
            int markerPos = -1;
            for (int i = read - 1; i >= 0; i--) {
                if ((probe[i] & 0xFF) == 0x97) {
                    markerPos = i;
                    break;
                }
            }
            if (markerPos < 5) {
                pin.unread(probe, 0, read);
                return pin;
            }
        }

        SocketAddress remoteAddr = context.getSocket().getRemoteSocketAddress();
        log.info("Detected obfuscated handshake from {}", remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown");

        byte[] clientRandom = new byte[4];
        System.arraycopy(probe, 1, clientRandom, 0, 4);

        byte[] magic = {(byte) 0x97};
        Obfuscation.RC4 receiveRC4 = new Obfuscation.RC4(Obfuscation.md5(magic, clientRandom));
        Obfuscation.RC4 sendRC4 = new Obfuscation.RC4(Obfuscation.md5(magic, clientRandom));

        receiveRC4.crypt(new byte[1024]);
        sendRC4.crypt(new byte[1024]);

        InputStream encryptedIn = new ObfuscatedInputStream(pin, receiveRC4);

        int method = -1;
        for (int k = 0; k < 256; k++) {
            int b = encryptedIn.read();
            if (b == 0x00) {
                method = b;
                break;
            }
            if (b == -1) break;
        }

        if (method != 0x00) {
            try { pin.unread(probe, 0, read); } catch (IOException ignored) {}
            throw new IOException("Unsupported encryption method or handshake timeout");
        }

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
