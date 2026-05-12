/*
 * JEmuleServer - An experimental eMule server.
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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles IP filtering by loading ipfilter.dat files.
 * Format: IP_START - IP_END , ACCESS_LEVEL , DESCRIPTION
 * IPs are stored as long values for efficient range checking.
 */
public class IPFilter {
    private static final Logger log = LoggerFactory.getLogger(IPFilter.class);

    private record IPRange(long start, long end, String description) implements Comparable<IPRange> {
        @Override
        public int compareTo(IPRange o) {
            return Long.compare(this.start, o.start);
        }
    }

    private final List<IPRange> blockedRanges = new ArrayList<>();

    /**
     * Loads blocked IP ranges from an ipfilter.dat file.
     *
     * @param filePath Path to the ipfilter.dat file.
     */
    public void loadFromFile(String filePath) {
        if (filePath == null) return;

        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                try {
                    IPRange range = parseLine(line);
                    if (range != null) {
                        blockedRanges.add(range);
                        count++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse ipfilter line: {} - {}", line, e.getMessage());
                }
            }
            Collections.sort(blockedRanges);
            log.info("Loaded {} IP filter ranges from {}", count, filePath);
        } catch (IOException e) {
            log.error("Could not read IP filter file {}: {}", filePath, e.getMessage());
        }
    }

    private IPRange parseLine(String line) {
        // Expected format: 001.002.003.004 - 001.002.003.007 , 000 , Blocked IP
        String[] parts = line.split(",", 3);
        if (parts.length < 1) return null;

        String rangePart = parts[0].trim();
        String description = parts.length > 2 ? parts[2].trim() : "Blocked";

        String[] ips = rangePart.split("-");
        if (ips.length != 2) return null;

        long start = ipToLong(ips[0].trim());
        long end = ipToLong(ips[1].trim());

        if (start == -1 || end == -1) return null;

        return new IPRange(start, end, description);
    }

    /**
     * Checks if an IP address is blocked.
     *
     * @param ip The IP address string.
     * @return True if blocked, false otherwise.
     */
    public boolean isBlocked(String ip) {
        if (blockedRanges.isEmpty()) return false;

        long ipLong = ipToLong(ip);
        if (ipLong == -1) return false;

        // Binary search for the range
        int idx = Collections.binarySearch(blockedRanges, new IPRange(ipLong, ipLong, ""));
        
        if (idx >= 0) return true; // Exact match on start

        // idx = -(insertion_point) - 1 => insertion_point = -(idx + 1)
        int insertionPoint = -(idx + 1);
        
        // The IP might be in the range that starts before the insertion point
        if (insertionPoint > 0) {
            IPRange candidate = blockedRanges.get(insertionPoint - 1);
            if (ipLong >= candidate.start && ipLong <= candidate.end) {
                return true;
            }
        }

        return false;
    }

    private long ipToLong(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] octets = address.getAddress();
            if (octets.length != 4) return -1; // IPv4 only for now

            long result = 0;
            for (byte octet : octets) {
                result <<= 8;
                result |= (octet & 0xFF);
            }
            return result;
        } catch (UnknownHostException e) {
            return -1;
        }
    }

    public int getFilterCount() {
        return blockedRanges.size();
    }
}
