package org.jemule.network.handler;

import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Handles OP_OFFERFILES (0x15), the only way an eMule client publishes its shared files:
 * <pre>&lt;count 4&gt;(&lt;hash 16&gt;&lt;ID 4&gt;&lt;port 2&gt;&lt;tagcount 4&gt;&lt;tags&gt;)[count]</pre>
 * (CSharedFileList::SendListToServer / CreateOfferedFilePacket). An empty offer (count = 0) is the
 * client's keep-alive. The format is always binary: there is no text variant in the ed2k protocol.
 */
public class PublishHandler {
    private static final Logger log = LoggerFactory.getLogger(PublishHandler.class);

    public void handlePublish(ClientContext context, OpCode op, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length < 4) {
            log.warn("Invalid publish request: {} bytes", data == null ? 0 : data.length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buf.getInt());
        if (count == 0) {
            log.debug("Empty {} from client {} (keep-alive)", op, context.getState().clientId());
        } else {
            log.info("Received {} with {} files ({} bytes)", op, count, data.length);
        }

        int rejected = 0;
        for (long i = 0; i < count; i++) {
            if (buf.remaining() < 16) {
                log.warn("Truncated {}: file {}/{} has no hash (remaining {} bytes)", op, i + 1, count, buf.remaining());
                break;
            }
            byte[] hashBytes = new byte[16];
            buf.get(hashBytes);
            boolean partial = false;

            if (op == OpCode.OFFER_FILES) {
                if (buf.remaining() < 6) {
                    log.warn("Truncated {}: file {}/{} has no ID/port", op, i + 1, count);
                    break;
                }
                // Client ID and port, or - for servers announcing SRV_TCPFLG_COMPRESSION - a status marker:
                // 0xFBFBFBFB/0xFBFB = complete file, 0xFCFCFCFC/0xFCFC = part file (CreateOfferedFilePacket).
                // The source's ID and port are always taken from the session.
                partial = buf.getInt() == 0xFCFCFCFC;
                buf.getShort();
            }

            List<Tag> tags;
            try {
                tags = Tag.readList(buf);
            } catch (RuntimeException e) {
                log.warn("Failed to read tags of file {}/{}: {}", i + 1, count, HandlerUtils.sanitize(e.getMessage()));
                break;
            }

            if (!publishFile(context, HandlerUtils.bytesToHashString(hashBytes), tags, partial)) {
                rejected++;
            }
        }

        if (rejected > 0) {
            // One message for the whole offer: a client may publish up to 200 files per packet
            LoginHandler.sendServerMessage(context, out, "WARNING : " + rejected + " shared file(s) rejected by the server (spam/fake/child abuse detection)");
        }
    }

    /**
     * Validates and indexes one offered file.
     *
     * @return false if the file was rejected by the child abuse filter or the fake file detector, true otherwise
     */
    private boolean publishFile(ClientContext context, String hash, List<Tag> tags, boolean partial) {
        String name = "";
        long sizeLo = 0;
        long sizeHi = 0;
        String type = "";

        for (Tag t : tags) {
            if (t.name().equals(Tag.NAME_FILENAME)) {
                name = String.valueOf(t.value());
            } else if (t.name().equals(Tag.NAME_FILESIZE) && t.isInt()) {
                // eMule compacts integers (UINT8/16/32) and they are always unsigned
                sizeLo = t.unsignedValue();
            } else if (t.name().equals(Tag.NAME_FILESIZE_HI) && t.isInt()) { // high part of a >4GB size
                sizeHi = t.unsignedValue();
            } else if (t.name().equals(Tag.NAME_FILETYPE)) {
                // integer with SRV_TCPFLG_TYPETAGINTEGER (e.g. 2), string otherwise (e.g. "Video")
                type = org.jemule.protocol.FileTypes.fromTag(t);
            }
        }
        long size = sizeHi != 0 ? (sizeHi << 32) | (sizeLo & 0xFFFFFFFFL) : sizeLo;

        log.debug("Offered file from client {}: name='{}' hash={} size={} type={}",
                context.getState().clientId(), HandlerUtils.sanitize(name), hash, size, type);

        if (!HandlerUtils.isValidFilename(name)) {
            log.warn("Invalid file name for hash {}: '{}'", hash, HandlerUtils.sanitize(name));
            return true;
        }
        if (size <= 0 || size > MAX_EMULE_FILE_SIZE) {
            log.warn("Invalid file size for '{}': {}", HandlerUtils.sanitize(name), size);
            return true;
        }
        if (context.getFileIndex().isChildAbuse(hash, name)) {
            log.warn("Child abuse file rejected from client {} (hash={})", context.getState().clientId(), hash);
            return false;
        }
        if (context.getState().publishedFilesCount().get() >= context.getConfig().maxFilesPerUser()) {
            log.warn("Publish quota reached for client {} ({} files)", context.getState().clientId(), context.getConfig().maxFilesPerUser());
            return true;
        }
        if (!context.getFileIndex().contains(hash) && context.getFileIndex().fileCount() >= context.getConfig().maxFiles()) {
            // A known file still gets the client as a new source; only new files are refused
            long now = System.currentTimeMillis();
            long last = lastFullIndexWarning.get();
            if (now - last >= 60_000 && lastFullIndexWarning.compareAndSet(last, now)) {
                log.warn("File index full ({} files): new files are refused", context.getConfig().maxFiles());
            }
            return true;
        }
        if (context.getFakeFileDetector() != null && context.getFakeFileDetector().isFake(hash, name, size)) {
            log.warn("Fake file detected and rejected: {} (hash={})", HandlerUtils.sanitize(name), hash);
            return false;
        }

        if (partial) {
            context.getState().partialHashes().add(hash);
        } else {
            context.getState().partialHashes().remove(hash);
        }
        FileMetadata meta = new FileMetadata(hash, name, size, type, tags);
        meta.sources().put(org.jemule.core.FileIndex.sourceKey(context.getState()), context.getState());
        context.getFileIndex().addFile(meta, FileSource.CLIENT_PUSH);
        context.getState().publishedHashes().add(hash);
        context.getState().publishedFilesCount().incrementAndGet();
        return true;
    }

    /** Time of the last "index full" warning: logged at most once a minute, not once per offered file. */
    private static final java.util.concurrent.atomic.AtomicLong lastFullIndexWarning = new java.util.concurrent.atomic.AtomicLong();

    /** MAX_EMULE_FILE_SIZE in opcodes.h: 2^38 = 256 GB. */
    private static final long MAX_EMULE_FILE_SIZE = 0x4000000000L;
}
