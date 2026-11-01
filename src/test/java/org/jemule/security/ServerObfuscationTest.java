package org.jemule.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class ServerObfuscationTest {
    @Test
    void encodesAndDecodesServerDatagrams() {
        byte[] message = {(byte) 0xE3, (byte) 0xA4};
        int key = 0x4A51BC27;

        byte[] encoded = ServerObfuscation.encode(message, key, ServerObfuscation.REQUEST);

        assertNotEquals((byte) 0xE3, encoded[0]);
        assertNotEquals((byte) 0xD4, encoded[0]);
        assertNotEquals((byte) 0xC5, encoded[0]);
        assertArrayEquals(message, ServerObfuscation.decode(encoded, key, ServerObfuscation.REQUEST));
        assertNull(ServerObfuscation.decode(encoded, key, ServerObfuscation.ANSWER), "the direction is part of the key");
    }

    @Test
    void isEmulesClientServerUdpObfuscation() {
        // Lugdunum obfuscates server traffic as an eMule client does towards a server, and answers as a server does
        byte[] message = {(byte) 0xE3, (byte) 0xA0, 1, 2, 3, 4, 0x35, 0x12, 9, 8, 7, 6};
        int key = 0x0BADCAFE;
        assertArrayEquals(message, UdpObfuscation.decryptFromClient(
                ServerObfuscation.encode(message, key, ServerObfuscation.REQUEST), key));
        assertArrayEquals(message, ServerObfuscation.decode(
                UdpObfuscation.encryptForClient(message, key), key, ServerObfuscation.ANSWER));
        assertEquals(ServerObfuscation.ANSWER, ServerObfuscation.reverse(ServerObfuscation.REQUEST));
        assertEquals(ServerObfuscation.REQUEST, ServerObfuscation.reverse(ServerObfuscation.ANSWER));
    }

    @Test
    void cryptPingsCarryTheRandomPartInClear() {
        for (int i = 0; i < 200; i++) {
            int randomPart = ServerObfuscation.cryptPingRandomPart();
            assertNotEquals(0, randomPart);
            byte first = (byte) randomPart;
            assertTrue(first != (byte) 0xE3 && first != (byte) 0xD4 && first != (byte) 0xC5);
            byte[] ping = ServerObfuscation.cryptPing(randomPart);
            assertTrue(ping.length >= 4 && ping.length <= 18, "4 bytes and up to 14 of padding");
            assertEquals(randomPart, java.nio.ByteBuffer.wrap(ping, 0, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        }
    }

    @Test
    void rejectsWrongKeysAndMalformedFrames() {
        byte[] encoded = ServerObfuscation.encode(new byte[]{(byte) 0xE3, (byte) 0xA4}, 0x12345678,
                ServerObfuscation.ANSWER);

        assertNull(ServerObfuscation.decode(encoded, 0x87654321, ServerObfuscation.ANSWER));
        assertNull(ServerObfuscation.decode(new byte[]{1, 2, 3}, 0x12345678, ServerObfuscation.ANSWER));
        assertNull(ServerObfuscation.decode(null, 0x12345678, ServerObfuscation.ANSWER));
        assertNull(ServerObfuscation.decode(encoded, 0, ServerObfuscation.ANSWER));
    }

    @Test
    void derivesStableNonzeroKeysPerIpv4Peer() throws Exception {
        byte[] secret = new byte[16];
        InetAddress peer = InetAddress.getByName("192.0.2.45");
        int key = ServerObfuscation.serverKeyFor(secret, peer);

        assertNotEquals(0, key);
        assertEquals(key, ServerObfuscation.serverKeyFor(secret, peer));
        assertNotEquals(key, ServerObfuscation.serverKeyFor(new byte[16],
                InetAddress.getByName("192.0.2.46")));
    }

    @Test
    void validatesSecretLengthAndAddressFamily() {
        assertThrows(IllegalArgumentException.class, () ->
                ServerObfuscation.serverKeyFor(new byte[15], InetAddress.getByName("192.0.2.45")));
        assertThrows(IllegalArgumentException.class, () ->
                ServerObfuscation.serverKeyFor(new byte[16], InetAddress.getByName("2001:db8::1")));
    }
}
