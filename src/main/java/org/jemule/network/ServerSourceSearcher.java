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


package org.jemule.network;

import org.jemule.kad.KadPacket;
import org.jemule.network.handler.HandlerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Asks the verified eD2K servers for the sources of files, as an eMule client does with its UDP global source
 * search: OP_GLOBGETSOURCES2 {@code (<hash 16><size 4> | <hash 16><0 4><size 8>)*}, answered by OP_GLOBFOUNDSOURCES
 * {@code <hash 16><count 1>(<ID 4><port 2>)*}, several blocks per datagram.
 * <p>
 * The files asked within {@link #BATCH_DELAY_MS} go in one datagram per server, {@link #MAX_FILES_PER_REQUEST} at
 * most: a server receives at most one request every {@link #BATCH_DELAY_MS}, whatever the number of clients. Only
 * the HighID sources are kept: a LowID of another server can only be reached through that server.
 */
final class ServerSourceSearcher {
    private static final Logger log = LoggerFactory.getLogger(ServerSourceSearcher.class);

    static final int OP_GLOBGETSOURCES2 = 0x94;
    static final int OP_GLOBFOUNDSOURCES = 0x9B;
    /** Files per request: what eMule sends to a server announcing SRV_UDPFLG_EXT_GETSOURCES2. */
    static final int MAX_FILES_PER_REQUEST = 35;
    /** How long the first file of a batch waits for others. */
    static final long BATCH_DELAY_MS = 3_000;
    /** How long the servers have to answer. */
    static final long ANSWER_WAIT_MS = 3_000;
    /** Sources kept per file and per search. */
    static final int MAX_SOURCES = 100;
    /** Servers asked per batch. */
    static final int MAX_SERVERS = 32;
    /** Lowest HighID: smaller IDs are LowIDs, given by a server to the clients it cannot reach. */
    private static final long LOW_ID_LIMIT = 16_777_216L;

    /** One file searched: the sources answered, by endpoint. */
    private static final class Lookup {
        final byte[] hash;
        final long size;
        final Map<Long, KadPacket.KadSource> sources = new LinkedHashMap<>();
        final CompletableFuture<List<KadPacket.KadSource>> future = new CompletableFuture<>();
        Set<ServerGossip.Endpoint> asked = Set.of();

        Lookup(byte[] hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }

    private final Supplier<Collection<ServerGossip.VerifiedPeer>> peers;
    private final Supplier<DatagramSocket> socket;
    private final Executor batchDelay;
    private final Executor answerDelay;

    /** Files waiting for the next request, by hash. */
    private final Map<String, Lookup> batch = new LinkedHashMap<>();
    /** Files asked, waiting for the answers, by hash. */
    private final Map<String, Lookup> open = new ConcurrentHashMap<>();

    private final AtomicLong requestsSent = new AtomicLong();
    private final AtomicLong filesAsked = new AtomicLong();
    private final AtomicLong sourcesReceived = new AtomicLong();
    private final AtomicLong highIdSources = new AtomicLong();

    ServerSourceSearcher(Supplier<Collection<ServerGossip.VerifiedPeer>> peers, Supplier<DatagramSocket> socket) {
        this(peers, socket, CompletableFuture.delayedExecutor(BATCH_DELAY_MS, TimeUnit.MILLISECONDS),
                CompletableFuture.delayedExecutor(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS));
    }

    ServerSourceSearcher(Supplier<Collection<ServerGossip.VerifiedPeer>> peers, Supplier<DatagramSocket> socket,
                         Executor batchDelay, Executor answerDelay) {
        this.peers = peers;
        this.socket = socket;
        this.batchDelay = batchDelay;
        this.answerDelay = answerDelay;
    }

    /**
     * Searches the verified servers for the sources of a file; searches of the same file share one request.
     *
     * @return the HighID sources answered, as direct Kad sources (type 1, IP in host order), possibly none
     */
    CompletableFuture<List<KadPacket.KadSource>> search(byte[] fileHash, long fileSize) {
        if (fileHash == null || fileHash.length != 16 || fileSize <= 0 || peers.get().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String key = HandlerUtils.bytesToHashString(fileHash);
        boolean schedule;
        Lookup lookup;
        synchronized (batch) {
            Lookup known = open.get(key);
            if (known == null) known = batch.get(key);
            if (known != null) return known.future;
            lookup = new Lookup(fileHash.clone(), fileSize);
            schedule = batch.isEmpty();
            batch.put(key, lookup);
            if (batch.size() >= MAX_FILES_PER_REQUEST) {
                flush();
                return lookup.future;
            }
        }
        if (schedule) batchDelay.execute(this::flushBatch);
        return lookup.future;
    }

    private void flushBatch() {
        synchronized (batch) {
            flush();
        }
    }

    /** Sends the files waiting to every verified server; called with the batch lock held. */
    private void flush() {
        if (batch.isEmpty()) return;
        List<Lookup> files = new ArrayList<>(batch.values());
        batch.clear();
        byte[] request = request(files);
        Set<ServerGossip.Endpoint> asked = ConcurrentHashMap.newKeySet();
        DatagramSocket udp = socket.get();
        if (udp != null) {
            for (ServerGossip.VerifiedPeer peer : peers.get()) {
                if (asked.size() == MAX_SERVERS) break;
                ServerGossip.Endpoint endpoint = peer.endpoint();
                try {
                    udp.send(new DatagramPacket(request, request.length, endpoint.address(), endpoint.udpPort()));
                    asked.add(endpoint);
                    requestsSent.incrementAndGet();
                } catch (java.io.IOException e) {
                    log.debug("Could not send a source request to {}: {}", endpoint, e.getMessage());
                }
            }
        }
        filesAsked.addAndGet(files.size());
        for (Lookup lookup : files) {
            lookup.asked = asked;
            open.put(HandlerUtils.bytesToHashString(lookup.hash), lookup);
        }
        answerDelay.execute(() -> files.forEach(this::complete));
    }

    private void complete(Lookup lookup) {
        open.remove(HandlerUtils.bytesToHashString(lookup.hash), lookup);
        List<KadPacket.KadSource> sources;
        synchronized (lookup) {
            sources = List.copyOf(lookup.sources.values());
        }
        lookup.future.complete(sources);
    }

    private static byte[] request(List<Lookup> files) {
        ByteBuffer out = ByteBuffer.allocate(2 + files.size() * 28).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 0xE3).put((byte) OP_GLOBGETSOURCES2);
        for (Lookup lookup : files) {
            out.put(lookup.hash);
            if (lookup.size > 0xFFFFFFFFL) {
                out.putInt(0).putLong(lookup.size);
            } else {
                out.putInt((int) lookup.size);
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    /**
     * Takes an OP_GLOBFOUNDSOURCES from a server asked for the files it answers.
     *
     * @return true if the packet was an OP_GLOBFOUNDSOURCES (used or not)
     */
    boolean handle(InetAddress source, int sourcePort, byte[] packet) {
        if (packet == null || packet.length < 2 || (packet[0] & 0xFF) != 0xE3
                || (packet[1] & 0xFF) != OP_GLOBFOUNDSOURCES) {
            return false;
        }
        if (open.isEmpty() || sourcePort < 5) return true;
        ServerGossip.Endpoint endpoint;
        try {
            endpoint = new ServerGossip.Endpoint(source, sourcePort - 4);
        } catch (IllegalArgumentException e) {
            return true;
        }
        ByteBuffer in = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        in.position(2);
        Map<Lookup, List<KadPacket.KadSource>> found = new HashMap<>();
        while (in.remaining() >= 17) {
            byte[] hash = new byte[16];
            in.get(hash);
            int count = in.get() & 0xFF;
            if (in.remaining() < count * 6) break;
            Lookup lookup = open.get(HandlerUtils.bytesToHashString(hash));
            boolean expected = lookup != null && lookup.asked.contains(endpoint);
            for (int i = 0; i < count; i++) {
                long id = Integer.toUnsignedLong(in.getInt());
                int port = Short.toUnsignedInt(in.getShort());
                if (!expected) continue;
                sourcesReceived.incrementAndGet();
                if (id < LOW_ID_LIMIT || port == 0) continue;
                // eD2K IDs are a | b << 8 | ...: Kad sources carry the IP the other way round
                found.computeIfAbsent(lookup, ignored -> new ArrayList<>()).add(new KadPacket.KadSource(
                        new byte[16], 1, Integer.reverseBytes((int) id), port, 0));
            }
            // A further block starts with its own <0xE3><0x9B> header
            if (in.remaining() >= 2 && (in.get(in.position()) & 0xFF) == 0xE3
                    && (in.get(in.position() + 1) & 0xFF) == OP_GLOBFOUNDSOURCES) {
                in.position(in.position() + 2);
            }
        }
        found.forEach((lookup, sources) -> {
            synchronized (lookup) {
                for (KadPacket.KadSource s : sources) {
                    if (lookup.sources.size() >= MAX_SOURCES) break;
                    if (lookup.sources.putIfAbsent((Integer.toUnsignedLong(s.ip()) << 16) | s.tcpPort(), s) == null) {
                        highIdSources.incrementAndGet();
                    }
                }
            }
        });
        return true;
    }

    /** Counters for the admin panel. */
    Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("requestsSent", requestsSent.get());
        stats.put("filesAsked", filesAsked.get());
        stats.put("sourcesReceived", sourcesReceived.get());
        stats.put("highIdSources", highIdSources.get());
        return stats;
    }
}
