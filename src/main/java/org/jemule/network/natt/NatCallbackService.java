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

import org.jemule.core.ClientState;
import org.jemule.network.Packet;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Lugdunum eserver's LowID-to-LowID NAT callback between two clients of this server (eserver 17.15+), as described in
 * aMule master a6d6290be0, docs/NAT_T_Protocol.md, "Lugdunum NAT callback", and as ed2k-server
 * ({@code src/server/lugdunum_natt.rs}) and eNode-go ({@code ed2k/natcallback.go}) run it.
 * <ol>
 *   <li>Opt-in: the client sets {@code SRVCAP_NATTRAVERSAL} (0x1000) in {@code CT_SERVER_FLAGS}. It gets an odd LowID
 *   and the 24-byte OP_IDCHANGE, and is {@link #register registered} here: only registered clients may ask for a
 *   callback or be called back.</li>
 *   <li>Keepalive: UDP {@code 0x9F <user hash 16>} from the client UDP socket. Believed only from the client's login
 *   IP; its source is the client's public UDP endpoint, kept {@link #KEEPALIVE_TTL_MS} ({@link #endpointOf}).</li>
 *   <li>Request: UDP {@code 0xA6 <target ID 4><requester ID 4>}, server-key obfuscated, sent from the client UDP
 *   socket to the server's obfuscation UDP port: its source is the requester's UDP endpoint.</li>
 *   <li>Notice to the target: {@code <requester IP 4><requester UDP port 2><requester ID 4><crypt 1>[<user hash 16>]},
 *   by TCP 0x37 byte-identical to Lugdunum ({@link #notice}), or by UDP 0xA7, always with the user hash (27 bytes), to
 *   the endpoint of the target's own 0xA6 when that one is less than {@link #UDP_FRESH_MS} old (its NAT mapping towards
 *   the server is open). The target punches the requester's endpoint; the requester gets no answer (aMule then sends
 *   the reverse 0xA6, which brings it the target's endpoint).</li>
 * </ol>
 * Requester ID 0 marks the cross-server form, served by {@link CrossServerCallback} when natCallbackCrossServer is on
 * ({@link #handleForeignRequest}); a same-server notice never carries it, since LowIDs start at 1. Only IPv4 endpoints are handed out: a client logged in over IPv6 can neither ask (its
 * datagrams never match its login IPv4) nor be answered by UDP.
 * Every refusal is silent, as on eserver: a requester cannot tell an unknown target from a lost datagram.
 */
public final class NatCallbackService {
    private static final Logger log = LoggerFactory.getLogger(NatCallbackService.class);

    /** How long the endpoint of a 0x9F is kept: 600 s, as ed2k-server's OBSERVED_UDP_FRESH and eNode-go. */
    public static final long KEEPALIVE_TTL_MS = 600_000;
    /** A target whose own 0xA6 is younger than this gets UDP 0xA7 (Lugdunum: "more than 19 s ago: use TCP"). */
    public static final long UDP_FRESH_MS = 20_000;
    /** Requests per requester LowID: a burst of 10, then one a second (ed2k-server). */
    static final int REQUESTER_BURST = 10;
    static final long REQUESTER_INTERVAL_MS = 1_000;
    /** Notices per target LowID: a burst of 20, then one a second, so that many requesters cannot flood one target. */
    static final int TARGET_BURST = 20;
    static final long TARGET_INTERVAL_MS = 1_000;

    private static final int HASH_LENGTH = 16;
    private static final int CRYPT_SUPPORTED = 0x01;
    private static final int CRYPT_REQUESTED = 0x02;
    private static final int CRYPT_REQUIRED = 0x04;
    /** Set in the notice's crypt byte when the requester's user hash follows. */
    private static final int NOTICE_WITH_HASH = 0x80;
    /** {@code <IP 4><UDP port 2><ID 4><crypt 1><user hash 16>}. */
    private static final int NOTICE_UDP_LENGTH = 27;

    /** Where notices go. */
    public interface Transport {
        /** Sends {@code packet} on the server TCP connection of the client holding {@code clientId}. */
        void sendTcp(long clientId, Packet packet);

        /**
         * Sends {@code datagram} ({@code <0xE3><opcode><payload>}) to {@code to} from {@code socket}, obfuscated
         * with the server UDP key of {@code to}'s address (CEncryptedDatagramSocket::EncryptSendServer, server side).
         */
        void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram);
    }

    /** What became of a 0xA6. Only the two FORWARDED values and FOREIGN_WOKEN sent anything. */
    public enum Outcome {
        FORWARDED_TCP, FORWARDED_UDP,
        DISABLED, NOT_OBFUSCATED, SHORT,
        /** Requester ID 0: the cross-server form, while natCallbackCrossServer is off. */
        FOREIGN_UNSUPPORTED,
        /** Requester ID 0: the target was woken ({@link CrossServerCallback.Outcome} says more). */
        FOREIGN_WOKEN,
        /** Requester ID 0: refused ({@link CrossServerCallback.Outcome} says why). */
        FOREIGN_REFUSED,
        UNKNOWN_REQUESTER, WRONG_ADDRESS, REQUESTER_RATE_LIMITED, SAME_CLIENT,
        TARGET_NOT_CAPABLE, CRYPT_MISMATCH, TARGET_RATE_LIMITED
    }

    /** A datagram seen from a client: its source, when, and the server socket it reached (null for a 0x9F). */
    record Seen(UdpEndpoint endpoint, long at, DatagramSocket socket) {
    }

    /** A client that opted in, with LowID {@code lowId}. */
    static final class Session {
        final ClientState client;
        final long lowId;
        final TokenBucket asRequester;
        final TokenBucket asTarget;
        /** Last accepted 0x9F. */
        volatile Seen keepalive;
        /** Last 0xA6 sent by this client from its login address. */
        volatile Seen request;

        Session(ClientState client, long now) {
            this.client = client;
            this.lowId = client.clientId();
            this.asRequester = new TokenBucket(REQUESTER_BURST, REQUESTER_INTERVAL_MS, now);
            this.asTarget = new TokenBucket(TARGET_BURST, TARGET_INTERVAL_MS, now);
        }
    }

    /** A user hash as a map key. */
    record HashKey(long high, long low) {
        static HashKey of(byte[] bytes, int offset) {
            ByteBuffer b = ByteBuffer.wrap(bytes, offset, HASH_LENGTH);
            return new HashKey(b.getLong(), b.getLong());
        }
    }

    private final BooleanSupplier enabled;
    private final BooleanSupplier crossServerEnabled;
    private final LongSupplier clock;
    private final Transport transport;
    private final CrossServerCallback crossServer;
    private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HashKey, Session> byHash = new ConcurrentHashMap<>();

    /**
     * @param enabled   whether the callback is on (natCallback), read at each datagram
     * @param clock     current time in milliseconds
     * @param transport where notices go
     */
    public NatCallbackService(BooleanSupplier enabled, LongSupplier clock, Transport transport) {
        this(enabled, () -> false, ip -> false, clock, transport);
    }

    /**
     * @param enabled            whether the callback is on (natCallback), read at each datagram
     * @param crossServerEnabled whether the cross-server callback is on (natCallbackCrossServer); it also needs
     *                           {@code enabled}
     * @param blocked            addresses refused by the IP filter, for the cross-server requests
     * @param clock              current time in milliseconds
     * @param transport          where notices go
     */
    public NatCallbackService(BooleanSupplier enabled, BooleanSupplier crossServerEnabled,
                              Predicate<InetAddress> blocked, LongSupplier clock, Transport transport) {
        this.enabled = enabled;
        this.crossServerEnabled = crossServerEnabled;
        this.clock = clock;
        this.transport = transport;
        this.crossServer = new CrossServerCallback(this, blocked, clock);
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /** Whether the cross-server callback is taken (and announced): natCallbackCrossServer with natCallback. */
    public boolean isCrossServerEnabled() {
        return enabled.getAsBoolean() && crossServerEnabled.getAsBoolean();
    }

    /** UDP flags of ST_UDPFLAGS and OP_GLOBSERVSTATRES: SRV_UDPFLG_FOREIGN_NAT_CALLBACK only while it is taken. */
    public int udpFlags() {
        return Tag.serverUdpFlags(isCrossServerEnabled());
    }

    /** The cross-server callback. */
    public CrossServerCallback crossServer() {
        return crossServer;
    }

    /** Timeouts and resends of the cross-server callback; called a few times a second. */
    public void tick() {
        crossServer.tick(clock.getAsLong());
    }

    Transport transport() {
        return transport;
    }

    /** The session of {@code lowId}, if it opted in. */
    Session session(long lowId) {
        return sessions.get(lowId);
    }

    /** The endpoint of {@code session}'s last accepted 0x9F, if it is still its LowID's session and recent. */
    Optional<UdpEndpoint> endpointOf(Session session) {
        return sessions.get(session.lowId) == session ? endpointOf(session.lowId) : Optional.empty();
    }

    /** Whether a client announcing {@code serverCaps} in CT_SERVER_FLAGS opts in to the callback. */
    public static boolean optsIn(int serverCaps) {
        return (serverCaps & ClientState.SRVCAP_NATTRAVERSAL) != 0;
    }

    /**
     * Records a LowID client that opted in at login while the callback was on. A HighID cannot be called back and is
     * not recorded. A client logged in again under the same user hash replaces the older one.
     */
    public void register(ClientState client) {
        if (client.isHighId() || client.clientId() == 0) return;
        Session session = new Session(client, clock.getAsLong());
        sessions.put(session.lowId, session);
        byHash.put(HashKey.of(client.userHash(), 0), session);
    }

    /** Forgets a client that disconnected; nothing if it never opted in. */
    public void unregister(ClientState client) {
        if (client == null) return;
        sessions.computeIfPresent(client.clientId(), (id, s) -> s.client == client ? null : s);
        byHash.computeIfPresent(HashKey.of(client.userHash(), 0), (k, s) -> s.client == client ? null : s);
    }

    /** Whether {@code lowId} is a client of this server that opted in to the callback. */
    public boolean isOptedIn(long lowId) {
        return sessions.containsKey(lowId);
    }

    /** Clients registered for the callback. */
    public int sessionCount() {
        return sessions.size();
    }

    /** The UDP endpoint of {@code lowId}'s last accepted 0x9F, if it is younger than {@link #KEEPALIVE_TTL_MS}. */
    public Optional<UdpEndpoint> endpointOf(long lowId) {
        Session session = sessions.get(lowId);
        Seen seen = session == null ? null : session.keepalive;
        if (seen == null || clock.getAsLong() - seen.at() > KEEPALIVE_TTL_MS) return Optional.empty();
        return Optional.of(seen.endpoint());
    }

    /**
     * Records an accepted keepalive of {@code lowId} from {@code ip:port}, without any check: {@link #handleKeepalive}
     * decides what is believed. Nothing for a client that did not opt in.
     */
    public void onKeepalive(long lowId, InetAddress ip, int port) {
        Session session = sessions.get(lowId);
        if (session == null || port < 1 || port > 0xFFFF) return;
        UdpEndpoint endpoint = new UdpEndpoint(ip, port);
        session.keepalive = new Seen(endpoint, clock.getAsLong(), null);
        // The cross-server requesters waiting for this client's next 0x9F get its endpoint now
        crossServer.onTargetKeepalive(session, endpoint);
    }

    /**
     * UDP 0x9F (OP_NATT_KEEPALIVE) {@code <user hash 16>} from {@code ip:port}, payload after the opcode. The
     * session is found by its user hash; {@link #acceptKeepalive} decides whether it is believed.
     *
     * @return whether it was accepted
     */
    public boolean handleKeepalive(byte[] payload, InetAddress ip, int port, boolean obfuscated) {
        if (!enabled.getAsBoolean() || payload == null || payload.length < HASH_LENGTH || port < 1 || port > 0xFFFF) {
            return false;
        }
        Session session = byHash.get(HashKey.of(payload, 0));
        if (session == null || !acceptKeepalive(session, payload, ip, obfuscated)) {
            log.debug("NAT keepalive from {}:{} ignored", ip.getHostAddress(), port);
            return false;
        }
        onKeepalive(session.lowId, ip, port);
        return true;
    }

    /**
     * Whether a 0x9F of {@code session} is believed. A user hash is no secret, so the hash form (16 bytes; longer
     * payloads count as the hash alone) is believed only from the IPv4 the client logged in from (ed2k-server
     * README, "Implementing a compatible client", step 2).
     */
    boolean acceptKeepalive(Session session, byte[] payload, InetAddress ip, boolean obfuscated) {
        long source = ClientState.ipToLong(ip);
        return source != 0 && source == ClientState.ipToLong(session.client.address());
    }

    /**
     * UDP 0xA6 (OP_NAT_CALLBACKREQUEST) {@code <target ID 4><requester ID 4>} from {@code ip:port}, payload after the
     * opcode, received on {@code socket}. Forwarded to the target only when:
     * <ul>
     *   <li>the datagram was server-key obfuscated (the key is derived from the source IP, so decoding proves it);</li>
     *   <li>the requester ID is a client of this server that opted in, and the datagram comes from the IPv4 it logged
     *   in from or from the one of its last accepted 0x9F;</li>
     *   <li>the requester is within its rate ({@link #REQUESTER_BURST}, then one every
     *   {@link #REQUESTER_INTERVAL_MS} ms);</li>
     *   <li>the target is another client of this server that opted in (stricter than Lugdunum, which also forwards
     *   to stock clients that do not know 0x37);</li>
     *   <li>the two can use a common crypt setting ({@link #notice});</li>
     *   <li>the target is within its rate ({@link #TARGET_BURST}, then one every {@link #TARGET_INTERVAL_MS} ms).</li>
     * </ul>
     */
    public Outcome handleCallbackRequest(byte[] payload, InetAddress ip, int port, boolean obfuscated,
                                         DatagramSocket socket) {
        Outcome outcome = callbackRequest(payload, ip, port, obfuscated, socket);
        if (log.isDebugEnabled()) {
            log.debug("NAT callback request from {}:{}: {}", ip.getHostAddress(), port, outcome);
        }
        return outcome;
    }

    private Outcome callbackRequest(byte[] payload, InetAddress ip, int port, boolean obfuscated,
                                    DatagramSocket socket) {
        if (!enabled.getAsBoolean()) return Outcome.DISABLED;
        if (!obfuscated) return Outcome.NOT_OBFUSCATED;
        if (payload == null || payload.length < 8 || port < 1 || port > 0xFFFF) return Outcome.SHORT;
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long targetId = Integer.toUnsignedLong(b.getInt());
        long requesterId = Integer.toUnsignedLong(b.getInt());
        if (requesterId == 0) return handleForeignRequest(targetId, payload, ip, port, socket);

        Session requester = sessions.get(requesterId);
        if (requester == null) return Outcome.UNKNOWN_REQUESTER;
        long source = ClientState.ipToLong(ip);
        if (source == 0 || !fromRequester(requester, source)) return Outcome.WRONG_ADDRESS;
        long now = clock.getAsLong();
        UdpEndpoint from = new UdpEndpoint(ip, port);
        // Kept even when the rate is exceeded: it is where a UDP notice for this client goes
        requester.request = new Seen(from, now, socket);
        if (!requester.asRequester.tryTake(now)) return Outcome.REQUESTER_RATE_LIMITED;
        if (targetId == requesterId) return Outcome.SAME_CLIENT;

        Session target = sessions.get(targetId);
        if (target == null) return Outcome.TARGET_NOT_CAPABLE;
        byte[] body = notice(source, port, requesterId, requester.client.cryptOptions(), requester.client.userHash(),
                target.client.cryptOptions());
        if (body == null) return Outcome.CRYPT_MISMATCH;
        if (!target.asTarget.tryTake(now)) return Outcome.TARGET_RATE_LIMITED;
        return deliver(target, body, requester.client.userHash(), now);
    }

    /** The source IPv4 of a 0xA6 is the requester's login IPv4, or the one of its last accepted 0x9F. */
    private boolean fromRequester(Session requester, long source) {
        if (source == ClientState.ipToLong(requester.client.address())) return true;
        Seen keepalive = requester.keepalive;
        return keepalive != null && clock.getAsLong() - keepalive.at() <= KEEPALIVE_TTL_MS
                && source == ClientState.ipToLong(keepalive.endpoint().address());
    }

    /**
     * Requester ID 0: {@code <target ID 4><0 4>[<crypt 1>]} from a client logged in to another server (cross-server
     * NAT callback, ed2k-server 0.9.86; aMule master a6d6290be0, docs/NAT_T_Protocol.md). {@code payload} is the whole
     * request, {@code ip:port} its source, already known to be obfuscated. Dropped unless natCallbackCrossServer is
     * on, then served by {@link CrossServerCallback}.
     */
    Outcome handleForeignRequest(long targetId, byte[] payload, InetAddress ip, int port, DatagramSocket socket) {
        if (!crossServerEnabled.getAsBoolean()) return Outcome.FOREIGN_UNSUPPORTED;
        int crypt = payload.length > 8 ? payload[8] & 0x07 : 0; // absent: none, as on ed2k-server
        return crossServer.onRequest(targetId, crypt, ip, port, socket).woken()
                ? Outcome.FOREIGN_WOKEN : Outcome.FOREIGN_REFUSED;
    }

    /**
     * UDP 0xA7 to the endpoint of the target's own recent 0xA6, from the socket it reached, always 27 bytes
     * ({@code <IP 4><UDP port 2><ID 4><crypt 1><user hash 16>}, which aMule reads from 27 bytes on); TCP 0x37 with
     * {@code body} as it is otherwise.
     */
    private Outcome deliver(Session target, byte[] body, byte[] requesterHash, long now) {
        Seen recent = target.request;
        long targetIp = ClientState.ipToLong(target.client.address());
        if (recent != null && recent.socket() != null && now - recent.at() < UDP_FRESH_MS && targetIp != 0
                && ClientState.ipToLong(recent.endpoint().address()) == targetIp) {
            // Always 27 bytes by UDP: the user hash follows even without 0x80, which alone says "use the crypt layer"
            byte[] datagram = new byte[2 + NOTICE_UDP_LENGTH];
            datagram[0] = Packet.PROTOCOL_ED2K;
            datagram[1] = NatOpcodes.OP_NAT_CALLBACKREQUESTED_UDP;
            System.arraycopy(body, 0, datagram, 2, body.length);
            if (body.length < NOTICE_UDP_LENGTH) System.arraycopy(requesterHash, 0, datagram, 2 + body.length, HASH_LENGTH);
            transport.sendUdp(recent.socket(), recent.endpoint(), datagram);
            return Outcome.FORWARDED_UDP;
        }
        transport.sendTcp(target.lowId, new Packet(Packet.PROTOCOL_ED2K, NatOpcodes.OP_NAT_CALLBACKREQUESTED, body));
        return Outcome.FORWARDED_TCP;
    }

    /**
     * The notice body shared by TCP 0x37 and UDP 0xA7, describing a peer whose crypt options are {@code own} to a
     * reader whose crypt options are {@code other} (CT_SERVER_FLAGS bits 9-11: 0x01 supports, 0x02 requests, 0x04
     * requires): {@code <IP 4><UDP port 2><ID 4><crypt 1>[<user hash 16>]}, the IP in network byte order (as an ed2k
     * ID), the port and ID little-endian. {@code crypt} is {@code own}; the user hash follows, with 0x80 set in
     * {@code crypt}, when both support the crypt layer and one of them requests it. As eserver, ed2k-server and
     * eNode-go.
     *
     * @return the body, or null when one side requires the crypt layer and the two cannot use it
     */
    public static byte[] notice(long ipv4, int udpPort, long id, int own, byte[] userHash, int other) {
        own &= 0x07;
        other &= 0x07;
        boolean bothSupport = (own & other & CRYPT_SUPPORTED) != 0;
        boolean requested = ((own | other) & CRYPT_REQUESTED) != 0;
        boolean withHash = bothSupport && requested && userHash != null && userHash.length == HASH_LENGTH;
        if (!withHash && ((own | other) & CRYPT_REQUIRED) != 0) return null;
        ByteBuffer b = ByteBuffer.allocate(11 + (withHash ? HASH_LENGTH : 0)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) ipv4);
        b.putShort((short) udpPort);
        b.putInt((int) id);
        b.put((byte) (own | (withHash ? NOTICE_WITH_HASH : 0)));
        if (withHash) b.put(userHash);
        return b.array();
    }
}
