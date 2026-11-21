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
import org.jemule.network.natt.NatCallbackService.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * ed2k-server's LowID hole punch between two LowID clients of this server, and its hole-punch extension, as described
 * in aMule master a6d6290be0, docs/NAT_T_Protocol.md, "ed2k-server LowID hole punch" and "Hole-punch extension
 * (ed2k-server 0.9.85)", in the ed2k-server README ("Implementing a compatible client", "Hole-punch extension") and as
 * ed2k-server runs it ({@code src/server/holepunch.rs}).
 * <ol>
 *   <li>Opt-in at login: {@code CT_EMULE_UDPPORTS} (0xF9) {@code (Kad UDP port << 16) | client UDP port}; a LowID that
 *   sent a client UDP port is {@link NatCallbackService#register registered}, so its 0x9F keepalives give its public
 *   UDP endpoint. The server announces the punch with OP_IDCHANGE TCP flag 0x20000.</li>
 *   <li>Request: TCP 0x60 {@code <target ID 4><requester UDP port 2>}.</li>
 *   <li>Answer to both sides: TCP 0x61 ({@link #info}), each describing the other; or TCP 0x62
 *   {@code <target ID 4><reason 1>} to the requester ({@link #FAIL_TARGET_NOT_CONNECTED} to {@link #FAIL_NO_IPV4}).
 *   The answer is sent again at {@link #RESEND_MS} (+1.2 s and +3 s), checked again each time, so that one 0x61 lost
 *   or late on a TCP link does not leave the two punching at different times.</li>
 *   <li>Extension, between two clients that set 0x100000 with 0x1000 in CT_SERVER_FLAGS (OP_IDCHANGE TCP flag
 *   0x100000): the target is first woken with Lugdunum's TCP 0x37 carrying the requester's endpoint; it answers with
 *   0x9F, and on its first accepted 0x9F, or after {@link #EXTENSION_WAIT_MS} without one, both get the 0x61, then the
 *   resends. A 0x60 for a pair already waiting adds nothing.</li>
 * </ol>
 * The UDP endpoint given for a client is the source of its last accepted 0x9F when that is under
 * {@link NatCallbackService#KEEPALIVE_TTL_MS} old, else its login IPv4 with the port it announced (0xF9, or the port of
 * its own last 0x60). Only IPv4 endpoints are given. Requests share the callback's limits: a requester gets a burst of
 * {@link NatCallbackService#REQUESTER_BURST} then one a second, a target {@link NatCallbackService#TARGET_BURST} then
 * one a second; over them the request is dropped without answer, as ed2k-server drops a request over its session's
 * allowance.
 */
final class LowIdHolePunch {
    private static final Logger log = LoggerFactory.getLogger(LowIdHolePunch.class);

    /** 0x62 reasons, as ed2k-server's FAIL_* constants: the target ID is not logged in here. */
    static final int FAIL_TARGET_NOT_CONNECTED = 1;
    /** The target is a HighID: the requester can connect to it, or use the ordinary callback. */
    static final int FAIL_TARGET_IS_HIGHID = 2;
    /**
     * A request the server cannot serve: the requester names itself. JEmuleServer also sends it to a HighID requester
     * and to a requester whose UDP port it does not know, where ed2k-server would describe it with port 0.
     */
    static final int FAIL_BAD_REQUEST = 3;
    /** The target has no known UDP port: it sent no 0xF9 port, no 0x60 and no 0x9F (a stock client). */
    static final int FAIL_TARGET_NO_UDP = 4;
    /** One of the two sessions has no IPv4 endpoint to give (a login over IPv6 without a proven 0x9F). */
    static final int FAIL_NO_IPV4 = 5;

    /**
     * {@code role} in 0x61, the reader's own: 0 when it starts the punch, being the side with the lower server ID; 1
     * for the other. Advisory (ed2k-server README): both sides should punch, and aMule decides who dials by the peer ID.
     */
    static final int ROLE_FIRST = 0;
    static final int ROLE_SECOND = 1;

    /** How long an extension coordination waits for the target's 0x9F (ed2k-server's EXT_WAIT). */
    static final long EXTENSION_WAIT_MS = 3_000;
    /** When the 0x61 is sent again after the first one (ed2k-server's retry_loop). */
    static final long[] RESEND_MS = {1_200, 3_000};
    /** Coordinations waiting or resending at once (ed2k-server's max_retry_tasks); beyond, no resend and no wait. */
    static final int MAX_PENDING = 1024;

    /** 0x61 up to the peer's crypt options. */
    static final int INFO_LENGTH = 30;
    /** 0x61 with the recipient's own UDP endpoint. */
    static final int INFO_OWN_LENGTH = 36;

    /** What became of a 0x60. */
    enum Outcome {
        /** 0x61 sent to both sides. */
        INFO_SENT,
        /** 0x62 sent to the requester. */
        FAIL_SENT,
        /** Extension pair: the target was woken with 0x37, the 0x61 follows its 0x9F. */
        TARGET_WOKEN,
        /** Extension pair already waiting: nothing sent. */
        ALREADY_WAITING,
        DISABLED, SHORT, REQUESTER_RATE_LIMITED, TARGET_RATE_LIMITED
    }

    /** The UDP endpoint given for a client: IPv4 as an ed2k ID (0 when it has none) and port (0 when unknown). */
    record Endpoint(long ip, int port) {
    }

    /** One coordination of a (requester, target) pair: waiting for the target's 0x9F, or resending. */
    private static final class Pending {
        final long key;
        final Session requester;
        final long targetId;
        /** True while an extension coordination waits for the target's 0x9F. */
        final AtomicBoolean waiting;

        Pending(long key, Session requester, long targetId, boolean waiting) {
            this.key = key;
            this.requester = requester;
            this.targetId = targetId;
            this.waiting = new AtomicBoolean(waiting);
        }
    }

    private final NatCallbackService service;
    private final BooleanSupplier enabled;
    private final LongFunction<ClientState> clients;
    private final LongSupplier clock;
    private final NatCallbackService.Transport transport;
    private final NatCallbackService.Scheduler scheduler;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();

    // Counters, after ed2k-server's holepunch_* ones
    private final LongAdder requests = new LongAdder();
    private final LongAdder infos = new LongAdder();
    private final LongAdder[] failures = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(),
            new LongAdder()};
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder extensionStarted = new LongAdder();
    private final LongAdder extensionFresh = new LongAdder();
    private final LongAdder extensionTimeout = new LongAdder();

    /**
     * @param service   the sessions and their 0x9F endpoints
     * @param enabled   whether the hole punch is on (natCallback and natHolePunch), read at each request
     * @param clients   the client logged in under a server ID, or null
     * @param clock     current time in milliseconds
     * @param transport where 0x61, 0x62 and 0x37 go
     * @param scheduler runs the extension's wait and the resends
     */
    LowIdHolePunch(NatCallbackService service, BooleanSupplier enabled, LongFunction<ClientState> clients,
                   LongSupplier clock, NatCallbackService.Transport transport, NatCallbackService.Scheduler scheduler) {
        this.service = service;
        this.enabled = enabled;
        this.clients = clients;
        this.clock = clock;
        this.transport = transport;
        this.scheduler = scheduler;
    }

    boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /** TCP 0x60 {@code <target ID 4><requester UDP port 2>} from {@code client}, payload after the opcode. */
    Outcome handleRequest(ClientState client, byte[] payload) {
        if (!enabled.getAsBoolean()) return Outcome.DISABLED;
        if (client == null || payload == null || payload.length < 6) return Outcome.SHORT;
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long targetId = Integer.toUnsignedLong(b.getInt());
        int udpPort = Short.toUnsignedInt(b.getShort());
        requests.increment();
        if (client.isHighId() || client.clientId() == 0) {
            // A HighID needs no punch to be reached; aMule sends 0x60 only while it is LowID
            fail(client, targetId, FAIL_BAD_REQUEST);
            return Outcome.FAIL_SENT;
        }
        Session requester = service.sessionOf(client);
        if (requester == null) {
            // A LowID that announced no UDP port at login: its 0x60 brings one (ed2k-server keeps it as well)
            service.register(client, false);
            requester = service.sessionOf(client);
            if (requester == null) return Outcome.SHORT;
        }
        long now = clock.getAsLong();
        if (!requester.asRequester.tryTake(now)) {
            rateLimited.increment();
            return Outcome.REQUESTER_RATE_LIMITED;
        }
        // The announced port, last in the order of endpoint(): a fresh 0x9F still wins over it
        if (udpPort != 0) requester.udpPort = udpPort;

        Sides sides = sides(requester, targetId);
        if (sides.failure() == 0) {
            if (!sides.target().asTarget.tryTake(now)) {
                rateLimited.increment();
                return Outcome.TARGET_RATE_LIMITED;
            }
            if (requester.extension && sides.target().extension) {
                Outcome woken = wake(requester, sides);
                if (woken != null) return woken;
            }
        }
        boolean sent = answer(requester, targetId) == 0;
        if (sent) {
            long key = key(requester.lowId, targetId);
            Pending p = new Pending(key, requester, targetId, false);
            // At most one series of resends per pair: a repeated 0x60 gets its 0x61 at once, and nothing more
            if (pending.size() < MAX_PENDING && pending.putIfAbsent(key, p) == null) scheduleResends(p);
        }
        return sent ? Outcome.INFO_SENT : Outcome.FAIL_SENT;
    }

    /**
     * Extension: wakes the target with Lugdunum's 0x37 carrying the requester's endpoint, and waits for its 0x9F.
     *
     * @return the outcome, or null to answer at once as for any pair (crypt options that rule out the 0x37, which rule
     * out nothing in 0x61; too many coordinations pending)
     */
    private Outcome wake(Session requester, Sides sides) {
        long key = key(requester.lowId, sides.targetId());
        if (pending.containsKey(key)) return Outcome.ALREADY_WAITING;
        ClientState r = requester.client;
        byte[] body = NatCallbackService.notice(sides.requester().ip(), sides.requester().port(), requester.lowId,
                r.cryptOptions(), r.userHash(), sides.target().client.cryptOptions());
        if (body == null || pending.size() >= MAX_PENDING) return null;
        Pending p = new Pending(key, requester, sides.targetId(), true);
        if (pending.putIfAbsent(key, p) != null) return Outcome.ALREADY_WAITING;
        extensionStarted.increment();
        // Registered before the wake is sent, so that the target's 0x9F cannot slip past
        transport.sendTcp(sides.target().client,
                new Packet(Packet.PROTOCOL_ED2K, NatOpcodes.OP_NAT_CALLBACKREQUESTED, body));
        scheduler.schedule(() -> release(p, false), EXTENSION_WAIT_MS);
        return Outcome.TARGET_WOKEN;
    }

    /** A 0x9F of {@code session} was accepted: the extension coordinations waiting for it go ahead. */
    void keepaliveAccepted(Session session) {
        if (pending.isEmpty()) return;
        for (Pending p : pending.values()) {
            if (p.targetId == session.lowId && p.waiting.get()) release(p, true);
        }
    }

    /** The end of an extension wait: the target's 0x9F ({@code fresh}) or the timeout, whichever comes first. */
    private void release(Pending p, boolean fresh) {
        if (!p.waiting.compareAndSet(true, false)) return;
        (fresh ? extensionFresh : extensionTimeout).increment();
        if (!service.isRegistered(p.requester) || answer(p.requester, p.targetId) != 0) {
            pending.remove(p.key, p);
            return;
        }
        scheduleResends(p);
    }

    private void scheduleResends(Pending p) {
        for (int i = 0; i < RESEND_MS.length; i++) {
            boolean last = i == RESEND_MS.length - 1;
            scheduler.schedule(() -> resend(p, last), RESEND_MS[i]);
        }
    }

    /** Sends the answer again, checked again: a target gone since gets the requester a 0x62. */
    private void resend(Pending p, boolean last) {
        try {
            if (service.isRegistered(p.requester)) answer(p.requester, p.targetId);
        } finally {
            if (last) pending.remove(p.key, p);
        }
    }

    /** The two sides of a punch, or the 0x62 reason why there is none. */
    private record Sides(int failure, long targetId, Session target, Endpoint requester, Endpoint targetEndpoint) {
        static Sides failed(int reason, long targetId) {
            return new Sides(reason, targetId, null, null, null);
        }
    }

    /**
     * Checks a pair in ed2k-server's order: the requester names itself (3), the target is not logged in (1), is a
     * HighID (2), one of the two has no IPv4 endpoint (5), the target registered no UDP port (4, as a client that did
     * not send 0xF9 is not registered).
     */
    private Sides sides(Session requester, long targetId) {
        if (targetId == requester.lowId) return Sides.failed(FAIL_BAD_REQUEST, targetId);
        ClientState target = clients.apply(targetId);
        if (target == null) return Sides.failed(FAIL_TARGET_NOT_CONNECTED, targetId);
        if (target.isHighId()) return Sides.failed(FAIL_TARGET_IS_HIGHID, targetId);
        Session targetSession = service.sessionOf(target);
        Endpoint r = endpoint(requester);
        Endpoint t = targetSession != null ? endpoint(targetSession) : new Endpoint(ClientState.ipToLong(target.address()), 0);
        if (r.ip() == 0 || t.ip() == 0) return Sides.failed(FAIL_NO_IPV4, targetId);
        if (targetSession == null) return Sides.failed(FAIL_TARGET_NO_UDP, targetId);
        return new Sides(0, targetId, targetSession, r, t);
    }

    /**
     * Sends the 0x61 to both sides, or the 0x62 to the requester.
     *
     * @return 0 when the 0x61 went out, else the 0x62 reason
     */
    private int answer(Session requester, long targetId) {
        Sides sides = sides(requester, targetId);
        int failure = sides.failure();
        if (failure == 0 && sides.targetEndpoint().port() == 0) failure = FAIL_TARGET_NO_UDP;
        if (failure == 0 && sides.requester().port() == 0) failure = FAIL_BAD_REQUEST;
        if (failure != 0) {
            fail(requester.client, targetId, failure);
            return failure;
        }
        Session target = sides.target();
        // The lower server ID starts: arbitrary, but the two sides agree on it without a word (ed2k-server)
        boolean requesterFirst = requester.lowId < targetId;
        byte[] toTarget = info(sides.requester(), requester.client, requesterFirst ? ROLE_SECOND : ROLE_FIRST,
                requester.lowId, service.freshKeepalive(target));
        byte[] toRequester = info(sides.targetEndpoint(), target.client, requesterFirst ? ROLE_FIRST : ROLE_SECOND,
                targetId, service.freshKeepalive(requester));
        transport.sendTcp(target.client, new Packet(Packet.PROTOCOL_ED2K, NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, toTarget));
        transport.sendTcp(requester.client,
                new Packet(Packet.PROTOCOL_ED2K, NatOpcodes.OP_LOWID_HOLEPUNCH_INFO, toRequester));
        infos.increment();
        if (log.isDebugEnabled()) {
            log.debug("LowID hole punch between {} and {}: 0x61 sent to both", requester.lowId, targetId);
        }
        return 0;
    }

    private void fail(ClientState requester, long targetId, int reason) {
        failures[reason - 1].increment();
        byte[] body = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt((int) targetId).put((byte) reason)
                .array();
        transport.sendTcp(requester, new Packet(Packet.PROTOCOL_ED2K, NatOpcodes.OP_LOWID_HOLEPUNCH_FAIL, body));
        if (log.isDebugEnabled()) {
            log.debug("LowID hole punch from {} to {}: 0x62 reason {}", requester.clientId(), targetId, reason);
        }
    }

    /**
     * Where {@code session}'s UDP is: the source of its last accepted 0x9F if fresh (ed2k-server's per-client
     * observed port, issue 37; the address a proof came from, issue 40), else its login IPv4 and announced port.
     */
    private Endpoint endpoint(Session session) {
        UdpEndpoint keepalive = service.freshKeepalive(session);
        if (keepalive != null) return new Endpoint(ClientState.ipToLong(keepalive.address()), keepalive.port());
        return new Endpoint(ClientState.ipToLong(session.client.address()), session.udpPort);
    }

    /**
     * OP_LOWID_HOLEPUNCH_INFO describing {@code peer} at {@code endpoint} to its reader, whose own {@code role} it
     * carries:
     * {@code <peer IP 4><peer TCP port 2><peer UDP port 2><peer user hash 16><role 1><peer ID 4><peer crypt 1>}, 30
     * bytes, then {@code <own IP 4><own UDP port 2>}, 36 bytes, when the reader's {@code own} 0x9F endpoint is known.
     * The IPs in network byte order (as an ed2k ID), the ports and the ID little-endian; {@code peer crypt} is the
     * peer's {@code (CT_SERVER_FLAGS >> 9) & 7}. As ed2k-server's build_info; aMule reads the peer ID from 29 bytes,
     * the crypt options from 30 and its own endpoint from 36.
     */
    static byte[] info(Endpoint endpoint, ClientState peer, int role, long peerId, UdpEndpoint own) {
        long ownIp = own == null ? 0 : ClientState.ipToLong(own.address());
        ByteBuffer b = ByteBuffer.allocate(ownIp != 0 ? INFO_OWN_LENGTH : INFO_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) endpoint.ip());
        b.putShort((short) peer.listenPort());
        b.putShort((short) endpoint.port());
        b.put(peer.userHash());
        b.put((byte) role);
        b.putInt((int) peerId);
        b.put((byte) peer.cryptOptions());
        if (ownIp != 0) {
            b.putInt((int) ownIp);
            b.putShort((short) own.port());
        }
        return b.array();
    }

    private static long key(long requesterId, long targetId) {
        return (requesterId << 32) | targetId;
    }

    /** Coordinations waiting for a 0x9F or resending. */
    int pendingCount() {
        return pending.size();
    }

    /**
     * Counters after ed2k-server's holepunch_*: 0x60 received, 0x61 pairs sent (resends included), 0x62 by reason,
     * requests over a limit, and the extension's wakes, released by a fresh 0x9F or after the wait.
     */
    Map<String, Long> counters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("requests", requests.sum());
        counters.put("info", infos.sum());
        counters.put("fail_not_connected", failures[FAIL_TARGET_NOT_CONNECTED - 1].sum());
        counters.put("fail_highid", failures[FAIL_TARGET_IS_HIGHID - 1].sum());
        counters.put("fail_bad_request", failures[FAIL_BAD_REQUEST - 1].sum());
        counters.put("fail_no_udp", failures[FAIL_TARGET_NO_UDP - 1].sum());
        counters.put("fail_no_ipv4", failures[FAIL_NO_IPV4 - 1].sum());
        counters.put("rate_limited", rateLimited.sum());
        counters.put("ext_started", extensionStarted.sum());
        counters.put("ext_fresh", extensionFresh.sum());
        counters.put("ext_timeout", extensionTimeout.sum());
        return counters;
    }
}
