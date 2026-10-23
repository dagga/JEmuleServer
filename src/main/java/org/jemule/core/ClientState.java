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


package org.jemule.core;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

public final class ClientState {
    private static final AtomicInteger nextConnectionId = new AtomicInteger(1);

    private final int connectionId;
    private final InetAddress address;
    private final int port;
    private volatile long clientId;
    private final long connectedAt;
    private final AtomicLong lastActivity;
    private final AtomicInteger publishedFilesCount = new AtomicInteger(0);
    /** Hashes of the files this client offered: it must be removed from their sources when it disconnects. */
    private final java.util.Set<String> publishedHashes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Hashes offered as incomplete (part files, ID 0xFCFCFCFC in OP_OFFERFILES). */
    private final java.util.Set<String> partialHashes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean zlibSupported = false;
    private List<FileMetadata> pendingSearchResults;
    private long pendingSearchGeneration;
    private boolean pendingSearchLoading;
    /** Completed when the remote results of the pending search arrived (at once if none are expected). */
    private java.util.concurrent.CompletableFuture<Void> pendingSearchArrival =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    // Information announced by the client in OP_LOGINREQUEST (ServerConnect::ConnectionEstablished)
    private volatile byte[] userHash = new byte[16];
    private volatile String nickname = "";
    private volatile int listenPort;
    private volatile long clientVersion;
    private volatile long emuleVersion;
    private volatile int serverCaps;
    /** OFFERFILES v1 version asked for in the login (string-named uint32 tag offerfiles_v), 0 if none. */
    private volatile long offerFilesRequested;
    /** OFFERFILES v1 terms advertised to this client at login, null for a legacy connection. */
    private volatile OfferFilesSession offerFilesSession;
    /** Whether the client was already told that files beyond ST_SOFTFILES are ignored: once per connection. */
    private final java.util.concurrent.atomic.AtomicBoolean softLimitReported = new java.util.concurrent.atomic.AtomicBoolean();

    public ClientState(InetAddress address, int port, long clientId, long connectedAt, AtomicLong lastActivity) {
        this.connectionId = nextConnectionId.getAndIncrement();
        this.address = requireNonNull(address);
        this.port = port;
        this.clientId = clientId;
        this.connectedAt = connectedAt;
        this.lastActivity = lastActivity;
    }

    public int connectionId() { return connectionId; }
    public InetAddress address() { return address; }
    public int port() { return port; }
    public long clientId() { return clientId; }

    /** Sets the ID assigned at login (HighID = IP, or LowID); must be called before registering the client. */
    public void setClientId(long clientId) { this.clientId = clientId; }
    public long connectedAt() { return connectedAt; }
    public AtomicLong lastActivity() { return lastActivity; }

    public AtomicInteger publishedFilesCount() { return publishedFilesCount; }

    public java.util.Set<String> publishedHashes() { return publishedHashes; }

    public java.util.Set<String> partialHashes() { return partialHashes; }

    public boolean isZlibSupported() { return zlibSupported; }
    public void setZlibSupported(boolean zlibSupported) { this.zlibSupported = zlibSupported; }

    public byte[] userHash() { return userHash; }
    public void setUserHash(byte[] userHash) { this.userHash = userHash.clone(); }

    public String nickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname == null ? "" : nickname; }

    /** TCP port on which the client accepts incoming connections (from the login packet), 0 if unknown. */
    public int listenPort() { return listenPort; }
    public void setListenPort(int listenPort) { this.listenPort = listenPort; }

    /** CT_VERSION (ed2k protocol version, 0x3C for eMule). */
    public long clientVersion() { return clientVersion; }
    public void setClientVersion(long clientVersion) { this.clientVersion = clientVersion; }

    /** CT_EMULE_VERSION ((major << 17) | (minor << 10) | (update << 7)), 0 for non eMule clients. */
    public long emuleVersion() { return emuleVersion; }
    public void setEmuleVersion(long emuleVersion) { this.emuleVersion = emuleVersion; }

    public long offerFilesRequested() { return offerFilesRequested; }
    public void setOfferFilesRequested(long version) { this.offerFilesRequested = version; }

    public OfferFilesSession offerFilesSession() { return offerFilesSession; }
    public void setOfferFilesSession(OfferFilesSession session) { this.offerFilesSession = session; }

    public java.util.concurrent.atomic.AtomicBoolean softLimitReported() { return softLimitReported; }

    /** Kad source searches started for this client: file hash to time, the 64 most recent. */
    private final java.util.Map<String, Long> kadSourceSearches = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };

    /**
     * Records a Kad source search for {@code hash} unless this client had one for it within {@code reaskMs}, or
     * already {@code perHour} in the last hour.
     *
     * @return true if the search may start
     */
    public boolean tryKadSourceSearch(String hash, long now, long reaskMs, int perHour) {
        synchronized (kadSourceSearches) {
            Long last = kadSourceSearches.get(hash);
            if (last != null && now - last < reaskMs) return false;
            if (kadSourceSearches.values().stream().filter(t -> now - t < 3_600_000L).count() >= perHour) return false;
            kadSourceSearches.put(hash, now);
            return true;
        }
    }

    /** CT_SERVER_FLAGS: SRVCAP_* capabilities announced by the client. */
    public int serverCaps() { return serverCaps; }
    public void setServerCaps(int serverCaps) { this.serverCaps = serverCaps; }

    public boolean supportsCrypt() { return (serverCaps & SRVCAP_SUPPORTCRYPT) != 0; }
    public boolean requestsCrypt() { return (serverCaps & SRVCAP_REQUESTCRYPT) != 0; }
    public boolean requiresCrypt() { return (serverCaps & SRVCAP_REQUIRECRYPT) != 0; }

    // SRVCAP_* values (opcodes.h)
    public static final int SRVCAP_ZLIB = 0x0001;
    public static final int SRVCAP_IP_IN_LOGIN = 0x0002;
    public static final int SRVCAP_AUXPORT = 0x0004;
    public static final int SRVCAP_NEWTAGS = 0x0008;
    public static final int SRVCAP_UNICODE = 0x0010;
    public static final int SRVCAP_LARGEFILES = 0x0100;
    public static final int SRVCAP_SUPPORTCRYPT = 0x0200;
    public static final int SRVCAP_REQUESTCRYPT = 0x0400;
    public static final int SRVCAP_REQUIRECRYPT = 0x0800;

    public record PendingSearchBatch(List<FileMetadata> results, boolean moreAvailable) {}

    public synchronized long beginPendingSearch() {
        pendingSearchGeneration++;
        pendingSearchResults = new java.util.ArrayList<>();
        pendingSearchLoading = false;
        pendingSearchArrival.complete(null); // a client waiting for the previous search stops waiting
        return pendingSearchGeneration;
    }

    public synchronized void initializePendingSearch(long generation, List<FileMetadata> results, boolean loading) {
        if (generation != pendingSearchGeneration) return;
        // null once the client was told that no more results are available
        pendingSearchResults = results.isEmpty() && !loading ? null : new java.util.ArrayList<>(results);
        pendingSearchLoading = loading;
        pendingSearchArrival = loading ? new java.util.concurrent.CompletableFuture<>()
                : java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * Returns a future completed when the remote results of the pending search arrived, so that a client asking
     * for more results while nothing is left yet can wait for them.
     */
    public synchronized java.util.concurrent.CompletableFuture<Void> awaitablePendingSearch() {
        boolean nothingLeft = pendingSearchResults == null || pendingSearchResults.isEmpty();
        return nothingLeft ? pendingSearchArrival : java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    public synchronized void completePendingSearch(long generation, List<FileMetadata> results) {
        if (generation != pendingSearchGeneration) return;
        if (pendingSearchResults == null) pendingSearchResults = new java.util.ArrayList<>();
        java.util.Set<String> pending = new java.util.HashSet<>();
        for (FileMetadata result : pendingSearchResults) {
            if (result != null && result.hash() != null) pending.add(result.hash().toUpperCase(java.util.Locale.ROOT));
        }
        for (FileMetadata file : results) {
            if (file != null && file.hash() != null && pending.add(file.hash().toUpperCase(java.util.Locale.ROOT))) {
                pendingSearchResults.add(file);
            }
        }
        pendingSearchLoading = false;
        pendingSearchArrival.complete(null);
    }

    public synchronized PendingSearchBatch takePendingSearchBatch(int batchSize) {
        // An empty batch still answers the client, which announced more results and waits for this answer
        if (pendingSearchResults == null) return null;
        int count = Math.min(pendingSearchResults.size(), batchSize);
        List<FileMetadata> batch = new java.util.ArrayList<>(pendingSearchResults.subList(0, count));
        pendingSearchResults.subList(0, count).clear();
        boolean moreAvailable = !pendingSearchResults.isEmpty() || pendingSearchLoading;
        if (!moreAvailable) pendingSearchResults = null;
        return new PendingSearchBatch(batch, moreAvailable);
    }

    public static long ipToLong(InetAddress addr) {
        byte[] b = addr.getAddress();
        // If IPv4 (4 bytes) -> convert normally (little-endian expected by protocol)
        if (b.length == 4) {
            return (long) ((b[3] & 0xFF) << 24 | (b[2] & 0xFF) << 16 | (b[1] & 0xFF) << 8 | (b[0] & 0xFF)) & 0xFFFFFFFFL;
        }
        // If IPv6 and IPv4-mapped (::ffff:0:0/96), use the last 4 bytes
        if (b.length == 16) {
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++)
                if (b[i] != 0) {
                    isV4Mapped = false;
                    break;
                }
            if (isV4Mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                int off = 12;
                return (long) ((b[off + 3] & 0xFF) << 24 | (b[off + 2] & 0xFF) << 16 | (b[off + 1] & 0xFF) << 8 | (b[off] & 0xFF)) & 0xFFFFFFFFL;
            }
        }
        // Fallback: return 0 (unspecified) when IPv6 address cannot be encoded in 4 bytes
        return 0;
    }

    public boolean isHighId() {
        return clientId > 0x00FFFFFFL;
    }
}
