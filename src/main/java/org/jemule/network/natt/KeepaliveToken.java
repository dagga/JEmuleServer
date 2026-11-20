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

package org.jemule.network.natt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * NAT callback session token (ed2k-server 0.9.88, issue andrey23127/ed2k-server#40; aMule master a6d6290be0,
 * docs/NAT_T_Protocol.md and NatServerHolePunch::BuildKeepaliveProof() in NatServerKeepaliveProof.cpp).
 * <p>
 * A user hash is no secret, so a plain {@code <user hash 16>} 0x9F counts only from the session's login IP. That
 * leaves out a client whose UDP leaves from another public IPv4 than its TCP (carrier NAT, VPN). So a session gets 16
 * random bytes in OP_SERVERIDENT, ST_NATT_TOKEN (0xAC, hash tag) with ST_NATT_PROOF (0xA9, uint32 = 1), new at every
 * login and never sent over UDP. The client proves it in a 36-byte 0x9F, always plain:
 * <pre>&lt;user hash 16&gt;&lt;counter 4 LE&gt;&lt;mac 16&gt;
 * mac = HMAC-SHA256(key = token, &lt;user hash 16&gt;&lt;counter 4 LE&gt;), first 16 bytes</pre>
 * The counter starts at 1 and rises with each 0x9F of the login. A proof with the right mac and a counter above the
 * last one accepted in this login is the session's own from any address; a wrong mac or a counter not above the last
 * (a replay) is no keepalive at all.
 */
public final class KeepaliveToken {
    /** Size of the token, ST_NATT_TOKEN. */
    public static final int TOKEN_SIZE = 16;
    /** Size of the truncated HMAC-SHA256 (kKeepaliveProofMacSize). */
    public static final int MAC_SIZE = 16;
    /** Size of the proof form of 0x9F, {@code <user hash 16><counter 4><mac 16>} (kKeepaliveProofSize). */
    public static final int PROOF_SIZE = 16 + 4 + MAC_SIZE;
    /** Value of ST_NATT_PROOF: the version of the proof form the server takes. */
    public static final int PROOF_VERSION = 1;

    private static final String HMAC = "HmacSHA256";
    private static final int SIGNED_SIZE = 16 + 4;

    /** What a proof 0x9F is worth. */
    public enum Verdict {
        /** Right mac and a counter above the last accepted: the session's own, from any address. */
        ACCEPTED,
        /** The session holds no token: the proof cannot be checked. */
        NO_TOKEN,
        /** Not a proof of this session's token (or not {@link #PROOF_SIZE} bytes). */
        BAD_MAC,
        /** Right mac, but a counter not above the last accepted one. */
        REPLAY
    }

    /** One login's token and the last counter accepted (0: none yet, the client starts at 1). */
    private static final class Session {
        private final byte[] token;
        private long lastCounter;

        private Session(byte[] token) {
            this.token = token;
        }
    }

    /** Tokens of the sessions holding one, by connection ID: a new login is a new connection, so a new token. */
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random;

    public KeepaliveToken() {
        this(new SecureRandom());
    }

    public KeepaliveToken(SecureRandom random) {
        this.random = requireNonNull(random);
    }

    /** Draws a new token for the connection {@code connectionId}, forgetting its previous one and counter. */
    public byte[] issue(int connectionId) {
        byte[] token = new byte[TOKEN_SIZE];
        random.nextBytes(token);
        sessions.put(connectionId, new Session(token));
        return token.clone();
    }

    /** Whether the connection {@code connectionId} holds a token. */
    public boolean holds(int connectionId) {
        return sessions.containsKey(connectionId);
    }

    /** Forgets the token of a closed connection. */
    public void forget(int connectionId) {
        sessions.remove(connectionId);
    }

    /** Number of sessions holding a token. */
    public int size() {
        return sessions.size();
    }

    /**
     * Checks a proof 0x9F payload (after the opcode) of the connection {@code connectionId}, and when it is good,
     * records its counter so that it cannot be played again.
     */
    public Verdict verify(int connectionId, byte[] payload) {
        Session session = sessions.get(connectionId);
        if (session == null) return Verdict.NO_TOKEN;
        if (payload == null || payload.length != PROOF_SIZE) return Verdict.BAD_MAC;
        byte[] expected = mac(session.token, payload);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(payload, SIGNED_SIZE, PROOF_SIZE))) {
            return Verdict.BAD_MAC;
        }
        long counter = Integer.toUnsignedLong(ByteBuffer.wrap(payload, 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        synchronized (session) {
            if (counter <= session.lastCounter) return Verdict.REPLAY;
            session.lastCounter = counter;
        }
        return Verdict.ACCEPTED;
    }

    /**
     * The proof form of 0x9F as the client builds it (NatServerHolePunch::BuildKeepaliveProof):
     * {@code <user hash 16><counter 4 LE><mac 16>}.
     */
    public static byte[] proof(byte[] userHash, byte[] token, long counter) {
        if (userHash.length != 16) throw new IllegalArgumentException("user hash must be 16 bytes");
        ByteBuffer buf = ByteBuffer.allocate(PROOF_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(userHash).putInt((int) counter);
        byte[] payload = buf.array();
        System.arraycopy(mac(token, payload), 0, payload, SIGNED_SIZE, MAC_SIZE);
        return payload;
    }

    /** The first {@link #MAC_SIZE} bytes of HMAC-SHA256 keyed with {@code token} over the first 20 bytes. */
    static byte[] mac(byte[] token, byte[] payload) {
        try {
            Mac hmac = Mac.getInstance(HMAC);
            hmac.init(new SecretKeySpec(token, HMAC));
            hmac.update(payload, 0, SIGNED_SIZE);
            return Arrays.copyOf(hmac.doFinal(), MAC_SIZE);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
