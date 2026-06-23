package org.jemule.network.handler;

import org.jemule.core.FileMetadata;
import org.jemule.network.Packet;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class PublishHandler {
    private static final Logger log = LoggerFactory.getLogger(PublishHandler.class);

    public void handlePublish(ClientContext context, OpCode op, byte[] data, OutputStream out) throws IOException {
        if (data == null || data.length == 0) {
            log.warn("Invalid publish request: empty data");
            return;
        }

        log.info("Received {} packet with {} bytes of data", op, data.length);

        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data[0] < 32) { // Heuristic to guess if it's binary format
                int count = 0;
                try {
                    count = buf.getInt();
                } catch (BufferUnderflowException e) {
                    log.warn("Failed to read file count from PUBLISH packet: {}", e.getMessage());
                    return;
                }
                log.info("Detected binary PUBLISH format with {} files", count);
                for (int i = 0; i < count; i++) {
                    if (buf.remaining() < 16) {
                        log.warn("Buffer underflow before reading hash for file {}. Remaining: {}", i, buf.remaining());
                        break;
                    }
                    byte[] hashBytes = new byte[16];
                    buf.get(hashBytes);

                    if (op == OpCode.OFFER_FILES) {
                        if (buf.remaining() < 6) {
                            log.warn("Buffer underflow before reading client ID/Port for file {}. Remaining: {}", i, buf.remaining());
                            break;
                        }
                        buf.getInt();   // FileID / ClientID
                        buf.getShort(); // FilePort / ClientPort
                    }

                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashBytes) sb.append(String.format("%02x", b));
                    String hash = sb.toString();

                    List<Tag> tags = null;
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Before reading tags for file {}: buf.position={}, buf.remaining={}, raw_data={}",
                                    i, buf.position(), buf.remaining(), HandlerUtils.bytesToHex(Arrays.copyOfRange(data, buf.position(), data.length)));
                        }
                        tags = Tag.readList(buf);
                        if (tags.isEmpty() && i < count - 1) {
                            // If we failed to read any tags for a file in the middle of a multi-file packet, 
                            // we are probably misaligned and cannot reliably continue.
                            log.warn("Empty tag list read for file {}/{} - alignment likely lost", i + 1, count);
                            break;
                        }
                    } catch (BufferUnderflowException e) {
                        log.warn("BufferUnderflowException while reading tags for file {}. Raw data around error: {}",
                                i, HandlerUtils.bytesToHex(Arrays.copyOfRange(data, Math.max(0, buf.position() - 32), Math.min(data.length, buf.position() + 32))));
                        log.warn("Failed to read tags for file {}: {}", i, e.getMessage());
                        break;
                    } catch (Exception e) {
                        log.warn("Failed to read tags for file {}: {}", i, e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                        break;
                    }
                    String name = "";
                    long size = 0;
                    String type = "";

                    for (Tag t : tags) {
                        if (t.name().equals(Tag.NAME_FILENAME)) {
                            name = String.valueOf(t.value());
                        } else if (t.name().equals(Tag.NAME_FILESIZE)) {
                            size = ((Number) t.value()).longValue();
                        } else if (t.name().equals(Tag.NAME_FILESIZE_HI)) { // Handle high part of 64-bit size
                            size = (size & 0xFFFFFFFFL) | (((Number) t.value()).longValue() << 32);
                        } else if (t.name().equals(Tag.NAME_FILETYPE)) {
                            type = String.valueOf(t.value());
                        }
                    }

                    log.info("PUBLISH attempt from client {}: name='{}' hash={} size={} type={}",
                            context.getState() != null ? context.getState().clientId() : -1,
                            HandlerUtils.sanitize(name), hash, size, type);

                    if (HandlerUtils.isValidHash(hash) && HandlerUtils.isValidFilename(name) && context.getState().publishedFilesCount().get() < context.getConfig().maxFilesPerUser()) {
                        if (context.getFakeFileDetector().isFake(hash, name, size)) {
                            log.warn("Fake file detected and rejected (binary): {} (hash={})", HandlerUtils.sanitize(name), hash);
                            LoginHandler.sendServerMessage(context, out, "File rejected (spam/malicious detected): " + HandlerUtils.sanitize(name));
                            continue;
                        }
                        if (size < 0 || size > 100_000_000_000L) {
                            log.warn("Invalid file size for PUBLISH (binary): {}", size);
                            continue;
                        }
                        FileMetadata meta = new FileMetadata(hash, name, size, type, tags);
                        meta.sources().put(String.valueOf(context.getState().clientId()), context.getState());
                        context.getFileIndex().addFile(meta);
                        context.getState().publishedFilesCount().incrementAndGet();
                        log.info("Published (binary): {} - Total files in index: {}", HandlerUtils.sanitize(name), context.getFileIndex().fileCount());
                    } else {
                        log.warn("Invalid publish data (binary): hash={}, name={}, quota={}/{}",
                                hash, HandlerUtils.sanitize(name), context.getState().publishedFilesCount().get(), context.getConfig().maxFilesPerUser());
                    }
                }
            } else {
                String raw = new String(data, StandardCharsets.UTF_8);
                log.info("Detected text PUBLISH format: {}", HandlerUtils.sanitize(raw));
                String[] p = raw.split("\\|");
                if (p.length >= 4) {
                    String hash = p[0].trim();
                    String name = p[1].trim();
                    String sizeStr = p[2].trim();
                    String type = p[3].trim();

                    log.info("PUBLISH attempt from client {}: name='{}' hash={} size={} type={}",
                            context.getState() != null ? context.getState().clientId() : -1,
                            HandlerUtils.sanitize(name), hash, sizeStr, type);

                    if (HandlerUtils.isValidHash(hash) && HandlerUtils.isValidFilename(name) && context.getState().publishedFilesCount().get() < context.getConfig().maxFilesPerUser()) {
                        try {
                            long size = Long.parseLong(sizeStr);
                            if (context.getFakeFileDetector().isFake(hash, name, size)) {
                                log.warn("Fake file detected and rejected (text): {} (hash={})", HandlerUtils.sanitize(name), hash);
                                LoginHandler.sendServerMessage(context, out, "File rejected (spam/malicious detected): " + HandlerUtils.sanitize(name));
                                return;
                            }
                            if (size >= 0 && size <= 100_000_000_000L) {
                                FileMetadata meta = new FileMetadata(hash, name, size, type);
                                meta.sources().put(String.valueOf(context.getState().clientId()), context.getState());
                                context.getFileIndex().addFile(meta);
                                context.getState().publishedFilesCount().incrementAndGet();
                                log.info("Published (text): {} - Total files in index: {}", HandlerUtils.sanitize(name), context.getFileIndex().fileCount());
                            } else {
                                log.warn("Invalid file size for PUBLISH (text): {}", size);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Invalid size format for PUBLISH (text): {}", HandlerUtils.sanitize(sizeStr));
                        }
                    } else {
                        log.warn("Invalid publish data (text): hash={}, name={}, quota={}/{}",
                                hash, HandlerUtils.sanitize(name), context.getState().publishedFilesCount().get(), context.getConfig().maxFilesPerUser());
                    }
                } else {
                    log.warn("Invalid text format - expected at least 4 pipe-separated fields, got {}", p.length);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse PUBLISH_FILES: {}", HandlerUtils.sanitize(e.getMessage()), e);
        }

        new Packet(Packet.PROTOCOL_ED2K, OpCode.PUBLISH_ACK.value, new byte[0]).write(out, context.getState().isZlibSupported());
    }
}