package org.jemule.network.handler;

import org.jemule.core.FileMetadata;
import org.jemule.core.SearchQuery;
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
import java.util.List;

public class SearchHandler {
    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);
    private static final int BATCH_SIZE = 50;

    public void handleSearch(ClientContext context, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 1) {
            log.warn("Invalid search request: empty data");
            return;
        }

        List<FileMetadata> results;
        try {
            SearchQuery query = SearchQuery.parse(ByteBuffer.wrap(data));
            results = context.getFileIndex().searchComplex(query, context.getConfig().maxSearchResults());
            log.debug("Search {} -> {} results", query, results.size());
        } catch (RuntimeException e) {
            // Never guess: an unparsable tree gets an empty answer, so that the client ends its search
            log.warn("Malformed search request ({} bytes): {}", data.length, HandlerUtils.sanitize(String.valueOf(e.getMessage())));
            results = List.of();
        }

        List<FileMetadata> batch = results.subList(0, Math.min(results.size(), BATCH_SIZE));
        sendSearchResults(context, batch, results.size() > BATCH_SIZE, out);

        if (results.size() > BATCH_SIZE) {
            context.getState().setPendingSearchResults(new ArrayList<>(results.subList(BATCH_SIZE, results.size())));
        } else {
            context.getState().setPendingSearchResults(null);
        }
    }

    public void handleQueryMoreResult(ClientContext context, OutputStream out) throws IOException {
        List<FileMetadata> pending = context.getState().getPendingSearchResults();
        if (pending == null || pending.isEmpty()) {
            return;
        }

        List<FileMetadata> batch = pending.subList(0, Math.min(pending.size(), BATCH_SIZE));
        sendSearchResults(context, batch, pending.size() > BATCH_SIZE, out);

        if (pending.size() > BATCH_SIZE) {
            context.getState().setPendingSearchResults(new ArrayList<>(pending.subList(BATCH_SIZE, pending.size())));
        } else {
            context.getState().setPendingSearchResults(null);
        }
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
            byte[] hashBytes = HandlerUtils.hashToBytes(m.hash());

            ByteBuffer itemBuf = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
            itemBuf.put(hashBytes);
            itemBuf.putInt(0); // ID
            itemBuf.putShort((short) 0); // Port

            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, m.name())); // Corrected tag name
            if (m.size() > Integer.MAX_VALUE) {
                tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) (m.size() & 0xFFFFFFFFL))); // Corrected tag name
                tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE_HI, (int) (m.size() >> 32))); // Corrected tag name
            } else {
                tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) m.size())); // Corrected tag name
            }
            if (m.type() != null && !m.type().isEmpty()) {
                tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, m.type())); // search results use the string form ("Video")
            }
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, m.sources().size()));
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_COMPLETE_SOURCES, m.completeSourceCount()));

            Tag.writeList(itemBuf, tags);
            itemBuf.flip();
            byte[] itemData = new byte[itemBuf.remaining()];
            itemBuf.get(itemData);
            baos.writeBytes(itemData);
        }
        baos.write(moreAvailable ? 0x01 : 0x00);
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SEARCH_RESULT.value, baos.toByteArray()).write(out, context.getState().isZlibSupported());
    }
}