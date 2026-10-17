package org.jemule.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class ServerObfuscationTest {
    @Test
    void encodesAndDecodesServerDatagrams() {
        byte[] message = {(byte) 0xE3, (byte) 0xA4};
        int key = 0x4A51BC27;

        byte[] encoded = ServerObfuscation.encode(message, key);

        assertNotEquals((byte) 0xE3, encoded[0]);
        assertNotEquals((byte) 0xD4, encoded[0]);
        assertNotEquals((byte) 0xC5, encoded[0]);
        assertArrayEquals(message, ServerObfuscation.decode(encoded, key));
    }

    @Test
    void rejectsWrongKeysAndMalformedFrames() {
        byte[] encoded = ServerObfuscation.encode(new byte[]{(byte) 0xE3, (byte) 0xA4}, 0x12345678);

        assertNull(ServerObfuscation.decode(encoded, 0x87654321));
        assertNull(ServerObfuscation.decode(new byte[]{1, 2, 3}, 0x12345678));
        assertNull(ServerObfuscation.decode(null, 0x12345678));
        assertNull(ServerObfuscation.decode(encoded, 0));
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
