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
    // DH parameters (copied from eMule implementation)
    private static final int PRIMESIZE_BYTES = 96;
    private static final int DHAGREEMENT_A_BITS = 128;
    private static final int MAGICVALUE_SYNC = 0x835E6FC4;
    private static final byte[] dh768_p = new byte[] {
            (byte)0xF2,(byte)0xBF,(byte)0x52,(byte)0xC5,(byte)0x5F,(byte)0x58,(byte)0x7A,(byte)0xDD,(byte)0x53,(byte)0x71,(byte)0xA9,(byte)0x36,
            (byte)0xE8,(byte)0x86,(byte)0xEB,(byte)0x3C,(byte)0x62,(byte)0x17,(byte)0xA3,(byte)0x3E,(byte)0xC3,(byte)0x4C,(byte)0xB4,(byte)0x0D,
            (byte)0xC7,(byte)0x3A,(byte)0x41,(byte)0xA6,(byte)0x43,(byte)0xAF,(byte)0xFC,(byte)0xE7,(byte)0x21,(byte)0xFC,(byte)0x28,(byte)0x63,
            (byte)0x66,(byte)0x53,(byte)0x5B,(byte)0xDB,(byte)0xCE,(byte)0x25,(byte)0x9F,(byte)0x22,(byte)0x86,(byte)0xDA,(byte)0x4A,(byte)0x91,
            (byte)0xB2,(byte)0x07,(byte)0xCB,(byte)0xAA,(byte)0x52,(byte)0x55,(byte)0xD4,(byte)0xF6,(byte)0x1C,(byte)0xCE,(byte)0xAE,(byte)0xD4,
            (byte)0x5A,(byte)0xD5,(byte)0xE0,(byte)0x74,(byte)0x7D,(byte)0xF7,(byte)0x78,(byte)0x18,(byte)0x28,(byte)0x10,(byte)0x5F,(byte)0x34,
            (byte)0x0F,(byte)0x76,(byte)0x23,(byte)0x87,(byte)0xF8,(byte)0x8B,(byte)0x28,(byte)0x91,(byte)0x42,(byte)0xFB,(byte)0x42,(byte)0x68,
            (byte)0x8F,(byte)0x05,(byte)0x15,(byte)0x0F,(byte)0x54,(byte)0x8B,(byte)0x5F,(byte)0x43,(byte)0x6A,(byte)0xF7,(byte)0x0D,(byte)0xF3
    };

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
        // We'll read up to enough bytes to match either simplified marker-based or DH-based handshakes
        int maxProbe = 128;
        byte[] fullProbe = new byte[maxProbe];
        System.arraycopy(probe, 0, fullProbe, 0, read);

        // Read more bytes (blocking) up to maxProbe
        while (read < maxProbe) {
            int r = pin.read(fullProbe, read, 1);
            if (r == -1) break;
            read += r;
            int b = fullProbe[read - 1] & 0xFF;
            if (b == 0x97 && read >= 5) { // ensure we have at least 1 + 4 before marker
                markerPos = read - 1;
                break;
            }
            if (read >= 1 + PRIMESIZE_BYTES) break; // enough for DH detection
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

        // Decide mode: simplified marker-based or DH if we have enough bytes
        boolean dhMode = false;
        if (markerPos == -1 && read >= 1 + PRIMESIZE_BYTES) dhMode = true;

        if (!dhMode && (markerPos == -1 || markerPos < 5)) { // Needs at least 1 byte (any) + 4 bytes (nonce) before 0x97
            // If it's not DH mode and we don't have a marker, check if it's a server connection
            // Servers should generally favor DH if it's not a standard protocol.
            if (read >= 1 + PRIMESIZE_BYTES) {
                dhMode = true;
            } else {
                if (log.isDebugEnabled() && read > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(read, 64); i++) sb.append(String.format("%02X ", fullProbe[i]));
                    log.debug("Obfuscation marker 0x97 not found and not enough for DH. First {} bytes: {}", read, sb.toString().trim());
                }
                if (firstByte != (Packet.PROTOCOL_ED2K & 0xFF) && firstByte != (Packet.PROTOCOL_EMULE & 0xFF) && firstByte != (Packet.PROTOCOL_ZLIB & 0xFF)) {
                    throw new IOException(String.format("Invalid protocol or failed obfuscation handshake (first byte: 0x%02X)", firstByte));
                }
                pin.unread(fullProbe, 0, read);
                return pin;
            }
        }

        probe = fullProbe; // Use the full buffer for further processing

        SocketAddress remoteAddr = context.getSocket().getRemoteSocketAddress();

        if (!dhMode) {
            if (markerPos != -1 && markerPos >= 5 && read < 1 + PRIMESIZE_BYTES) {
                // simplified marker-based server obfuscation (existing behavior)
                // Push back everything AFTER the marker so the next read returns the encrypted method byte
                int afterLen = read - (markerPos + 1);
                if (afterLen > 0) {
                    pin.unread(probe, markerPos + 1, afterLen);
                    read -= afterLen; // logical position for our local buffer
                }

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
            } else {
                dhMode = true;
            }
        }

        // --- DH-mode handling ---
        log.info("Detected DH obfuscated handshake (DH mode) from {}", remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown");

        // Extract g^a from buffer: starts at index 1 and length PRIMESIZE_BYTES
        byte[] clientExpBytes = new byte[PRIMESIZE_BYTES];
        System.arraycopy(probe, 1, clientExpBytes, 0, PRIMESIZE_BYTES);

        try {
            java.math.BigInteger prime = new java.math.BigInteger(1, dh768_p);
            java.math.BigInteger g = java.math.BigInteger.valueOf(2);
            // create server private 'b' with DHAGREEMENT_A_BITS bits
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            java.math.BigInteger b = new java.math.BigInteger(DHAGREEMENT_A_BITS, rnd);

            // compute g^b mod p (server public)
            java.math.BigInteger gb = g.modPow(b, prime);
            byte[] gbBytes = toFixedLength(gb.toByteArray(), PRIMESIZE_BYTES);

            // send g^b (unencrypted)
            out.write(gbBytes);

            // compute shared secret S = (g^a)^b mod p
            java.math.BigInteger ga = new java.math.BigInteger(1, clientExpBytes);
            java.math.BigInteger shared = ga.modPow(b, prime);
            byte[] sharedBytes = toFixedLength(shared.toByteArray(), PRIMESIZE_BYTES);

            // Derive RC4 keys: server sendkey = MD5(S || MAGICVALUE_SERVER(203)), receivekey = MD5(S || MAGICVALUE_REQUESTER(34))
            byte[] magicServer = {(byte) 203};
            byte[] magicRequester = {(byte) 34};
            byte[] sendKey = Obfuscation.md5(sharedBytes, magicServer);
            byte[] receiveKey = Obfuscation.md5(sharedBytes, magicRequester);
            Obfuscation.RC4 receiveRC4 = new Obfuscation.RC4(receiveKey);
            Obfuscation.RC4 sendRC4 = new Obfuscation.RC4(sendKey);

            // DISCARD first 1024 bytes of RC4 stream
            receiveRC4.crypt(new byte[1024]);
            sendRC4.crypt(new byte[1024]);

            // Build server response: <MagicValue 4><EncryptionMethodsSupported 1><EncryptionMethodPreferred 1><PaddingLen 1><RandomBytes PaddingLen>
            java.nio.ByteBuffer resp = java.nio.ByteBuffer.allocate(4 + 1 + 1 + 1 + 16);
            resp.putInt(MAGICVALUE_SYNC);
            resp.put((byte) 0x00); // ENM_OBFUSCATION
            resp.put((byte) 0x00); // preferred
            int pad = rnd.nextInt(16);
            resp.put((byte) pad);
            byte[] padBytes = new byte[pad];
            rnd.nextBytes(padBytes);
            resp.put(padBytes);
            byte[] respPlain = new byte[resp.position()];
            System.arraycopy(resp.array(), 0, respPlain, 0, respPlain.length);

            // encrypt entire response with sendRC4 and send it
            byte[] respEncrypted = respPlain.clone();
            sendRC4.crypt(respEncrypted);
            out.write(respEncrypted);
            out.flush();

            // Wrap input and output with RC4 streams for subsequent communication
            InputStream encryptedIn = new ObfuscatedInputStream(pin, receiveRC4);
            
            // Consume the Sync bytes that the client sends after g^b
            // requester sync: <MagicValue 4><EncryptionMethodsSupported 1><EncryptionMethodPreferred 1><PaddingLen 1><RandomBytes PaddingLen>
            // Note: client might delay this until first payload, but here it should be at the start of encryptedIn.
            // We search for MAGICVALUE_SYNC in the first 256 bytes to be robust.
            // If the first bytes look like a standard packet (0xE3, 0xC5, 0xD4), we skip sync searching.
            boolean syncFound = false;
            int firstVal = encryptedIn.read();
            if (firstVal != -1) {
                if (firstVal == (Packet.PROTOCOL_ED2K & 0xFF) || firstVal == (Packet.PROTOCOL_EMULE & 0xFF) || firstVal == (Packet.PROTOCOL_ZLIB & 0xFF)) {
                    log.debug("First byte 0x{} looks like protocol start, skipping DH sync search", Integer.toHexString(firstVal));
                    PushbackInputStream epin = new PushbackInputStream(encryptedIn, 1024);
                    epin.unread(firstVal);
                    encryptedIn = epin;
                } else if (firstVal == 0x97) {
                    log.debug("First byte is 0x97, might be simplified obfuscation method in DH mode or sync start");
                    // Continue search
                    int val = firstVal;
                    for (int i = 0; i < 256; i++) {
                        if (val == -1) break;
                        if (val == ((MAGICVALUE_SYNC >> 24) & 0xFF)) {
                            int b2 = encryptedIn.read();
                            if (b2 == ((MAGICVALUE_SYNC >> 16) & 0xFF)) {
                                int b3 = encryptedIn.read();
                                if (b3 == ((MAGICVALUE_SYNC >> 8) & 0xFF)) {
                                    int b4 = encryptedIn.read();
                                    if (b4 == (MAGICVALUE_SYNC & 0xFF)) {
                                        syncFound = true;
                                        break;
                                    }
                                }
                            }
                        }
                        val = encryptedIn.read();
                    }
                } else {
                    // Search for MAGICVALUE_SYNC starting with the byte we already read
                    int val = firstVal;
                    for (int i = 0; i < 256; i++) {
                        if (val == -1) break;
                        if (val == ((MAGICVALUE_SYNC >> 24) & 0xFF)) {
                            int b2 = encryptedIn.read();
                            if (b2 == ((MAGICVALUE_SYNC >> 16) & 0xFF)) {
                                int b3 = encryptedIn.read();
                                if (b3 == ((MAGICVALUE_SYNC >> 8) & 0xFF)) {
                                    int b4 = encryptedIn.read();
                                    if (b4 == (MAGICVALUE_SYNC & 0xFF)) {
                                        syncFound = true;
                                        break;
                                    }
                                }
                            }
                        }
                        val = encryptedIn.read();
                    }
                }
            }

            if (syncFound) {
                int meths = encryptedIn.read();
                int pref = encryptedIn.read();
                int padLen = encryptedIn.read();
                if (padLen > 0) {
                    encryptedIn.readNBytes(padLen);
                }
                log.debug("Consumed DH Sync from client: meths={}, pref={}, pad={}", meths, pref, padLen);
            } else {
                log.warn("DH sync magic NOT found in first 256 bytes from {}", remoteAddr);
            }

            context.setObfuscated(true);
            context.setWrappedOut(new ObfuscatedOutputStream(out, sendRC4));
            log.info("DH obfuscation handshake complete for {}", remoteAddr != null ? HandlerUtils.sanitize(remoteAddr.toString()) : "unknown");
            return encryptedIn;
        } catch (Exception ex) {
            throw new IOException("DH obfuscation failure", ex);
        }
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

    private static byte[] toFixedLength(byte[] in, int length) {
        if (in.length == length) return in;
        byte[] out = new byte[length];
        // copy from right (least significant) to preserve big-endian representation
        int copyLen = Math.min(in.length, length);
        System.arraycopy(in, Math.max(0, in.length - copyLen), out, length - copyLen, copyLen);
        return out;
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
