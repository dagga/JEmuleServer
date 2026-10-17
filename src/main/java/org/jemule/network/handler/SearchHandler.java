package org.jemule.network.handler;

import org.jemule.core.FileMetadata;
import org.jemule.core.SearchQuery;
import org.jemule.kad.KadSearchBridge;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SearchHandler {
    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);
    private static final int BATCH_SIZE = 50;

    public void handleSearch(ClientContext context, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 1) {
            log.warn("Invalid search request: empty data");
            return;
        }

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

        KadSearchBridge kadBridge = context.getKadSearchBridge();
        if (kadBridge != null && query != null) {
            try {
                CompletableFuture<List<FileMetadata>> kadFuture = kadBridge.forkSearch(query);
                if (kadFuture != null) {
                    if (dedupMap.size() < BATCH_SIZE) {
                        try {
                            List<FileMetadata> kadResults = kadFuture.get(kadBridge.getSearchTimeoutMs() + 1000, TimeUnit.MILLISECONDS);
                            if (kadResults != null) {
                                for (FileMetadata kf : kadResults) {
                                    if (kf != null && kf.hash() != null) {
                                        String upperHash = kf.hash().toUpperCase(Locale.ROOT);
                                        if (!dedupMap.containsKey(upperHash) && query.test(kf)) {
                                            dedupMap.put(upperHash, kf);
                                        }
                                    }
                                }
                            }
                        } catch (TimeoutException e) {
                            log.debug("Kad search fork timed out for query: {}", query);
                        } catch (Exception e) {
                            log.debug("Kad search fork error for query {}: {}", query, e.getMessage());
                        }
                    } else {
                        final SearchQuery activeQuery = query;
                        kadFuture.thenAccept(kadResults -> {
                            if (kadResults != null && !kadResults.isEmpty() && context.getState() != null) {
                                List<FileMetadata> pending = context.getState().getPendingSearchResults();
                                if (pending != null) {
                                    synchronized (pending) {
                                        for (FileMetadata kf : kadResults) {
                                            if (kf != null && kf.hash() != null) {
                                                String upperHash = kf.hash().toUpperCase(Locale.ROOT);
                                                boolean exists = pending.stream().anyMatch(p -> p.hash().equalsIgnoreCase(upperHash));
                                                if (!exists && activeQuery.test(kf)) {
                                                    pending.add(kf);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                log.debug("Error initiating Kad search fork: {}", e.getMessage());
            }
        }

        List<FileMetadata> combined = new ArrayList<>(dedupMap.values());
        List<FileMetadata> batch = combined.subList(0, Math.min(combined.size(), BATCH_SIZE));
        sendSearchResults(context, batch, combined.size() > BATCH_SIZE, out);

        if (combined.size() > BATCH_SIZE) {
            context.getState().setPendingSearchResults(new ArrayList<>(combined.subList(BATCH_SIZE, combined.size())));
        } else {
            context.getState().setPendingSearchResults(null);
        }
    }

    public void handleQueryMoreResult(ClientContext context, OutputStream out) throws IOException {
        List<FileMetadata> pending = context.getState().getPendingSearchResults();
        if (pending == null || pending.isEmpty()) {
            return;
        }

        List<FileMetadata> batch;
        boolean hasMore;
        synchronized (pending) {
            batch = new ArrayList<>(pending.subList(0, Math.min(pending.size(), BATCH_SIZE)));
            if (pending.size() > BATCH_SIZE) {
                context.getState().setPendingSearchResults(new ArrayList<>(pending.subList(BATCH_SIZE, pending.size())));
                hasMore = true;
            } else {
                context.getState().setPendingSearchResults(null);
                hasMore = false;
            }
        }
        sendSearchResults(context, batch, hasMore, out);
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
