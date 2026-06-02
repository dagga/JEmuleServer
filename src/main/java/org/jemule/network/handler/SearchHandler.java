package org.jemule.network.handler;

import org.jemule.core.FileMetadata;
import org.jemule.core.SearchQuery;
import org.jemule.network.Packet;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
            log.debug("Complex search -> {} results", results.size());
        } catch (Exception e) {
            log.warn("Failed to parse complex search, falling back to simple search: {}", HandlerUtils.sanitize(e.getMessage()));
            String queryStr = new String(data, StandardCharsets.UTF_8).trim();
            if (queryStr.length() < 3) {
                log.warn("Simple search query too short: '{}'", HandlerUtils.sanitize(queryStr));
                results = List.of();
            } else {
                results = context.getFileIndex().search(queryStr, context.getConfig().maxSearchResults());
                log.debug("Simple search '{}' -> {} results", queryStr, results.size());
            }
        }

        List<FileMetadata> batch = results.subList(0, Math.min(results.size(), BATCH_SIZE));
        sendSearchResults(context, batch, out);

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
        sendSearchResults(context, batch, out);

        if (pending.size() > BATCH_SIZE) {
            context.getState().setPendingSearchResults(new ArrayList<>(pending.subList(BATCH_SIZE, pending.size())));
        } else {
            context.getState().setPendingSearchResults(null);
        }
    }

    private void sendSearchResults(ClientContext context, List<FileMetadata> results, OutputStream out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(results.size());
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
            tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, m.type())); // Corrected tag name
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, m.sources().size())); // Ajout du nombre de sources

            Tag.writeList(itemBuf, tags);
            itemBuf.flip();
            byte[] itemData = new byte[itemBuf.remaining()];
            itemBuf.get(itemData);
            dos.write(itemData);
        }
        new Packet(Packet.PROTOCOL_ED2K, OpCode.SEARCH_RESULT.value, baos.toByteArray()).write(out, context.getState().isZlibSupported());
    }
}