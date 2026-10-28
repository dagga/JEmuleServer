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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * IPv4 filter loaded from an eMule ipfilter.dat file: <code>start - end , level , description</code>.
 * <p>
 * Like eMule, a range blocks only if its access level is below {@link #FILTER_LEVEL}: ranges of level 100 or more
 * (often 127 or 255) are explicitly allowed. A line without level blocks. Overlapping and adjacent ranges are merged,
 * so that the lookup finds an address whatever the order and the nesting of the ranges in the file.
 * <p>
 * The eD2K servers are checked apart ({@link #isServerBlocked}), without the ranges of the hosting providers: lists made
 * for eMule clients block whole providers ("hosting", "Cloud", "cloudflare:AS..."), where nearly every eD2K server
 * runs. A range whose description also names a hostile activity (anti-P2P, phishing, fraud, fake servers...) still
 * blocks the servers.
 */
public class IPFilter {
    private static final Logger log = LoggerFactory.getLogger(IPFilter.class);
    private static final double MAX_UPDATE_DIFFERENCE = 0.10;
    private static final int BINARY_MAGIC = 0x4A454631; // 'JEF1'
    private static final int BINARY_VERSION = 2;
    /** A description naming a hosting provider. */
    private static final java.util.regex.Pattern HOSTING =
            java.util.regex.Pattern.compile("hosting|cloud", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** A description naming a hostile activity: the range blocks the servers of a hosting provider too. */
    private static final java.util.regex.Pattern HOSTILE = java.util.regex.Pattern.compile(
            "p2p|phish|fraud|trojan|fake|spam|ddos|malware|spy|hoax|virus|botnet|scam",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** eMule's default IP filter level: ranges of a lower level are blocked. */
    public static final int FILTER_LEVEL = 100;

    private record IPRange(long start, long end) {}

    /** The blocking ranges of a file, merged: all of them, and those that block the servers. */
    private record Parsed(List<IPRange> all, List<IPRange> servers) {}

    /** Disjoint blocked ranges sorted by start. Replaced as a whole, so that lookups never see a partial list. */
    private volatile List<IPRange> blockedRanges = List.of();
    /** {@link #blockedRanges} without the hosting providers, for the eD2K servers. */
    private volatile List<IPRange> serverRanges = List.of();

    /** Whether a range description names a hosting provider without any hostile activity. */
    static boolean hostingProvider(CharSequence description) {
        return HOSTING.matcher(description).find() && !HOSTILE.matcher(description).find();
    }

    /** Whether the range of a line blocks the servers: not a hosting provider's. */
    private static boolean blocksServers(CharSequence line, int secondComma, int len) {
        return secondComma == -1 || !hostingProvider(line.subSequence(secondComma + 1, len));
    }

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
        Parsed cached = readBinaryCache(binPath, datPath);
        if (cached != null) {
            blockedRanges = cached.all();
            serverRanges = cached.servers();
            log.info("Loaded {} blocking IP filter ranges from binary cache {} ({} for the servers)", cached.all().size(),
                    binPath, cached.servers().size());
            return;
        }

        List<IPRange> ranges = new ArrayList<>();
        List<IPRange> forServers = new ArrayList<>();
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
                if (blocksServers(line, secondComma, len)) forServers.add(new IPRange(start, end));
            }
        } catch (IOException e) {
            log.error("Could not read IP filter file {}: {}", filePath, e.getMessage());
            return;
        }

        Parsed parsed = new Parsed(merge(ranges), merge(forServers));
        blockedRanges = parsed.all();
        serverRanges = parsed.servers();
        log.info("Loaded {} blocking IP filter ranges from {} ({} after merging overlaps, {} allowed ranges ignored, "
                + "{} hosting provider ranges not applied to the servers)", ranges.size(), filePath,
                blockedRanges.size(), allowed, ranges.size() - forServers.size());
        writeBinaryCache(binPath, datPath, parsed);
    }

    private static Path getBinaryCachePath(String filePath) {
        return Path.of(filePath + ".bin");
    }

    private static Path getBinaryCachePath(Path filePath) {
        return Path.of(filePath.toString() + ".bin");
    }

    private static Parsed readBinaryCache(Path binPath, Path datPath) {
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
                List<IPRange> all = readRanges(dis);
                List<IPRange> servers = all == null ? null : readRanges(dis);
                return servers == null ? null : new Parsed(all, servers);
            }
        } catch (Exception e) {
            log.warn("Failed to read binary IP filter cache {}: {}", binPath, e.getMessage());
            return null;
        }
    }

    private static List<IPRange> readRanges(DataInputStream dis) throws IOException {
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

    private static void writeBinaryCache(Path binPath, Path datPath, Parsed parsed) {
        if (parsed == null || !Files.exists(datPath)) return;
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
                    for (List<IPRange> ranges : List.of(parsed.all(), parsed.servers())) {
                        dos.writeInt(ranges.size());
                        for (IPRange r : ranges) {
                            dos.writeLong(r.start());
                            dos.writeLong(r.end());
                        }
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
        Parsed parsed = parse(lines);
        List<IPRange> updated = parsed.all();
        if (updated.isEmpty()) {
            throw new IllegalArgumentException("Downloaded filter contains no blocking ranges");
        }

        List<IPRange> previous = blockedRanges;
        int changed = previous.size() + updated.size() - 2 * countCommon(previous, updated);
        double difference = previous.isEmpty() ? 0 : (double) changed / Math.max(previous.size(), updated.size());
        if (difference >= MAX_UPDATE_DIFFERENCE) {
            appendTrace(destination, "Rejected update from " + source + ": difference " + percent(difference)
                    + " is not below 10% (" + previous.size() + " current ranges, " + updated.size()
                    + " downloaded ranges, " + changed + " changed ranges)");
            return false;
        }

        if (changed == 0 && Files.exists(destination)) {
            // Same ranges: keep the file and only record the check, which postpones the next download
            Files.setLastModifiedTime(destination, FileTime.fromMillis(System.currentTimeMillis()));
            writeBinaryCache(getBinaryCachePath(destination), destination, new Parsed(previous, serverRanges));
            log.info("IP filter from {} is unchanged: {} blocking ranges", source, previous.size());
            return true;
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
        serverRanges = parsed.servers();
        writeBinaryCache(getBinaryCachePath(destination), destination, parsed);
        log.info("Applied IP filter update from {}: {} blocking ranges ({}% difference)",
                source, blockedRanges.size(), percent(difference));
        return true;
    }

    /**
     * Counts the ranges present in both lists, which are sorted by start and disjoint (see {@link #merge(List)}).
     */
    private static int countCommon(List<IPRange> a, List<IPRange> b) {
        int common = 0;
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            IPRange x = a.get(i);
            IPRange y = b.get(j);
            if (x.equals(y)) {
                common++;
                i++;
                j++;
            } else if (x.start() < y.start() || (x.start() == y.start() && x.end() < y.end())) {
                i++;
            } else {
                j++;
            }
        }
        return common;
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

    private static Parsed parse(List<String> lines) {
        List<IPRange> ranges = new ArrayList<>(lines.size());
        List<IPRange> forServers = new ArrayList<>(lines.size());
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
            if (blocksServers(input, secondComma, len)) forServers.add(new IPRange(start, end));
        }
        return new Parsed(merge(ranges), merge(forServers));
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
        return blocked(blockedRanges, ip);
    }

    /**
     * Checks if an eD2K server is blocked: as {@link #isBlocked}, without the ranges of the hosting providers.
     */
    public boolean isServerBlocked(java.net.InetAddress address) {
        return address != null && blocked(serverRanges, address.getHostAddress());
    }

    private static boolean blocked(List<IPRange> ranges, String ip) {
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

    /** Removes every blocked range, disabling the filter until the next load. */
    public void clear() {
        blockedRanges = List.of();
        serverRanges = List.of();
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
