package org.jemule.network.handler;

import org.jemule.core.ClientState;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.OfferFilesSession;
import org.jemule.core.OfferFilesV1;
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
 * <p>
 * Limits, as on Lugdunum: an offer declaring {@link #HARD_FILES} files or more closes the connection
 * (ST_HARDFILES), and a client gets at most {@code maxFilesPerUser} distinct files indexed (ST_SOFTFILES). An
 * OFFERFILES v1 connection is also held to its batch size and pace (doc/offerfiles-v1.md).
 */
public class PublishHandler {
    private static final Logger log = LoggerFactory.getLogger(PublishHandler.class);

    /** ST_HARDFILES: an OP_OFFERFILES declaring this many files or more is refused and the connection closed. */
    public static final int HARD_FILES = 2_000_000;

    /** What became of one offered file. */
    private enum Outcome { INDEXED, IGNORED, REJECTED, OVER_SOFT_LIMIT }

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

        ClientState state = context.getState();
        OfferFilesSession v1 = state.offerFilesSession();
        OfferFilesV1 offerFiles = context.getOfferFilesV1();

        // Every connection: the per-packet boundary is checked on the declared count, before reading any file
        int hardFiles = v1 != null ? v1.hardFiles() : HARD_FILES;
        if (count >= hardFiles) {
            log.warn("Client {} offered {} files, at least ST_HARDFILES ({}): disconnected", state.clientId(), count, hardFiles);
            offerFiles.hardLimitReject();
            context.disconnect();
            return;
        }

        if (v1 != null && count > 0) {
            if (count > v1.batchMax()) {
                // Not indexed, the session stays up: a disconnect would only bring the same offer back
                offerFiles.oversizedBatch();
                long now = System.currentTimeMillis();
                long last = lastOversizeWarning.get();
                if (now - last >= 60_000 && lastOversizeWarning.compareAndSet(last, now)) {
                    log.warn("OFFERFILES v1 client {} offered {} files, more than offerfiles_batch_max ({}): batch ignored",
                            state.clientId(), count, v1.batchMax());
                }
                if (v1.oversizeReported().compareAndSet(false, true)) {
                    LoginHandler.sendServerMessage(context, out, "WARNING : " + count + " shared file(s) not published: "
                            + "OFFERFILES v1 allows at most " + v1.batchMax() + " files per packet");
                }
                return;
            }
            try {
                offerFiles.awaitTurn(v1, count, context.getConfig().offerFiles().globalRecordsPerSec());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (context.getSocket().isClosed()) {
                return; // disconnected while waiting: nothing to index for a gone client
            }
        }

        int rejected = 0;
        int overSoftLimit = 0;
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

            switch (publishFile(context, HandlerUtils.bytesToHashString(hashBytes), tags, partial)) {
                case REJECTED -> rejected++;
                case OVER_SOFT_LIMIT -> overSoftLimit++;
                default -> { }
            }
        }

        if (rejected > 0) {
            // One message for the whole offer: a client may publish up to 200 files per packet
            LoginHandler.sendServerMessage(context, out, "WARNING : " + rejected + " shared file(s) rejected by the server (spam/fake/child abuse detection)");
        }
        if (overSoftLimit > 0) {
            int softFiles = softFiles(context);
            offerFiles.softLimitIgnored(overSoftLimit);
            log.warn("Client {} reached its quota of {} files: {} new file(s) ignored", state.clientId(), softFiles, overSoftLimit);
            if (state.softLimitReported().compareAndSet(false, true)) {
                // Lugdunum's message, once per connection
                LoginHandler.sendServerMessage(context, out, "WARNING : This server accepts " + softFiles
                        + " shares per client. Some of your shares are ignored.");
            }
        }
    }

    /** ST_SOFTFILES of the connection: the value advertised at login for OFFERFILES v1, the live setting otherwise. */
    private static int softFiles(ClientContext context) {
        OfferFilesSession v1 = context.getState().offerFilesSession();
        return v1 != null ? v1.softFiles() : context.getConfig().maxFilesPerUser();
    }

    /**
     * Validates and indexes one offered file.
     *
     * @return REJECTED by the child abuse filter or the fake file detector, OVER_SOFT_LIMIT if a new file would exceed
     *         the client's quota, IGNORED if invalid or the index is full, INDEXED otherwise
     */
    private Outcome publishFile(ClientContext context, String hash, List<Tag> tags, boolean partial) {
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
            return Outcome.IGNORED;
        }
        if (size <= 0 || size > MAX_EMULE_FILE_SIZE) {
            log.warn("Invalid file size for '{}': {}", HandlerUtils.sanitize(name), size);
            return Outcome.IGNORED;
        }
        if (context.getFileIndex().isChildAbuse(hash, name)) {
            log.warn("Child abuse file rejected from client {} (hash={})", context.getState().clientId(), hash);
            return Outcome.REJECTED;
        }
        // The quota counts distinct files: re-offering a file the client already shares (rename, completed download)
        // refreshes its source and is always accepted
        if (!context.getState().publishedHashes().contains(hash)
                && context.getState().publishedHashes().size() >= softFiles(context)) {
            return Outcome.OVER_SOFT_LIMIT;
        }
        if (!context.getFileIndex().contains(hash) && (context.getFileIndex().fileCount() >= context.getConfig().maxFiles()
                || context.getFileIndex().isFull())) {
            // A known file still gets the client as a new source; only new files are refused
            long now = System.currentTimeMillis();
            long last = lastFullIndexWarning.get();
            if (now - last >= 60_000 && lastFullIndexWarning.compareAndSet(last, now)) {
                log.warn("File index full ({} files, maxFiles {}, or heap full): new files are refused",
                        context.getFileIndex().fileCount(), context.getConfig().maxFiles());
            }
            return Outcome.IGNORED;
        }
        if (context.getFakeFileDetector() != null && context.getFakeFileDetector().isFake(hash, name, size)) {
            log.warn("Fake file detected and rejected: {} (hash={})", HandlerUtils.sanitize(name), hash);
            return Outcome.REJECTED;
        }

        if (partial) {
            context.getState().partialHashes().add(hash);
        } else {
            context.getState().partialHashes().remove(hash);
        }
        FileMetadata meta = new FileMetadata(hash, name, size, type, tags);
        meta.sources().put(org.jemule.core.FileIndex.sourceKey(context.getState()), context.getState());
        context.getFileIndex().addFile(meta, context.getState().offerFilesSession() != null ? FileSource.CLIENT_PUSH_V1 : FileSource.CLIENT_PUSH);
        if (context.getState().publishedHashes().add(hash)) {
            context.getState().publishedFilesCount().incrementAndGet();
        }
        return Outcome.INDEXED;
    }

    /** Time of the last "index full" warning: logged at most once a minute, not once per offered file. */
    private static final java.util.concurrent.atomic.AtomicLong lastFullIndexWarning = new java.util.concurrent.atomic.AtomicLong();

    /** Time of the last oversized OFFERFILES v1 batch warning: logged at most once a minute. */
    private static final java.util.concurrent.atomic.AtomicLong lastOversizeWarning = new java.util.concurrent.atomic.AtomicLong();

    /** MAX_EMULE_FILE_SIZE in opcodes.h: 2^38 = 256 GB. */
    private static final long MAX_EMULE_FILE_SIZE = 0x4000000000L;
}
