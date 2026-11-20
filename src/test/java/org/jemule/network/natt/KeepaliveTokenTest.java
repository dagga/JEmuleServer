package org.jemule.network.natt;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** The session token's proof of 0x9F, as aMule's NatServerKeepaliveProofTest checks it. */
class KeepaliveTokenTest {

    static final byte[] TOKEN = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");
    static final byte[] USER_HASH = HexFormat.of().parseHex("101112131415161718191a1b1c1d1e1f");

    @Test
    void matchesThePublishedTestVector() {
        // ed2k-server README and aMule master a6d6290be0 unittests/tests/NatServerKeepaliveProofTest.cpp
        byte[] proof = KeepaliveToken.proof(USER_HASH, TOKEN, 1);
        assertEquals(36, proof.length);
        assertArrayEquals(USER_HASH, Arrays.copyOf(proof, 16));
        assertArrayEquals(new byte[]{1, 0, 0, 0}, Arrays.copyOfRange(proof, 16, 20), "counter u32 LE");
        assertEquals("57f631c901f652e692307db3ea25be83", HexFormat.of().formatHex(Arrays.copyOfRange(proof, 20, 36)));
    }

    @Test
    void carriesTheCounterLittleEndian() {
        byte[] proof = KeepaliveToken.proof(USER_HASH, TOKEN, 0x04030201L);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Arrays.copyOfRange(proof, 16, 20));
    }

    @Test
    void acceptsOnlyARisingCounterWithTheRightMac() {
        KeepaliveToken tokens = new KeepaliveToken();
        assertEquals(KeepaliveToken.Verdict.NO_TOKEN, tokens.verify(7, KeepaliveToken.proof(USER_HASH, TOKEN, 1)));

        byte[] token = tokens.issue(7);
        assertEquals(KeepaliveToken.TOKEN_SIZE, token.length);
        assertEquals(KeepaliveToken.Verdict.ACCEPTED, tokens.verify(7, KeepaliveToken.proof(USER_HASH, token, 1)));
        assertEquals(KeepaliveToken.Verdict.REPLAY, tokens.verify(7, KeepaliveToken.proof(USER_HASH, token, 1)));
        assertEquals(KeepaliveToken.Verdict.ACCEPTED, tokens.verify(7, KeepaliveToken.proof(USER_HASH, token, 5)));
        assertEquals(KeepaliveToken.Verdict.REPLAY, tokens.verify(7, KeepaliveToken.proof(USER_HASH, token, 4)));
        // Counters are unsigned: above 2^31 still rises
        assertEquals(KeepaliveToken.Verdict.ACCEPTED, tokens.verify(7, KeepaliveToken.proof(USER_HASH, token, 0xFFFFFFF0L)));

        byte[] tampered = KeepaliveToken.proof(USER_HASH, token, 0xFFFFFFF1L);
        tampered[35] ^= 1;
        assertEquals(KeepaliveToken.Verdict.BAD_MAC, tokens.verify(7, tampered));
        assertEquals(KeepaliveToken.Verdict.BAD_MAC, tokens.verify(7, KeepaliveToken.proof(USER_HASH, TOKEN, 0xFFFFFFF2L)),
                "another token");
        assertEquals(KeepaliveToken.Verdict.BAD_MAC, tokens.verify(7, Arrays.copyOf(USER_HASH, 32)), "not 36 bytes");
    }

    @Test
    void aNewLoginDrawsANewTokenAndRestartsTheCounter() {
        KeepaliveToken tokens = new KeepaliveToken();
        byte[] first = tokens.issue(1);
        assertEquals(KeepaliveToken.Verdict.ACCEPTED, tokens.verify(1, KeepaliveToken.proof(USER_HASH, first, 9)));
        byte[] second = tokens.issue(1);
        assertFalse(Arrays.equals(first, second), "random per session");
        assertEquals(KeepaliveToken.Verdict.BAD_MAC, tokens.verify(1, KeepaliveToken.proof(USER_HASH, first, 10)));
        assertEquals(KeepaliveToken.Verdict.ACCEPTED, tokens.verify(1, KeepaliveToken.proof(USER_HASH, second, 1)));
        tokens.forget(1);
        assertFalse(tokens.holds(1));
        assertEquals(0, tokens.size());
    }
}
