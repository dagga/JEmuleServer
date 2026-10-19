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


package org.jemule.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IPv4 filter loaded from an eMule ipfilter.dat file: <code>start - end , level , description</code>.
 * <p>
 * Like eMule, a range blocks only if its access level is below {@link #FILTER_LEVEL}: ranges of level 100 or more
 * (often 127 or 255) are explicitly allowed. A line without level blocks. Overlapping and adjacent ranges are merged,
 * so that the lookup finds an address whatever the order and the nesting of the ranges in the file.
 */
public class IPFilter {
    private static final Logger log = LoggerFactory.getLogger(IPFilter.class);
    private static final double MAX_UPDATE_DIFFERENCE = 0.10;
    private static final int BINARY_MAGIC = 0x4A454631; // 'JEF1'
    private static final int BINARY_VERSION = 1;

    /** eMule's default IP filter level: ranges of a lower level are blocked. */
    public static final int FILTER_LEVEL = 100;

    private record IPRange(long start, long end) {}

    /** Disjoint blocked ranges sorted by start. Replaced as a whole, so that lookups never see a partial list. */
    private volatile List<IPRange> blockedRanges = List.of();

    /**
     * Loads blocked IP ranges from an ipfilter.dat file.
     * Checks for an up-to-date binary cache file (.bin) first for fast startup.
     *
     * @param filePath Path to the ipfilter.dat file.
     */
    public void loadFromFile(String filePath) {
        if (filePath == null) return;
        Path datPath = Path.of(filePath);
        if (!Files.exists(datPath)) {
            log.error("Could not read IP filter file {}: file does not exist", filePath);
            return;
        }

        Path binPath = getBinaryCachePath(filePath);
        List<IPRange> cached = readBinaryCache(binPath, datPath);
        if (cached != null) {
            blockedRanges = cached;
            log.info("Loaded {} blocking IP filter ranges from binary cache {}", cached.size(), binPath);
            return;
        }

        List<IPRange> ranges = new ArrayList<>();
        int allowed = 0;
        // ISO-8859-1 never fails on the descriptions, whatever their encoding; only the ASCII part is parsed
        try (BufferedReader br = Files.newBufferedReader(datPath, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = br.readLine()) != null) {
                int len = line.length();
                int startIdx = 0;
                while (startIdx < len && line.charAt(startIdx) <= ' ') startIdx++;
                while (len > startIdx && line.charAt(len - 1) <= ' ') len--;
                if (startIdx >= len) continue;

                if (line.charAt(startIdx) == '#' || (startIdx + 1 < len && line.charAt(startIdx) == '/' && line.charAt(startIdx + 1) == '/')) {
                    continue;
                }

                int firstComma = -1;
                int secondComma = -1;
                int dash = -1;
                for (int i = startIdx; i < len; i++) {
                    char c = line.charAt(i);
                    if (c == ',' && firstComma == -1) {
                        firstComma = i;
                    } else if (c == ',' && secondComma == -1) {
                        secondComma = i;
                    } else if (c == '-' && dash == -1 && firstComma == -1) {
                        dash = i;
                    }
                }

                if (dash == -1) {
                    log.warn("Failed to parse ipfilter line: {}", line);
                    continue;
                }

                long start = parseIpTrimmed(line, startIdx, dash);
                long end = parseIpTrimmed(line, dash + 1, firstComma != -1 ? firstComma : len);
                if (start == -1 || end == -1 || start > end) {
                    log.warn("Failed to parse ipfilter line: {}", line);
                    continue;
                }

                if (firstComma != -1) {
                    int level = parseLevel(line, firstComma + 1, secondComma != -1 ? secondComma : len);
                    if (level >= FILTER_LEVEL) {
                        allowed++;
                        continue;
                    }
                }

                ranges.add(new IPRange(start, end));
            }
        } catch (IOException e) {
            log.error("Could not read IP filter file {}: {}", filePath, e.getMessage());
            return;
        }

        blockedRanges = merge(ranges);
        log.info("Loaded {} blocking IP filter ranges from {} ({} after merging overlaps, {} allowed ranges ignored)",
                ranges.size(), filePath, blockedRanges.size(), allowed);
        writeBinaryCache(binPath, datPath, blockedRanges);
    }

    private static Path getBinaryCachePath(String filePath) {
        return Path.of(filePath + ".bin");
    }

    private static Path getBinaryCachePath(Path filePath) {
        return Path.of(filePath.toString() + ".bin");
    }

    private static List<IPRange> readBinaryCache(Path binPath, Path datPath) {
        if (!Files.exists(binPath) || !Files.exists(datPath)) return null;
        try {
            long datLastModified = Files.getLastModifiedTime(datPath).toMillis();
            long datSize = Files.size(datPath);
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(binPath), 65536))) {
                int magic = dis.readInt();
                if (magic != BINARY_MAGIC) return null;
                int version = dis.readInt();
                if (version != BINARY_VERSION) return null;
                long cachedDatModified = dis.readLong();
                long cachedDatSize = dis.readLong();
                if (cachedDatModified != datLastModified || cachedDatSize != datSize) {
                    return null;
                }
                int count = dis.readInt();
                if (count < 0 || count > 10_000_000) return null;
                List<IPRange> ranges = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    long start = dis.readLong();
                    long end = dis.readLong();
                    ranges.add(new IPRange(start, end));
                }
                return List.copyOf(ranges);
            }
        } catch (Exception e) {
            log.warn("Failed to read binary IP filter cache {}: {}", binPath, e.getMessage());
            return null;
        }
    }

    private static void writeBinaryCache(Path binPath, Path datPath, List<IPRange> ranges) {
        if (ranges == null || !Files.exists(datPath)) return;
        try {
            long datLastModified = Files.getLastModifiedTime(datPath).toMillis();
            long datSize = Files.size(datPath);
            Path parent = binPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tempFile = Files.createTempFile(parent != null ? parent : Path.of("."), "ipfilter-bin-", ".tmp");
            try {
                try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempFile), 65536))) {
                    dos.writeInt(BINARY_MAGIC);
                    dos.writeInt(BINARY_VERSION);
                    dos.writeLong(datLastModified);
                    dos.writeLong(datSize);
                    dos.writeInt(ranges.size());
                    for (IPRange r : ranges) {
                        dos.writeLong(r.start());
                        dos.writeLong(r.end());
                    }
                    dos.flush();
                }
                try {
                    Files.move(tempFile, binPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, binPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.warn("Could not write binary IP filter cache {}: {}", binPath, e.getMessage());
        }
    }

    private static int parseLevel(CharSequence line, int start, int end) {
        while (start < end && line.charAt(start) <= ' ') start++;
        while (end > start && line.charAt(end - 1) <= ' ') end--;
        if (start >= end) return 0;
        int val = 0;
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                val = val * 10 + (c - '0');
                if (val > 1_000_000) return 0;
            } else {
                return 0; // unreadable level: block, like a line without level
            }
        }
        return val;
    }

    private static List<IPRange> merge(List<IPRange> ranges) {
        ranges.sort(Comparator.comparingLong(IPRange::start));
        List<IPRange> merged = new ArrayList<>();
        for (IPRange r : ranges) {
            if (!merged.isEmpty() && r.start() <= merged.getLast().end() + 1) {
                IPRange last = merged.removeLast();
                merged.add(new IPRange(last.start(), Math.max(last.end(), r.end())));
            } else {
                merged.add(r);
            }
        }
        return List.copyOf(merged);
    }

    synchronized boolean applyUpdate(Path destination, List<String> lines, String source) throws IOException {
        List<IPRange> updated = parse(lines);
        if (updated.isEmpty()) {
            throw new IllegalArgumentException("Downloaded filter contains no blocking ranges");
        }

        List<IPRange> previous = blockedRanges;
        Set<IPRange> changes = new HashSet<>(previous);
        changes.addAll(updated);
        Set<IPRange> common = new HashSet<>(previous);
        common.retainAll(updated);
        int changed = changes.size() - common.size();
        double difference = previous.isEmpty() ? 0 : (double) changed / Math.max(previous.size(), updated.size());
        if (difference >= MAX_UPDATE_DIFFERENCE) {
            appendTrace(destination, "Rejected update from " + source + ": difference " + percent(difference)
                    + " is not below 10% (" + previous.size() + " current ranges, " + updated.size()
                    + " downloaded ranges, " + changed + " changed ranges)");
            return false;
        }

        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("IP filter destination has no parent directory: " + destination);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "ipfilter-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.ISO_8859_1);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        blockedRanges = updated;
        writeBinaryCache(getBinaryCachePath(destination), destination, updated);
        log.info("Applied IP filter update from {}: {} blocking ranges ({}% difference)",
                source, blockedRanges.size(), percent(difference));
        return true;
    }

    void traceRejectedUpdate(Path destination, String detail) {
        appendTrace(destination, detail);
    }

    private static String percent(double difference) {
        return String.format(java.util.Locale.ROOT, "%.2f", difference * 100);
    }

    private static void appendTrace(Path destination, String detail) {
        Path trace = destination.resolveSibling(destination.getFileName() + ".trace");
        try {
            Path parent = trace.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(trace, java.time.Instant.now() + " " + detail + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Could not write IP filter update trace {}: {}", trace, e.getMessage());
        }
    }

    private static List<IPRange> parse(List<String> lines) {
        List<IPRange> ranges = new ArrayList<>(lines.size());
        for (String input : lines) {
            int len = input.length();
            int startIdx = 0;
            while (startIdx < len && input.charAt(startIdx) <= ' ') startIdx++;
            while (len > startIdx && input.charAt(len - 1) <= ' ') len--;
            if (startIdx >= len) continue;

            if (input.charAt(startIdx) == '#' || (startIdx + 1 < len && input.charAt(startIdx) == '/' && input.charAt(startIdx + 1) == '/')) {
                continue;
            }

            int firstComma = -1;
            int secondComma = -1;
            int dash = -1;
            for (int i = startIdx; i < len; i++) {
                char c = input.charAt(i);
                if (c == ',' && firstComma == -1) {
                    firstComma = i;
                } else if (c == ',' && secondComma == -1) {
                    secondComma = i;
                } else if (c == '-' && dash == -1 && firstComma == -1) {
                    dash = i;
                }
            }

            if (dash == -1) {
                throw new IllegalArgumentException("Invalid IP filter line: " + input);
            }

            long start = parseIpTrimmed(input, startIdx, dash);
            long end = parseIpTrimmed(input, dash + 1, firstComma != -1 ? firstComma : len);
            if (start == -1 || end == -1 || start > end) {
                throw new IllegalArgumentException("Invalid IP filter line: " + input);
            }

            if (firstComma != -1) {
                int level = parseLevel(input, firstComma + 1, secondComma != -1 ? secondComma : len);
                if (level >= FILTER_LEVEL) {
                    continue;
                }
            }

            ranges.add(new IPRange(start, end));
        }
        return merge(ranges);
    }

    /**
     * Checks if an IP address is blocked.
     *
     * @param address The IP address.
     * @return True if blocked, false otherwise.
     */
    public boolean isBlocked(java.net.InetAddress address) {
        if (address == null) return false;
        return isBlocked(address.getHostAddress());
    }

    /**
     * Checks if an IP address is blocked.
     *
     * @param ip The IP address string (dotted IPv4; other addresses are never blocked).
     * @return True if blocked, false otherwise.
     */
    public boolean isBlocked(String ip) {
        List<IPRange> ranges = blockedRanges;
        if (ranges.isEmpty()) return false;

        long ipLong = ipToLong(ip);
        if (ipLong == -1) return false;

        // Last range starting at or before the address
        int lo = 0, hi = ranges.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ranges.get(mid).start() <= ipLong) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found >= 0 && ipLong <= ranges.get(found).end();
    }

    private static long parseIpStrict(CharSequence s, int start, int end) {
        if (s == null || start >= end) return -1;
        long result = 0;
        int octet = 0;
        int octetLen = 0;
        int dots = 0;

        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (dots >= 3 || octetLen == 0 || octetLen > 3 || octet > 255) return -1;
                result = (result << 8) | octet;
                octet = 0;
                octetLen = 0;
                dots++;
            } else if (c >= '0' && c <= '9') {
                octet = octet * 10 + (c - '0');
                octetLen++;
            } else {
                return -1;
            }
        }
        if (dots != 3 || octetLen == 0 || octetLen > 3 || octet > 255) return -1;
        return (result << 8) | octet;
    }

    private static long parseIpTrimmed(CharSequence s, int start, int end) {
        if (s == null || start >= end) return -1;
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        return parseIpStrict(s, start, end);
    }

    /**
     * Parses a dotted IPv4 address, zero-padded or not ("001.002.003.004"), without any DNS lookup.
     *
     * @return the address as an unsigned 32-bit value, or -1 if it is not a dotted IPv4 address
     */
    static long ipToLong(String ip) {
        if (ip == null) return -1;
        return parseIpStrict(ip, 0, ip.length());
    }

    public int getFilterCount() {
        return blockedRanges.size();
    }

    /**
     * Returns the normalized blocked IPv4 ranges in dotted-decimal form.
     */
    public List<String> getBlockedRanges() {
        return getBlockedRanges(Integer.MAX_VALUE);
    }

    /**
     * Returns at most {@code limit} normalized blocked ranges.
     */
    public List<String> getBlockedRanges(int limit) {
        if (limit <= 0) return List.of();
        List<String> result = new ArrayList<>(Math.min(limit, blockedRanges.size()));
        for (IPRange range : blockedRanges) {
            if (result.size() == limit) break;
            result.add(formatIp(range.start()) + " - " + formatIp(range.end()));
        }
        return List.copyOf(result);
    }

    private static String formatIp(long value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
