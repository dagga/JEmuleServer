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

import org.jemule.config.KadSourcesConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.kad.KadPacket;
import org.jemule.network.handler.FoundSource;
import org.jemule.network.handler.HandlerUtils;
import org.jemule.network.handler.SourceHandler;
import org.jemule.protocol.OpCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Kad sources for eD2K clients. When a client asks for the sources of a file (OP_GETSOURCES) and the server knows
 * few, the sources Kad gave for that file in the last 20 minutes are added to the answer; without them, Kad is
 * searched and the new sources are sent in a second OP_FOUNDSOURCES, which eMule and aMule take at any time for a
 * file they download (CServerSocket::ProcessPacket).
 * <p>
 * A Kad source that can be contacted directly (types 1 and 4) is sent with its IP as HighID. A firewalled one
 * (types 3, 5 and 6) is useless to an eD2K client, unless that client is connected to this server: it is then sent
 * with its ID here, LowID included, and reached through this server's callback.
 */
public final class KadSourceService {
    private static final Logger log = LoggerFactory.getLogger(KadSourceService.class);

    /** How long the sources Kad gave for a file are reused. */
    static final long CACHE_TTL_MS = 20 * 60_000L;
    /** Files whose Kad sources are kept, the least recently asked dropped first. */
    static final int MAX_CACHED_FILES = 1000;
    /** Kad sources kept per file, the directly reachable ones first. */
    static final int MAX_SOURCES_PER_FILE = 50;
    /** eMule asks its server for the sources of a file every 15 minutes (SERVERREASKTIME): one Kad search per period. */
    static final long CLIENT_REASK_MS = 15 * 60_000L;
    /** Kad source searches one client may start per hour. */
    static final int CLIENT_SEARCHES_PER_HOUR = 20;

    /** Starts a Kad source search; KadSearchBridge#searchSources in the server. */
    public interface Searcher {
        CompletableFuture<List<KadPacket.KadSource>> search(byte[] fileHash, long fileSize);
    }

    private record Cached(long time, List<KadPacket.KadSource> sources) {}

    private final Searcher searcher;
    private final ClientRegistry registry;
    private final Predicate<InetAddress> blocked;
    private final Supplier<ServerConfig> config;
    private final LongSupplier clock;

    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED_FILES;
        }
    };
    private long windowStart;
    private int windowSearches;

    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong skippedBudget = new AtomicLong();
    private final AtomicLong directFound = new AtomicLong();
    private final AtomicLong firewalledMatched = new AtomicLong();
    private final AtomicLong firewalledDropped = new AtomicLong();
    private final AtomicLong answersSent = new AtomicLong();
    private final AtomicLong sourcesDelivered = new AtomicLong();

    public KadSourceService(Searcher searcher, ClientRegistry registry, Predicate<InetAddress> blocked,
                            Supplier<ServerConfig> config, LongSupplier clock) {
        this.searcher = searcher;
        this.registry = registry;
        this.blocked = blocked;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Completes the answer to an OP_GETSOURCES with few local sources: adds the Kad sources known for the file, or
     * starts a Kad search whose new sources are sent to {@code requester} later.
     *
     * @param local the sources of this server already in the answer
     * @return the sources to send now
     */
    public List<FoundSource> onGetSources(ClientState requester, byte[] fileHash, long fileSize, boolean obfu,
                                          List<FoundSource> local) {
        KadSourcesConfig settings = config.get().kadSources();
        if (!settings.enabled() || local.size() >= settings.minLocalSources() || fileSize <= 0) return local;
        String hash = HandlerUtils.bytesToHashString(fileHash);
        long now = clock.getAsLong();

        Cached cached = cached(hash, now);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return merge(local, usable(requester, cached.sources()));
        }
        if (!requester.tryKadSourceSearch(hash, now, CLIENT_REASK_MS, CLIENT_SEARCHES_PER_HOUR)
                || !takeGlobalBudget(now, settings.searchesPerMinute())) {
            skippedBudget.incrementAndGet();
            return local;
        }

        searches.incrementAndGet();
        int connectionId = requester.connectionId();
        searcher.search(fileHash, fileSize).thenAccept(found -> {
            List<KadPacket.KadSource> kept = keep(found);
            synchronized (cache) {
                cache.put(hash, new Cached(clock.getAsLong(), kept));
            }
            List<FoundSource> extra = new ArrayList<>(usable(requester, kept));
            extra.removeIf(s -> local.stream().anyMatch(s::sameEndpoint));
            if (extra.isEmpty()) return;
            byte[] payload = obfu ? SourceHandler.encodeObfu(fileHash, extra) : SourceHandler.encodePlain(fileHash, extra);
            Packet packet = new Packet(Packet.PROTOCOL_ED2K,
                    obfu ? OpCode.SOURCES_RESULT_OBFU.value : OpCode.FOUND_SOURCES.value, payload);
            if (registry.sendToConnection(connectionId, packet)) {
                answersSent.incrementAndGet();
                sourcesDelivered.addAndGet(Math.min(extra.size(), 255));
                log.debug("Sent {} Kad source(s) of {} to client {}", extra.size(), hash, requester.clientId());
            }
        }).exceptionally(e -> {
            log.debug("Kad source search for {} failed: {}", hash, e.getMessage());
            return null;
        });
        return local;
    }

    private Cached cached(String hash, long now) {
        synchronized (cache) {
            Cached cached = cache.get(hash);
            if (cached != null && now - cached.time() >= CACHE_TTL_MS) {
                cache.remove(hash);
                return null;
            }
            return cached;
        }
    }

    private synchronized boolean takeGlobalBudget(long now, int perMinute) {
        if (now - windowStart >= 60_000L) {
            windowStart = now;
            windowSearches = 0;
        }
        if (windowSearches >= perMinute) return false;
        windowSearches++;
        return true;
    }

    /** At most {@link #MAX_SOURCES_PER_FILE} sources, the directly reachable ones first; counts them. */
    private List<KadPacket.KadSource> keep(List<KadPacket.KadSource> found) {
        List<KadPacket.KadSource> kept = new ArrayList<>();
        found.stream().filter(KadPacket.KadSource::isDirect).forEach(kept::add);
        found.stream().filter(s -> !s.isDirect()).forEach(kept::add);
        return kept.size() > MAX_SOURCES_PER_FILE ? List.copyOf(kept.subList(0, MAX_SOURCES_PER_FILE)) : kept;
    }

    /**
     * The Kad sources {@code requester} can use: the directly reachable ones with a public, unfiltered IP, and the
     * firewalled ones connected to this server, never the requester itself, each endpoint once.
     */
    List<FoundSource> usable(ClientState requester, List<KadPacket.KadSource> sources) {
        List<FoundSource> usable = new ArrayList<>();
        Map<String, ClientState> connected = null;
        for (KadPacket.KadSource s : sources) {
            if (Arrays.equals(s.userHash(), requester.userHash())) continue;
            FoundSource source = null;
            if (s.isDirect()) {
                InetAddress address = address(s.ip());
                if (s.tcpPort() > 0 && address != null && isPublic(address) && !address.equals(requester.address())
                        && !blocked.test(address)) {
                    // Kad gives the IP in host order (a << 24 | ... | d), eD2K IDs are a | b << 8 | ... (ntohl)
                    source = new FoundSource(Integer.toUnsignedLong(Integer.reverseBytes(s.ip())), s.tcpPort(),
                            s.cryptOptions(), s.userHash());
                    directFound.incrementAndGet();
                }
            } else {
                if (connected == null) connected = connectedByUserHash();
                ClientState client = connected.get(HandlerUtils.bytesToHashString(s.userHash()));
                if (client != null && client.connectionId() != requester.connectionId() && client.listenPort() > 0) {
                    source = FoundSource.of(client);
                    firewalledMatched.incrementAndGet();
                } else {
                    firewalledDropped.incrementAndGet();
                }
            }
            if (source != null && usable.stream().noneMatch(source::sameEndpoint)) usable.add(source);
        }
        return usable;
    }

    private Map<String, ClientState> connectedByUserHash() {
        Map<String, ClientState> byHash = new HashMap<>();
        for (ClientState client : registry.getAllClients()) {
            byHash.put(HandlerUtils.bytesToHashString(client.userHash()), client);
        }
        return byHash;
    }

    private static List<FoundSource> merge(List<FoundSource> local, List<FoundSource> kad) {
        List<FoundSource> merged = new ArrayList<>(local);
        for (FoundSource s : kad) {
            if (merged.size() >= 255) break;
            if (merged.stream().noneMatch(s::sameEndpoint)) merged.add(s);
        }
        return merged;
    }

    private static InetAddress address(int hostOrderIp) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) (hostOrderIp >>> 24), (byte) (hostOrderIp >>> 16),
                    (byte) (hostOrderIp >>> 8), (byte) hostOrderIp});
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Not 0/8, private, loopback, link-local, shared (100.64/10) or multicast. */
    static boolean isPublic(InetAddress address) {
        byte[] b = address.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first != 0 && !address.isSiteLocalAddress() && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && !address.isMulticastAddress() && first < 224
                && !(first == 100 && second >= 64 && second < 128);
    }

    /** Counters for the admin panel. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", config.get().kadSources().enabled());
        stats.put("searches", searches.get());
        stats.put("cacheHits", cacheHits.get());
        stats.put("skippedBudget", skippedBudget.get());
        stats.put("directFound", directFound.get());
        stats.put("firewalledMatched", firewalledMatched.get());
        stats.put("firewalledDropped", firewalledDropped.get());
        stats.put("answersSent", answersSent.get());
        stats.put("sourcesDelivered", sourcesDelivered.get());
        synchronized (cache) {
            stats.put("cachedFiles", cache.size());
        }
        return stats;
    }
}
