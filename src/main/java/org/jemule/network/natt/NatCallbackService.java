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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
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
 *   IP; its source is the client's public UDP endpoint, kept {@link #KEEPALIVE_TTL_MS} ({@link #endpointOf}). With
 *   natCallbackToken, the proof of the session token ({@link KeepaliveToken}) is believed from any address
 *   ({@link #acceptKeepalive}).</li>
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
 * <p>
 * With natHolePunch, the same sessions and keepalives also serve ed2k-server's LowID hole punch (TCP 0x60 / 0x61 /
 * 0x62) and its extension ({@link LowIdHolePunch}). A LowID that announced a client UDP port in CT_EMULE_UDPPORTS
 * (0xF9) without opting in to the callback is then registered too, as a hole-punch session only: it may send 0x9F and
 * be a hole-punch target, but it neither asks for nor receives the callback.
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
    /** Size of ed2k-server 0.9.87's retired 0x9F token form, {@code <user hash 16><token 16>}. */
    static final int TOKEN_FORM_SIZE = HASH_LENGTH + KeepaliveToken.TOKEN_SIZE;

    /** Where notices go. */
    public interface Transport {
        /** Sends {@code packet} on the server TCP connection of the client holding {@code clientId}. */
        void sendTcp(long clientId, Packet packet);

        /** Sends {@code packet} on {@code client}'s own server TCP connection, if it is still open. */
        default void sendTcp(ClientState client, Packet packet) {
            sendTcp(client.clientId(), packet);
        }

        /**
         * Sends {@code datagram} ({@code <0xE3><opcode><payload>}) to {@code to} from {@code socket}, obfuscated
         * with the server UDP key of {@code to}'s address (CEncryptedDatagramSocket::EncryptSendServer, server side).
         */
        void sendUdp(DatagramSocket socket, UdpEndpoint to, byte[] datagram);
    }

    /** Runs the hole punch's timers: the extension's wait for a 0x9F and the 0x61 resends. */
    public interface Scheduler {
        /** Runs {@code task} once, {@code delayMs} from now. */
        void schedule(Runnable task, long delayMs);
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
        /** Opted in to Lugdunum's callback (0x1000): it may ask for it and be called back. */
        final boolean lugdunum;
        /** Announced the hole-punch extension: 0x100000 with 0x1000 in CT_SERVER_FLAGS. */
        final boolean extension;
        /** Callback requests and 0x60 as requester, notices and wakes as target: one allowance for both paths. */
        final TokenBucket asRequester;
        final TokenBucket asTarget;
        /**
         * The client UDP port it announced, given to hole-punch peers without a fresh 0x9F: the one of CT_EMULE_UDPPORTS
         * (0xF9), replaced by the one of its own 0x60, else the source port of its first accepted 0x9F; 0 if unknown.
         */
        volatile int udpPort;
        /** Last accepted 0x9F. */
        volatile Seen keepalive;
        /** Last 0xA6 sent by this client from its login address. */
        volatile Seen request;
        /** Whether an obfuscated 0x9F was believed: from then on a plain user hash alone is not. */
        volatile boolean obfuscatedKeepalive;

        Session(ClientState client, boolean lugdunum, long now) {
            this.client = client;
            this.lowId = client.clientId();
            this.lugdunum = lugdunum;
            int both = ClientState.SRVCAP_NATTRAVERSAL | ClientState.SRVCAP_HOLEPUNCH_EXTENSION;
            this.extension = lugdunum && (client.serverCaps() & both) == both;
            this.udpPort = clientUdpPort(client);
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
    private final BooleanSupplier tokenEnabled;
    private final BooleanSupplier crossServerEnabled;
    private final LongSupplier clock;
    private final Transport transport;
    private final CrossServerCallback crossServer;
    /** Session tokens of the 0x9F proofs, by connection. */
    private final KeepaliveToken tokens;
    /** ed2k-server's LowID hole punch, over the same sessions. */
    private final LowIdHolePunch holePunch;

    // 0x9F counters of ed2k-server 0.9.88 (natt_9f_*)
    private final LongAdder proofs = new LongAdder();
    private final LongAdder proofsOtherIp = new LongAdder();
    private final LongAdder proofsBad = new LongAdder();
    private final LongAdder proofsReplayed = new LongAdder();
    private final LongAdder tokenFormsIgnored = new LongAdder();
    private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HashKey, Session> byHash = new ConcurrentHashMap<>();

    /**
     * @param enabled   whether the callback is on (natCallback), read at each datagram
     * @param clock     current time in milliseconds
     * @param transport where notices go
     */
    public NatCallbackService(BooleanSupplier enabled, LongSupplier clock, Transport transport) {
        this(enabled, () -> false, clock, transport, new KeepaliveToken());
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
        this(enabled, () -> false, () -> false, crossServerEnabled, blocked, clock, transport, new KeepaliveToken(),
                id -> null, (task, delayMs) -> { });
    }

    /**
     * @param enabled      whether the callback is on (natCallback), read at each datagram
     * @param tokenEnabled whether the 0x9F session token is on (natCallbackToken), read at each login and datagram
     * @param clock        current time in milliseconds
     * @param transport    where notices go
     * @param tokens       the session tokens
     */
    public NatCallbackService(BooleanSupplier enabled, BooleanSupplier tokenEnabled, LongSupplier clock,
                              Transport transport, KeepaliveToken tokens) {
        this(enabled, tokenEnabled, () -> false, clock, transport, tokens, id -> null, (task, delayMs) -> { });
    }

    /**
     * @param enabled          whether the callback is on (natCallback), read at each datagram
     * @param tokenEnabled     whether the 0x9F session token is on (natCallbackToken), read at each login and datagram
     * @param holePunchEnabled whether the LowID hole punch is on (natCallback and natHolePunch), read at each 0x60
     * @param clock            current time in milliseconds
     * @param transport        where notices and hole-punch answers go
     * @param tokens           the session tokens
     * @param clients          the client logged in under a server ID, or null: a hole-punch target need not be
     *                         registered here to get its 0x62 reason
     * @param scheduler        runs the hole punch's timers
     */
    public NatCallbackService(BooleanSupplier enabled, BooleanSupplier tokenEnabled, BooleanSupplier holePunchEnabled,
                              LongSupplier clock, Transport transport, KeepaliveToken tokens,
                              LongFunction<ClientState> clients, Scheduler scheduler) {
        this(enabled, tokenEnabled, holePunchEnabled, () -> false, ip -> false, clock, transport, tokens, clients,
                scheduler);
    }

    /**
     * @param enabled            whether the callback is on (natCallback), read at each datagram
     * @param tokenEnabled       whether the 0x9F session token is on (natCallbackToken), read at each login and
     *                           datagram
     * @param holePunchEnabled   whether the LowID hole punch is on (natCallback and natHolePunch), read at each 0x60
     * @param crossServerEnabled whether the cross-server callback is on (natCallbackCrossServer); it also needs
     *                           {@code enabled}
     * @param blocked            addresses refused by the IP filter, for the cross-server requests
     * @param clock              current time in milliseconds
     * @param transport          where notices and hole-punch answers go
     * @param tokens             the session tokens
     * @param clients            the client logged in under a server ID, or null: a hole-punch target need not be
     *                           registered here to get its 0x62 reason
     * @param scheduler          runs the hole punch's timers
     */
    public NatCallbackService(BooleanSupplier enabled, BooleanSupplier tokenEnabled, BooleanSupplier holePunchEnabled,
                              BooleanSupplier crossServerEnabled, Predicate<InetAddress> blocked, LongSupplier clock,
                              Transport transport, KeepaliveToken tokens, LongFunction<ClientState> clients,
                              Scheduler scheduler) {
        this.enabled = enabled;
        this.tokenEnabled = tokenEnabled;
        this.crossServerEnabled = crossServerEnabled;
        this.clock = clock;
        this.transport = transport;
        this.tokens = tokens;
        this.crossServer = new CrossServerCallback(this, blocked, clock);
        this.holePunch = new LowIdHolePunch(this, holePunchEnabled, clients, clock, transport, scheduler);
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
        register(client, true);
    }

    /**
     * Records a LowID client: {@code lugdunum} when it opted in to the callback (0x1000), else as a hole-punch session
     * only, for a client that announced a UDP port in CT_EMULE_UDPPORTS (0xF9) while the hole punch was on. Both may
     * send 0x9F and be hole-punch targets; only the first may ask for or receive the callback.
     */
    public void register(ClientState client, boolean lugdunum) {
        if (client.isHighId() || client.clientId() == 0) return;
        Session session = new Session(client, lugdunum, clock.getAsLong());
        sessions.put(session.lowId, session);
        byHash.put(HashKey.of(client.userHash(), 0), session);
    }

    /** The client UDP port of CT_EMULE_UDPPORTS (0xF9), {@code (Kad UDP port << 16) | client UDP port}; 0 if none. */
    public static int clientUdpPort(ClientState client) {
        return client.emuleUdpPorts() & 0xFFFF;
    }

    /** The session of {@code client}, if it is the one registered under its LowID. */
    Session sessionOf(ClientState client) {
        Session session = sessions.get(client.clientId());
        return session != null && session.client == client ? session : null;
    }

    /** Whether {@code session} is still the one registered under its LowID. */
    boolean isRegistered(Session session) {
        return sessions.get(session.lowId) == session;
    }

    /**
     * Whether {@code client} may send 0x9F, so that it gets the session token: SRVCAP_NATTRAVERSAL (0x1000) or
     * SRVCAP_HOLEPUNCH_EXTENSION (0x100000) in CT_SERVER_FLAGS, or a UDP port in CT_EMULE_UDPPORTS (0xF9), as
     * ed2k-server 0.9.88. Never any other client: an unsolicited tag in OP_SERVERIDENT makes some eMule mods drop the
     * connection.
     */
    public static boolean mayKeepalive(ClientState client) {
        if (client == null) return false;
        return (client.serverCaps() & (ClientState.SRVCAP_NATTRAVERSAL | ClientState.SRVCAP_HOLEPUNCH_EXTENSION)) != 0
                || client.emuleUdpPorts() != 0;
    }

    /**
     * Draws the session token of this login (ST_NATT_TOKEN), new at every login, for a client that
     * {@link #mayKeepalive may send 0x9F} while natCallback and natCallbackToken are on; null otherwise. Only a client
     * {@link #register registered} for the callback, or with natHolePunch for the hole punch (a LowID with a 0xF9 UDP
     * port), can then prove it: the endpoint of any other has no use here.
     */
    public byte[] issueToken(ClientState client) {
        if (!enabled.getAsBoolean() || !tokenEnabled.getAsBoolean() || !mayKeepalive(client)) return null;
        return tokens.issue(client.connectionId());
    }

    /**
     * The token {@link #issueToken} gave this session, for an OP_SERVERIDENT sent again during it (the IPv6 verdict,
     * doc/ipv6.md): the client keeps signing its 0x9F with it. Null as for {@link #issueToken}.
     */
    public byte[] sessionToken(ClientState client) {
        if (!enabled.getAsBoolean() || !tokenEnabled.getAsBoolean() || !mayKeepalive(client)) return null;
        return tokens.current(client.connectionId());
    }

    /** Forgets a client that disconnected, and its session token; nothing else if it never opted in. */
    public void unregister(ClientState client) {
        if (client == null) return;
        tokens.forget(client.connectionId());
        sessions.computeIfPresent(client.clientId(), (id, s) -> s.client == client ? null : s);
        byHash.computeIfPresent(HashKey.of(client.userHash(), 0), (k, s) -> s.client == client ? null : s);
    }

    /** Whether {@code lowId} is a client of this server that opted in to the callback. */
    public boolean isOptedIn(long lowId) {
        Session session = sessions.get(lowId);
        return session != null && session.lugdunum;
    }

    /**
     * Whether {@code source}, a LowID of this server, is of use to {@code requester}, a LowID of this server asking for
     * sources (#126). A stock client that is LowID drops LowID sources ({@code CPartFile::CanAddSource()} while
     * firewalled) and cannot ask this server to call them back ({@code CemuleApp::CanDoCallback()}); only NAT
     * traversal reaches them:
     * <ul>
     *   <li>Lugdunum's callback (natCallback): both opted in with 0x1000; the requester asks with 0xA6;</li>
     *   <li>the hole punch (natHolePunch): the requester announced a UDP port (0xF9) or the extension (0x100000), so it
     *   asks with 0x60, and the source has a session, so it is a target: it reads 0x61, or it is Lugdunum-only and is
     *   bridged with 0x37.</li>
     * </ul>
     */
    public boolean lowIdSourceUsable(ClientState requester, ClientState source) {
        if (requester == null || source == null || !enabled.getAsBoolean()) return false;
        Session target = sessionOf(source);
        if (target == null) return false;
        Session asking = sessionOf(requester);
        if (asking != null && asking.lugdunum && target.lugdunum) return true;
        return holePunch.isEnabled() && asksHolePunch(requester);
    }

    /** Whether {@code client} sends 0x60: it announced a client UDP port (0xF9) or the hole-punch extension. */
    static boolean asksHolePunch(ClientState client) {
        return clientUdpPort(client) != 0 || (client.serverCaps() & ClientState.SRVCAP_HOLEPUNCH_EXTENSION) != 0;
    }

    /**
     * Whether {@code source}, a LowID of this server, is of use to a client of another server (OP_GLOBGETSOURCES):
     * only through the cross-server callback (natCallbackCrossServer), to a source that opted in with 0x1000 (#126).
     * A client of another server cannot have this server call a LowID back otherwise.
     */
    public boolean foreignSourceUsable(ClientState source) {
        if (source == null || !enabled.getAsBoolean() || !crossServerEnabled.getAsBoolean()) return false;
        Session target = sessionOf(source);
        return target != null && target.lugdunum;
    }

    /** Clients registered for the callback or, with natHolePunch, for the hole punch only. */
    public int sessionCount() {
        return sessions.size();
    }

    /** The UDP endpoint of {@code lowId}'s last accepted 0x9F, if it is younger than {@link #KEEPALIVE_TTL_MS}. */
    public Optional<UdpEndpoint> endpointOf(long lowId) {
        Session session = sessions.get(lowId);
        return Optional.ofNullable(session == null ? null : freshKeepalive(session));
    }

    /** The UDP endpoint of {@code session}'s last accepted 0x9F, if younger than {@link #KEEPALIVE_TTL_MS}; else null. */
    UdpEndpoint freshKeepalive(Session session) {
        Seen seen = session.keepalive;
        if (seen == null || clock.getAsLong() - seen.at() > KEEPALIVE_TTL_MS) return null;
        return seen.endpoint();
    }

    /**
     * Records an accepted keepalive of {@code lowId} from {@code ip:port}, without any check: {@link #handleKeepalive}
     * decides what is believed. Nothing for a client that is not registered. A session that announced no UDP port
     * takes this one (ed2k-server), which makes it a hole-punch target.
     */
    public void onKeepalive(long lowId, InetAddress ip, int port) {
        Session session = sessions.get(lowId);
        if (session == null || port < 1 || port > 0xFFFF) return;
        UdpEndpoint endpoint = new UdpEndpoint(ip, port);
        session.keepalive = new Seen(endpoint, clock.getAsLong(), null);
        if (session.udpPort == 0) session.udpPort = port;
        // The cross-server requesters waiting for this client's next 0x9F get its endpoint now
        crossServer.onTargetKeepalive(session, endpoint);
    }

    /**
     * UDP 0x9F (OP_NATT_KEEPALIVE) {@code <user hash 16>[<counter 4><mac 16>]} from {@code ip:port}, payload after
     * the opcode. The
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
        // An extension coordination waiting for this client's fresh port goes ahead
        holePunch.keepaliveAccepted(session);
        return true;
    }

    /**
     * Whether a 0x9F of {@code session} is believed (ed2k-server 0.9.88, issue 40 of andrey23127/ed2k-server; aMule
     * master a6d6290be0, docs/NAT_T_Protocol.md, "Split TCP and UDP exit"):
     * <ul>
     *   <li>the proof of the session token, {@code <user hash 16><counter 4 LE><mac 16>} ({@link KeepaliveToken}),
     *   from a session holding one while natCallbackToken is on: believed from any IPv4, plain or obfuscated, when the
     *   mac is right and the counter above the last accepted; a bad mac or a replayed counter is no keepalive at all,
     *   even from the login IP;</li>
     *   <li>anything else is the user hash alone (16 bytes; longer payloads, ed2k-server 0.9.87's
     *   {@code <user hash 16><token 16>} included, count as the hash alone: the token is never taken over UDP). A user
     *   hash is no secret, so it is believed only from the IPv4 the client logged in from (ed2k-server README,
     *   "Implementing a compatible client", step 2), and once an obfuscated one was believed, only obfuscated: only
     *   the server UDP key makes one.</li>
     * </ul>
     */
    boolean acceptKeepalive(Session session, byte[] payload, InetAddress ip, boolean obfuscated) {
        long source = ClientState.ipToLong(ip);
        boolean loginIp = source != 0 && source == ClientState.ipToLong(session.client.address());
        int connectionId = session.client.connectionId();
        if (payload.length == KeepaliveToken.PROOF_SIZE && tokenEnabled.getAsBoolean() && tokens.holds(connectionId)) {
            KeepaliveToken.Verdict verdict = tokens.verify(connectionId, payload);
            switch (verdict) {
                case ACCEPTED -> {
                    proofs.increment();
                    if (!loginIp) proofsOtherIp.increment();
                }
                case REPLAY -> proofsReplayed.increment();
                default -> proofsBad.increment();
            }
            if (verdict != KeepaliveToken.Verdict.ACCEPTED) {
                log.debug("NAT keepalive proof of LowID {} from {}: {}", session.lowId, ip.getHostAddress(), verdict);
            }
            // Only IPv4 endpoints are handed out
            return verdict == KeepaliveToken.Verdict.ACCEPTED && source != 0;
        }
        if (payload.length == TOKEN_FORM_SIZE) tokenFormsIgnored.increment();
        if (!loginIp) return false;
        if (obfuscated) {
            session.obfuscatedKeepalive = true;
            return true;
        }
        return !session.obfuscatedKeepalive;
    }

    /**
     * The 0x9F counters of ed2k-server 0.9.88: proofs believed, believed from another IP than the login's, bad ones,
     * replayed ones, and 0.9.87 token forms read as the user hash alone.
     */
    public Map<String, Long> keepaliveCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("proof", proofs.sum());
        counters.put("proof_other_ip", proofsOtherIp.sum());
        counters.put("proof_bad", proofsBad.sum());
        counters.put("proof_replay", proofsReplayed.sum());
        counters.put("token_form_ignored", tokenFormsIgnored.sum());
        return counters;
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
        if (requester == null || !requester.lugdunum) return Outcome.UNKNOWN_REQUESTER;
        long source = ClientState.ipToLong(ip);
        if (source == 0 || !fromRequester(requester, source)) return Outcome.WRONG_ADDRESS;
        long now = clock.getAsLong();
        UdpEndpoint from = new UdpEndpoint(ip, port);
        // Kept even when the rate is exceeded: it is where a UDP notice for this client goes
        requester.request = new Seen(from, now, socket);
        if (!requester.asRequester.tryTake(now)) return Outcome.REQUESTER_RATE_LIMITED;
        if (targetId == requesterId) return Outcome.SAME_CLIENT;

        Session target = sessions.get(targetId);
        if (target == null || !target.lugdunum) return Outcome.TARGET_NOT_CAPABLE;
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

    /** Whether the LowID hole punch is on (natCallback and natHolePunch). */
    public boolean isHolePunchEnabled() {
        return holePunch.isEnabled();
    }

    /**
     * TCP 0x60 (OP_LOWID_HOLEPUNCH_REQUEST) {@code <target ID 4><requester UDP port 2>} from {@code client}, payload
     * after the opcode: ed2k-server's LowID hole punch ({@link LowIdHolePunch}). Answered by 0x61 to both sides or
     * 0x62 to the requester, after a 0x37 wake and the target's 0x9F between two extension clients.
     */
    public void handleHolePunchRequest(ClientState client, byte[] payload) {
        LowIdHolePunch.Outcome outcome = holePunch.handleRequest(client, payload);
        if (log.isDebugEnabled() && client != null) {
            log.debug("LowID hole punch request from {}: {}", client.clientId(), outcome);
        }
    }

    /** The hole punch's counters ({@link LowIdHolePunch#counters}). */
    public Map<String, Long> holePunchCounters() {
        return holePunch.counters();
    }

    LowIdHolePunch holePunch() {
        return holePunch;
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
