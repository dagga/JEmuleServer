package org.jemule.network.handler;

import org.jemule.core.FileMetadata;
import org.jemule.core.ClientState;
import org.jemule.core.SearchQuery;
import org.jemule.kad.KadSearchBridge;
import org.jemule.network.ServerSearchBridge;
import org.jemule.network.Packet;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SearchHandler {
    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);
    /**
     * Results per OP_SEARCHRESULT. eMule clients send OP_QUERY_MORE_RESULT at most 5 times per search
     * (MAX_MORE_SEARCH_REQ, sized for "(1+5)*300 = 1800" results), so the batch size bounds what a client can see.
     */
    public static final int BATCH_SIZE = 300;

    public void handleSearch(ClientContext context, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 1) {
            log.warn("Invalid search request: empty data");
            return;
        }
        long searchGeneration = context.getState().beginPendingSearch();

        List<FileMetadata> localResults;
        SearchQuery query;
        try {
            query = SearchQuery.parse(ByteBuffer.wrap(data));
            localResults = context.getFileIndex().searchComplex(query, context.getConfig().maxSearchResults());
            log.debug("Search {} -> {} local results", query, localResults.size());
        } catch (RuntimeException e) {
            // Never guess: an unparsable tree gets an empty answer, so that the client ends its search
            log.warn("Malformed search request ({} bytes): {}", data.length, HandlerUtils.sanitize(String.valueOf(e.getMessage())));
            localResults = List.of();
            query = null;
        }

        LinkedHashMap<String, FileMetadata> dedupMap = new LinkedHashMap<>();
        for (FileMetadata f : localResults) {
            if (f != null && f.hash() != null) {
                dedupMap.put(f.hash().toUpperCase(Locale.ROOT), f);
            }
        }

        CompletableFuture<List<FileMetadata>> remoteResults = startRemoteSearches(context, query, data);
        boolean deferRemoteResults = false;
        if (remoteResults != null) {
            if (remoteResults.isDone()) {
                addMatchingResults(dedupMap, remoteResults.join(), query);
            } else if (dedupMap.isEmpty()) {
                try {
                    addMatchingResults(dedupMap,
                            remoteResults.get(remoteTimeoutMs(context) + 1000, TimeUnit.MILLISECONDS), query);
                } catch (TimeoutException e) {
                    log.debug("Remote search timed out for query: {}", query);
                    deferRemoteResults = true;
                } catch (Exception e) {
                    log.debug("Remote search failed for query {}: {}", query, e.getMessage());
                }
            } else {
                // Local results are sent at once; the Kad and server results are offered through "More"
                deferRemoteResults = true;
            }
        }

        List<FileMetadata> combined = new ArrayList<>(dedupMap.values());
        List<FileMetadata> batch = combined.subList(0, Math.min(combined.size(), BATCH_SIZE));
        List<FileMetadata> pending = combined.size() > BATCH_SIZE
                ? new ArrayList<>(combined.subList(BATCH_SIZE, combined.size()))
                : List.of();
        context.getState().initializePendingSearch(searchGeneration, pending, deferRemoteResults);
        if (deferRemoteResults) {
            SearchQuery activeQuery = query;
            Set<String> known = new HashSet<>(dedupMap.keySet()); // already sent or pending
            remoteResults.whenComplete((results, error) -> {
                if (error != null) {
                    log.debug("Remote search failed: {}", error.getMessage());
                    context.getState().completePendingSearch(searchGeneration, List.of());
                } else {
                    context.getState().completePendingSearch(searchGeneration, matchingResults(activeQuery, results).stream()
                            .filter(file -> !known.contains(file.hash().toUpperCase(Locale.ROOT)))
                            .toList());
                }
            });
        }
        sendSearchResults(context, batch, combined.size() > BATCH_SIZE || deferRemoteResults, out);
    }

    private static long remoteTimeoutMs(ClientContext context) {
        return Math.max(
                context.getKadSearchBridge() == null ? 0 : context.getKadSearchBridge().getSearchTimeoutMs(),
                context.getServerSearchBridge() == null ? 0 : context.getServerSearchBridge().getSearchTimeoutMs());
    }

    private CompletableFuture<List<FileMetadata>> startRemoteSearches(ClientContext context, SearchQuery query,
                                                                       byte[] searchTree) {
        if (query == null) return null;
        List<CompletableFuture<List<FileMetadata>>> futures = new ArrayList<>();
        // Each network is searched on its own: a failure of Kad must not keep the search from the eD2K servers
        KadSearchBridge kadBridge = context.getKadSearchBridge();
        if (kadBridge != null) {
            try {
                CompletableFuture<List<FileMetadata>> future = kadBridge.forkSearch(query);
                if (future != null) futures.add(future);
            } catch (Exception e) {
                log.debug("Error initiating the Kad search for {}: {}", query, e.getMessage());
            }
        }
        ServerSearchBridge serverBridge = context.getServerSearchBridge();
        if (serverBridge != null) {
            try {
                CompletableFuture<List<FileMetadata>> future = serverBridge.forkSearch(query, searchTree);
                if (future != null) futures.add(future);
            } catch (Exception e) {
                log.debug("Error initiating the eD2K server search for {}: {}", query, e.getMessage());
            }
        }
        if (futures.isEmpty()) return null;
        List<CompletableFuture<List<FileMetadata>>> safeFutures = futures.stream()
                .map(future -> future.handle((results, error) -> {
                    if (error != null) {
                        log.debug("Remote search failed: {}", error.getMessage());
                        return List.<FileMetadata>of();
                    }
                    return results == null ? List.<FileMetadata>of() : results;
                }))
                .toList();
        return CompletableFuture.allOf(safeFutures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> safeFutures.stream().flatMap(future -> future.join().stream()).toList());
    }

    private static void addMatchingResults(LinkedHashMap<String, FileMetadata> results,
                                           List<FileMetadata> discovered, SearchQuery query) {
        if (discovered == null) return;
        for (FileMetadata file : discovered) {
            if (file != null && file.hash() != null && query.test(file)) {
                results.putIfAbsent(file.hash().toUpperCase(Locale.ROOT), file);
            }
        }
    }

    private static List<FileMetadata> matchingResults(SearchQuery query, List<FileMetadata> discovered) {
        if (discovered == null || query == null) return List.of();
        return discovered.stream().filter(file -> file != null && file.hash() != null && query.test(file)).toList();
    }

    public void handleQueryMoreResult(ClientContext context, OutputStream out) throws IOException {
        // Nothing left but remote results still expected: answer with them rather than with an empty batch,
        // which would use up one of the client's few "More" requests
        try {
            context.getState().awaitablePendingSearch().get(remoteTimeoutMs(context) + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Remote results still missing for OP_QUERY_MORE_RESULT: {}", e.getMessage());
        }
        ClientState.PendingSearchBatch pending = context.getState().takePendingSearchBatch(BATCH_SIZE);
        if (pending == null) return;
        sendSearchResults(context, pending.results(), pending.moreAvailable(), out);
    }

    /**
     * Sends OP_SEARCHRESULT <code>&lt;count 4&gt;(&lt;hash 16&gt;&lt;ID 4&gt;&lt;port 2&gt;&lt;tags&gt;)[count]&lt;more 1&gt;</code>.
     * The trailing byte (0x01 = more results available) enables the client's "More" button, which sends
     * OP_QUERY_MORE_RESULT (CSearchList::ProcessSearchAnswer).
     */
    private void sendSearchResults(ClientContext context, List<FileMetadata> results, boolean moreAvailable, OutputStream out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // <count 4> is little-endian like every ed2k integer (CSearchList::ProcessSearchAnswer)
        baos.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(results.size()).array());
        for (var m : results) {
            baos.writeBytes(encodeSearchResult(m));
        }
        baos.write(moreAvailable ? 0x01 : 0x00);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SEARCH_RESULT.value, baos.toByteArray()).write(out, context.getState().isZlibSupported());
    }

    /**
     * Encodes one search result <code>&lt;hash 16&gt;&lt;ID 4&gt;&lt;port 2&gt;&lt;tagcount 4&gt;&lt;tags&gt;</code> as read by
     * CSearchFile; shared by OP_SEARCHRESULT (TCP) and OP_GLOBSEARCHRES (UDP). ID and port are 0: the client
     * asks the server for the sources.
     */
    public static byte[] encodeSearchResult(FileMetadata m) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, m.name()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) (m.size() & 0xFFFFFFFFL)));
        if (m.size() > 0xFFFFFFFFL) {
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE_HI, (int) (m.size() >>> 32)));
        }
        if (m.type() != null && !m.type().isEmpty()) {
            tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, m.type())); // search results use the string form ("Video")
        }
        int sourceCount = Math.max(m.sources().size(), m.sourceCount());
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sourceCount));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_COMPLETE_SOURCES, m.completeSourceCount()));

        ByteBuffer itemBuf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        itemBuf.put(HandlerUtils.hashToBytes(m.hash()));
        itemBuf.putInt(0);
        itemBuf.putShort((short) 0);
        Tag.writeList(itemBuf, tags);
        byte[] itemData = new byte[itemBuf.position()];
        System.arraycopy(itemBuf.array(), 0, itemData, 0, itemData.length);
        return itemData;
    }
}
