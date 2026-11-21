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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Cross-server NAT callback (ed2k-server 0.9.86, andrey23127/ed2k-server#40; aMule master a6d6290be0,
 * docs/NAT_T_Protocol.md "Cross-server NAT callback"; eNode-go ed2k/natcallback.go; doc/nat-callback-cross-server.md).
 * A client logged in to another server, which learnt a LowID source and its server by source exchange, asks this
 * server to wake that LowID:
 * <ol>
 *   <li>requester to this server, UDP, server-key obfuscated: OP_NAT_CALLBACKREQUEST (0xA6)
 *       {@code <target ID 4><0 4>[<crypt 1>]}. The key is derived from the sender's IP, so a request that decodes
 *       proves its address; its source port is the requester's endpoint;</li>
 *   <li>this server to the target, TCP: OP_NAT_CALLBACKREQUESTED (0x37)
 *       {@code <requester IP 4><requester UDP port 2><0 4><crypt 1>}, no hash;</li>
 *   <li>this server to the requester, UDP, obfuscated with its key, from the port the request came to:
 *       OP_NAT_CALLBACKREQUESTED_UDP (0xA7) {@code <target IP 4><target UDP port 2><target ID 4><crypt 1><hash 16>},
 *       on the target's next OP_NATT_KEEPALIVE (0x9F), or after {@link #KEEPALIVE_WAIT_MS} with its last one; then
 *       again at +1.2 s and +3 s.</li>
 * </ol>
 * {@link NatCallbackService} checks the obfuscation and hands requester ID 0 here
 * ({@link NatCallbackService#handleForeignRequest}); the target's 0x9F reaches {@link #onTargetKeepalive} through
 * {@link NatCallbackService#onKeepalive}. Only the requesters of a target that announced ed2k-server's hole-punch
 * extension (SRVCAP_HOLEPUNCH_EXTENSION in CT_SERVER_FLAGS) get an answer: such a target answers a wake with its 0x9F burst, punches and waits to be dialled.
 * A Lugdunum-only target gets the 0x37 alone and dials the requester itself. Every refusal is silent, so the path
 * tells nobody who is online. The limits are ed2k-server's, which aMule's requester stays under
 * (a burst of 5, then one every 2 s, 30 targets in 10 minutes per server).
 */
public final class CrossServerCallback {

    private static final Logger log = LoggerFactory.getLogger(CrossServerCallback.class);

    /** Requests per source IP: a burst of 10, then one a second (ed2k-server). */
    static final int ADDRESS_BURST = 10;
    static final long ADDRESS_REFILL_MS = 1_000;
    /** Distinct target IDs per source IP in {@link #TARGET_WINDOW_MS}, known or not: stops a sweep over LowIDs. */
    static final int TARGETS_PER_ADDRESS = 60;
    static final long TARGET_WINDOW_MS = 600_000;
    /**
     * Wakes (0x37) per target in {@link #WAKE_WINDOW_MS}: nobody can flood a client with notices. A wake also takes a
     * token from the target's same-server notice rate.
     */
    static final int WAKES_PER_TARGET = 6;
    static final long WAKE_WINDOW_MS = 60_000;
    /** Wakes for the whole server: a burst of 100, then 20 a second, whatever the number of source IPs. */
    static final int GLOBAL_BURST = 100;
    static final long GLOBAL_REFILL_MS = 50;
    /** How long a request waits for the target's next 0x9F (ed2k-server, eNode-go: 3 s). */
    static final long KEEPALIVE_WAIT_MS = 3_000;
    /** Copies of an answer after the first, as ed2k-server and eNode-go: the requester ignores the later ones. */
    static final long[] RESEND_OFFSETS_MS = {1_200, 3_000};
    /** Requesters waiting for one target; the oldest is dropped (eNode-go natForeignMaxPerTarget). */
    static final int MAX_WAITING_PER_TARGET = 4;
    /** Requests waiting or being answered, for the whole server. */
    static final int MAX_IN_FLIGHT = 1_024;
    /** Source IPs and targets whose limits are tracked; a new one beyond is refused until older ones expire. */
    static final int MAX_TRACKED = 8_192;
    private static final long PRUNE_EVERY_MS = 10_000;

    /** Crypt options, CT_SERVER_FLAGS bits 9-11 shifted down: crypt layer supported, requested, required. */
    static final int CRYPT_SUPPORT = 0x01;
    static final int CRYPT_REQUEST = 0x02;
    static final int CRYPT_REQUIRE = 0x04;
    /** Set in the answer's crypt byte (Lugdunum's meaning): both sides support the crypt layer and one asks for it. */
    static final int CRYPT_USE_LAYER = 0x80;

    /** What became of a request: one counter each. */
    public enum Outcome {
        /** Woken; its requester waits for the target's endpoint. */
        WOKEN,
        /** Woken; a Lugdunum-only target, whose requester gets no answer (it dials the requester itself). */
        WOKEN_WITHOUT_ANSWER,
        /** From an IPv6 address, which neither 0x37 nor 0xA7 can carry. */
        NOT_IPV4,
        /** Refused by the IP filter. */
        BLOCKED,
        /** Over the source IP's request rate. */
        RATE_LIMITED,
        /** Over the source IP's distinct targets. */
        TOO_MANY_TARGETS,
        /** No such LowID here, or it did not opt in to the NAT callback. */
        UNKNOWN_TARGET,
        /** One side requires the crypt layer and the other cannot use it. */
        CRYPT_MISMATCH,
        /** The same requester endpoint already waits for this target, or is being answered. */
        IN_FLIGHT,
        /** Over the target's wakes. */
        TARGET_RATE_LIMITED,
        /** Over the server's wakes or requests in flight. */
        BUSY;

        /** Whether the target was woken. */
        public boolean woken() {
            return this == WOKEN || this == WOKEN_WITHOUT_ANSWER;
        }
    }

    /** One request in flight, per requester endpoint and target. */
    private static final class Pending {
        final UdpEndpoint requester;
        final long targetId;
        final NatCallbackService.Session target;
        final int requesterCrypt;
        /** The server socket the request reached: the answer leaves from it. */
        final DatagramSocket socket;
        final long waitUntil;
        /** The 0xA7 datagram once answered, null while waiting for the target's 0x9F. */
        byte[] answer;
        final ArrayDeque<Long> resends = new ArrayDeque<>();

        Pending(UdpEndpoint requester, NatCallbackService.Session target, int requesterCrypt, DatagramSocket socket,
                long waitUntil) {
            this.requester = requester;
            this.targetId = target.lowId;
            this.target = target;
            this.requesterCrypt = requesterCrypt;
            this.socket = socket;
            this.waitUntil = waitUntil;
        }
    }

    private record Key(UdpEndpoint requester, long targetId) {
    }

    /** A token bucket. */
    private static final class Bucket {
        final int burst;
        final long refillMs;
        int tokens;
        long refilledAt;

        Bucket(int burst, long refillMs, long now) {
            this.burst = burst;
            this.refillMs = refillMs;
            this.tokens = burst;
            this.refilledAt = now;
        }

        boolean take(long now) {
            long earned = (now - refilledAt) / refillMs;
            if (earned > 0) {
                tokens = (int) Math.min(burst, tokens + earned);
                refilledAt += earned * refillMs;
            }
            if (tokens == 0) return false;
            tokens--;
            return true;
        }

        boolean full(long now) {
            return tokens + (now - refilledAt) / refillMs >= burst;
        }
    }

    private final NatCallbackService service;
    private final Predicate<InetAddress> blocked;
    private final LongSupplier clock;
    private final Map<Key, Pending> inFlight = new LinkedHashMap<>();
    private final Map<InetAddress, Bucket> addressRates = new HashMap<>();
    /** Per source IP: target ID to the last time it was named. */
    private final Map<InetAddress, LinkedHashMap<Long, Long>> addressTargets = new HashMap<>();
    /** Per target ID: the times of its last wakes. */
    private final Map<Long, ArrayDeque<Long>> targetWakes = new HashMap<>();
    private final Bucket globalRate;
    private long prunedAt;
    private final Map<Outcome, AtomicLong> outcomes = new EnumMap<>(Outcome.class);
    private final AtomicLong answers = new AtomicLong();

    CrossServerCallback(NatCallbackService service, Predicate<InetAddress> blocked, LongSupplier clock) {
        this.service = service;
        this.blocked = blocked;
        this.clock = clock;
        long now = clock.getAsLong();
        this.globalRate = new Bucket(GLOBAL_BURST, GLOBAL_REFILL_MS, now);
        this.prunedAt = now;
        for (Outcome o : Outcome.values()) outcomes.put(o, new AtomicLong());
    }

    /**
     * An OP_NAT_CALLBACKREQUEST with requester ID 0, already known to be server-key obfuscated (which proves its
     * source address).
     *
     * @param targetId the LowID to wake
     * @param crypt    the requester's crypt options, {@code (CT_SERVER_FLAGS >> 9) & 7}, 0 if it sent none
     * @param from     source address of the request: the requester's endpoint with {@code port}
     * @param socket   the server socket it reached, which the answer leaves from
     */
    public Outcome onRequest(long targetId, int crypt, InetAddress from, int port, DatagramSocket socket) {
        Outcome outcome = admit(targetId, crypt, from, port, socket);
        outcomes.get(outcome).incrementAndGet();
        if (outcome == Outcome.WOKEN || outcome == Outcome.WOKEN_WITHOUT_ANSWER) {
            log.debug("Cross-server NAT callback: {}:{} woke {} ({})", from.getHostAddress(), port, targetId, outcome);
        } else {
            log.debug("Cross-server NAT callback from {}:{} for {} refused: {}", from.getHostAddress(), port, targetId,
                    outcome);
        }
        return outcome;
    }

    private Outcome admit(long targetId, int crypt, InetAddress from, int port, DatagramSocket socket) {
        if (!(from instanceof Inet4Address) || port < 1 || port > 0xFFFF) return Outcome.NOT_IPV4;
        if (blocked.test(from)) return Outcome.BLOCKED;
        // Not refused when a client is logged in here from the same address (eNode-go does): two clients behind one
        // NAT on two servers is ordinary, and ed2k-server forwards such requests (ed2k-server#40, 0.9.87 numbers)
        UdpEndpoint requester = new UdpEndpoint(from, port);
        NatCallbackService.Session target;
        byte[] wake;
        boolean answered;
        synchronized (this) {
            long now = clock.getAsLong();
            pruneIfDue(now);
            Bucket rate = addressRates.get(from);
            if (rate == null) {
                if (addressRates.size() >= MAX_TRACKED) return Outcome.RATE_LIMITED;
                rate = new Bucket(ADDRESS_BURST, ADDRESS_REFILL_MS, now);
                addressRates.put(from, rate);
            }
            if (!rate.take(now)) return Outcome.RATE_LIMITED;
            if (!nameTarget(from, targetId, now)) return Outcome.TOO_MANY_TARGETS;

            // A logged-in LowID of this server that opted in (0x1000): only those have a session
            target = targetId >= 1 && targetId <= 0x00FFFFFFL ? service.session(targetId) : null;
            if (target == null) return Outcome.UNKNOWN_TARGET;
            int targetCrypt = target.client.cryptOptions();
            // A target with the extension gets its requester's dial, obfuscated with the hash of the answer; a
            // Lugdunum-only one dials the requester itself, without the requester's hash (ed2k-server README,
            // eNode-go): its requester gets nothing
            answered = (target.client.serverCaps() & ClientState.SRVCAP_HOLEPUNCH_EXTENSION) != 0;
            if (!cryptCompatible(answered, crypt, targetCrypt)) return Outcome.CRYPT_MISMATCH;
            long requesterIp = ClientState.ipToLong(from);
            // The wake carries the requester's crypt options as it sent them, requester ID 0 and no hash: for a
            // Lugdunum-only target exactly the same-server notice without a hash (NatCallbackService#notice)
            wake = answered ? wake(requesterIp, port, crypt)
                    : NatCallbackService.notice(requesterIp, port, 0, crypt, null, targetCrypt);
            if (wake == null) return Outcome.CRYPT_MISMATCH;
            Key key = new Key(requester, targetId);
            if (inFlight.containsKey(key)) return Outcome.IN_FLIGHT;
            if (!allowWake(targetId, now)) return Outcome.TARGET_RATE_LIMITED;
            if (answered && inFlight.size() >= MAX_IN_FLIGHT) return Outcome.BUSY;
            if (!globalRate.take(now)) return Outcome.BUSY;
            if (!target.asTarget.tryTake(now)) return Outcome.TARGET_RATE_LIMITED;
            if (answered) {
                dropOldestWaiting(targetId);
                inFlight.put(key, new Pending(requester, target, crypt, socket, now + KEEPALIVE_WAIT_MS));
            }
        }
        deliverWake(target, wake);
        return answered ? Outcome.WOKEN : Outcome.WOKEN_WITHOUT_ANSWER;
    }

    /**
     * The target's 0x9F was accepted: its requesters waiting get its endpoint now.
     */
    void onTargetKeepalive(NatCallbackService.Session session, UdpEndpoint endpoint) {
        List<Pending> due = new ArrayList<>();
        synchronized (this) {
            if (inFlight.isEmpty()) return;
            long now = clock.getAsLong();
            for (Pending p : inFlight.values()) {
                if (p.answer == null && p.target == session) {
                    answer(p, endpoint, now);
                    due.add(p);
                }
            }
        }
        send(due, "keepalive");
    }

    /** Sends the answers of the requests whose wait ended, and the resends due. */
    void tick(long now) {
        List<Pending> due = new ArrayList<>();
        synchronized (this) {
            pruneIfDue(now);
            for (Iterator<Pending> it = inFlight.values().iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (p.answer == null) {
                    if (now < p.waitUntil) continue;
                    // No fresh 0x9F: the last one of the target's session, if any (ed2k-server, eNode-go fallback)
                    Optional<UdpEndpoint> last = service.endpointOf(p.target);
                    if (last.isEmpty()) {
                        it.remove();
                        log.debug("Cross-server NAT callback: no UDP endpoint of {} for {}", p.targetId, p.requester);
                        continue;
                    }
                    answer(p, last.get(), now);
                    due.add(p);
                } else if (!p.resends.isEmpty() && now >= p.resends.peekFirst()) {
                    p.resends.pollFirst();
                    due.add(p);
                    if (p.resends.isEmpty()) it.remove();
                }
            }
        }
        send(due, "timer");
    }

    /** Builds the 0xA7 for {@code p}; the caller sends it. */
    private void answer(Pending p, UdpEndpoint endpoint, long now) {
        ByteBuffer a = ByteBuffer.allocate(2 + 27).order(ByteOrder.LITTLE_ENDIAN);
        a.put(Packet.PROTOCOL_ED2K);
        a.put(NatOpcodes.OP_NAT_CALLBACKREQUESTED_UDP);
        a.putInt((int) ClientState.ipToLong(endpoint.address()));
        a.putShort((short) endpoint.port());
        a.putInt((int) p.targetId);
        a.put((byte) answerCrypt(p.target.client.cryptOptions(), p.requesterCrypt));
        a.put(p.target.client.userHash(), 0, 16);
        p.answer = a.array();
        for (long offset : RESEND_OFFSETS_MS) p.resends.add(now + offset);
        answers.incrementAndGet();
        log.debug("Cross-server NAT callback: endpoint {} of {} to {}", endpoint, p.targetId, p.requester);
    }

    private void send(List<Pending> due, String why) {
        for (Pending p : due) {
            try {
                // Obfuscated with the requester's server UDP key, from the socket the request reached
                service.transport().sendUdp(p.socket, p.requester, p.answer);
            } catch (RuntimeException e) {
                log.debug("Cross-server NAT callback answer to {} ({}) not sent: {}", p.requester, why, e.getMessage());
            }
        }
    }

    /**
     * The wake goes by UDP 0xA7 to the endpoint of the target's own 0xA6 when that one is less than
     * {@link NatCallbackService#UDP_FRESH_MS} old (aMule reads a requester-ID-0 0xA7 as a wake then; ed2k-server and
     * eNode-go do the same), by TCP 0x37 otherwise. aMule reads both from 10 bytes on
     * (LugdunumNatTraversal::ParseForwardedCallback).
     */
    private void deliverWake(NatCallbackService.Session target, byte[] wake) {
        NatCallbackService.Seen recent = target.request;
        long targetIp = ClientState.ipToLong(target.client.address());
        if (recent != null && recent.socket() != null && clock.getAsLong() - recent.at() < NatCallbackService.UDP_FRESH_MS
                && targetIp != 0 && ClientState.ipToLong(recent.endpoint().address()) == targetIp) {
            byte[] datagram = new byte[2 + wake.length];
            datagram[0] = Packet.PROTOCOL_ED2K;
            datagram[1] = NatOpcodes.OP_NAT_CALLBACKREQUESTED_UDP;
            System.arraycopy(wake, 0, datagram, 2, wake.length);
            service.transport().sendUdp(recent.socket(), recent.endpoint(), datagram);
            return;
        }
        service.transport().sendTcp(target.lowId,
                new Packet(Packet.PROTOCOL_ED2K, NatOpcodes.OP_NAT_CALLBACKREQUESTED, wake));
    }

    /** The wake to an extension target: {@code <requester IP 4><requester UDP port 2><0 4><crypt 1>}. */
    static byte[] wake(long requesterIp, int port, int crypt) {
        return ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) requesterIp).putShort((short) port).putInt(0).put((byte) (crypt & 0x07)).array();
    }

    /**
     * The crypt byte of the answer: the target's options, with 0x80 when both sides support the crypt layer and one
     * asks for it (Lugdunum's meaning, ed2k-server 0.9.86).
     */
    static int answerCrypt(int targetCrypt, int requesterCrypt) {
        boolean both = (targetCrypt & CRYPT_SUPPORT) != 0 && (requesterCrypt & CRYPT_SUPPORT) != 0;
        boolean asked = ((targetCrypt | requesterCrypt) & CRYPT_REQUEST) != 0;
        return (targetCrypt & 0x07) | (both && asked ? CRYPT_USE_LAYER : 0);
    }

    /**
     * ed2k-server 0.9.87 and eNode-go: when the requester gets an answer, it dials with the target's hash, so a side
     * that requires the crypt layer only needs the other to support it. Otherwise the target dials without the
     * requester's hash, so neither side may require it.
     */
    static boolean cryptCompatible(boolean answered, int requesterCrypt, int targetCrypt) {
        if (!answered) return ((requesterCrypt | targetCrypt) & CRYPT_REQUIRE) == 0;
        boolean requesterBlocks = (requesterCrypt & CRYPT_REQUIRE) != 0
                && (targetCrypt & CRYPT_SUPPORT) == 0;
        boolean targetBlocks = (targetCrypt & CRYPT_REQUIRE) != 0
                && (requesterCrypt & CRYPT_SUPPORT) == 0;
        return !requesterBlocks && !targetBlocks;
    }

    private boolean nameTarget(InetAddress from, long targetId, long now) {
        LinkedHashMap<Long, Long> named = addressTargets.get(from);
        if (named == null) {
            if (addressTargets.size() >= MAX_TRACKED) return false;
            named = new LinkedHashMap<>();
            addressTargets.put(from, named);
        }
        named.values().removeIf(at -> now - at >= TARGET_WINDOW_MS);
        if (!named.containsKey(targetId) && named.size() >= TARGETS_PER_ADDRESS) return false;
        named.remove(targetId);
        named.put(targetId, now);
        return true;
    }

    private boolean allowWake(long targetId, long now) {
        ArrayDeque<Long> wakes = targetWakes.get(targetId);
        if (wakes == null) {
            if (targetWakes.size() >= MAX_TRACKED) return false;
            wakes = new ArrayDeque<>();
            targetWakes.put(targetId, wakes);
        }
        while (!wakes.isEmpty() && now - wakes.peekFirst() >= WAKE_WINDOW_MS) wakes.pollFirst();
        if (wakes.size() >= WAKES_PER_TARGET) return false;
        wakes.addLast(now);
        return true;
    }

    private void dropOldestWaiting(long targetId) {
        int waiting = 0;
        Key oldest = null;
        for (Map.Entry<Key, Pending> e : inFlight.entrySet()) {
            if (e.getValue().targetId == targetId && e.getValue().answer == null) {
                if (oldest == null) oldest = e.getKey();
                waiting++;
            }
        }
        if (waiting >= MAX_WAITING_PER_TARGET) inFlight.remove(oldest);
    }

    private void pruneIfDue(long now) {
        if (now - prunedAt < PRUNE_EVERY_MS) return;
        prunedAt = now;
        addressRates.values().removeIf(b -> b.full(now));
        addressTargets.values().removeIf(named -> {
            named.values().removeIf(at -> now - at >= TARGET_WINDOW_MS);
            return named.isEmpty();
        });
        targetWakes.values().removeIf(wakes -> {
            while (!wakes.isEmpty() && now - wakes.peekFirst() >= WAKE_WINDOW_MS) wakes.pollFirst();
            return wakes.isEmpty();
        });
    }

    /** How many requests ended with {@code outcome}. */
    public long count(Outcome outcome) {
        return outcomes.get(outcome).get();
    }

    /** Answers given to requesters, resends not counted. */
    public long answers() {
        return answers.get();
    }

    /** Requests waiting for the target's 0x9F or being answered. */
    public synchronized int inFlight() {
        return inFlight.size();
    }
}
