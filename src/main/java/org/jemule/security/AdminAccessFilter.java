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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Application-level access filter for administration interfaces (CLI console and web panel).
 * Filters access based on allowed IPv4/IPv6 addresses, CIDR blocks, and optionally MAC addresses.
 */
public class AdminAccessFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminAccessFilter.class);
    private static final Pattern MAC_PATTERN = Pattern.compile("^[0-9A-Fa-f]{12}$");

    private final List<IpMatcher> allowedIps = new ArrayList<>();
    private final Set<String> allowedMacs = new HashSet<>();
    private final boolean requireMac;

    public interface MacResolver {
        String resolveMac(InetAddress address);
    }

    private final MacResolver macResolver;

    public AdminAccessFilter(String allowedIpsConfig, String allowedMacsConfig) {
        this(allowedIpsConfig, allowedMacsConfig, AdminAccessFilter::defaultResolveMac);
    }

    public AdminAccessFilter(String allowedIpsConfig, String allowedMacsConfig, MacResolver macResolver) {
        this.macResolver = macResolver != null ? macResolver : AdminAccessFilter::defaultResolveMac;
        parseIps(allowedIpsConfig);
        parseMacs(allowedMacsConfig);
        this.requireMac = !this.allowedMacs.isEmpty();
    }

    private void parseIps(String config) {
        if (config == null || config.trim().isEmpty()) {
            // Default to loopback only if empty (127.0.0.0/8 and ::1)
            try {
                allowedIps.add(new CidrMatcher(InetAddress.getByName("127.0.0.1"), 8));
                allowedIps.add(new ExactIpMatcher(InetAddress.getByName("::1")));
                allowedIps.add(new ExactIpMatcher(InetAddress.getLoopbackAddress()));
            } catch (Exception ignored) {
            }
            return;
        }

        String[] tokens = config.split("[,;\\s]+");
        for (String token : tokens) {
            token = token.trim();
            if (token.isEmpty()) continue;
            if (token.equals("*") || token.equalsIgnoreCase("all") || token.equals("0.0.0.0/0")) {
                allowedIps.add(new AnyIpMatcher());
                continue;
            }
            if (token.contains("/")) {
                // CIDR block
                try {
                    String[] parts = token.split("/", 2);
                    InetAddress network = InetAddress.getByName(parts[0]);
                    int prefix = Integer.parseInt(parts[1]);
                    allowedIps.add(new CidrMatcher(network, prefix));
                } catch (Exception e) {
                    log.warn("Invalid CIDR in adminAllowedIps: {}", token, e);
                }
            } else {
                try {
                    InetAddress addr = InetAddress.getByName(token);
                    allowedIps.add(new ExactIpMatcher(addr));
                } catch (Exception e) {
                    log.warn("Invalid IP address in adminAllowedIps: {}", token, e);
                }
            }
        }
    }

    private void parseMacs(String config) {
        if (config == null || config.trim().isEmpty()) {
            return;
        }
        String[] tokens = config.split("[,;\\s]+");
        for (String token : tokens) {
            String normalized = normalizeMac(token);
            if (normalized != null) {
                allowedMacs.add(normalized);
            } else {
                log.warn("Invalid MAC address in adminAllowedMacs: {}", token);
            }
        }
    }

    /**
     * Checks whether the client's IP address and (if configured) MAC address are allowed.
     */
    public boolean isAllowed(InetAddress clientAddress) {
        if (clientAddress == null) {
            return false;
        }

        // 1. Check IP filter
        boolean ipMatch = false;
        for (IpMatcher matcher : allowedIps) {
            if (matcher.matches(clientAddress)) {
                ipMatch = true;
                break;
            }
        }

        if (!ipMatch) {
            log.debug("Admin access denied for IP {} (IP not in whitelist)", clientAddress.getHostAddress());
            return false;
        }

        // 2. Check MAC filter if configured
        if (requireMac) {
            String clientMac = macResolver.resolveMac(clientAddress);
            if (clientMac == null) {
                log.debug("Admin access denied for IP {} (could not resolve MAC address)", clientAddress.getHostAddress());
                return false;
            }
            String normalized = normalizeMac(clientMac);
            if (normalized == null || !allowedMacs.contains(normalized)) {
                log.debug("Admin access denied for IP {} with MAC {} (MAC not in whitelist)",
                        clientAddress.getHostAddress(), clientMac);
                return false;
            }
        }

        return true;
    }

    public boolean isRequireMac() {
        return requireMac;
    }

    public Set<String> getAllowedMacs() {
        return Collections.unmodifiableSet(allowedMacs);
    }

    public static String normalizeMac(String rawMac) {
        if (rawMac == null) return null;
        String clean = rawMac.replaceAll("[:\\-\\.]", "").trim().toUpperCase(Locale.ROOT);
        if (MAC_PATTERN.matcher(clean).matches()) {
            return clean;
        }
        return null;
    }

    public static String formatMac(String normalizedMac) {
        if (normalizedMac == null || normalizedMac.length() != 12) return normalizedMac;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(normalizedMac, i, i + 2);
        }
        return sb.toString();
    }

    /**
     * Resolves the MAC address of an IP address using local network interfaces or OS ARP table.
     */
    public static String defaultResolveMac(InetAddress address) {
        if (address == null) return null;

        // 1. If loopback or local host interface
        try {
            if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null) {
                // Return hardware address of first active interface or loopback synthetic MAC
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface nif = interfaces.nextElement();
                        byte[] hw = nif.getHardwareAddress();
                        if (hw != null && hw.length == 6) {
                            return bytesToMac(hw);
                        }
                    }
                }
                return "00:00:00:00:00:00";
            }
        } catch (SocketException ignored) {
        }

        String ip = address.getHostAddress();

        // 2. Read Linux /proc/net/arp
        Path arpPath = Path.of("/proc/net/arp");
        if (Files.isReadable(arpPath)) {
            try (BufferedReader reader = Files.newBufferedReader(arpPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.trim().split("\\s+");
                    if (tokens.length >= 4 && tokens[0].equalsIgnoreCase(ip)) {
                        String mac = tokens[3];
                        if (!"00:00:00:00:00:00".equals(mac)) {
                            return mac;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback: try executing 'arp -an' or 'ip neigh'
        try {
            Process process = new ProcessBuilder("arp", "-an", ip).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(ip)) {
                        for (String part : line.split("\\s+")) {
                            if (normalizeMac(part) != null) {
                                return part;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String bytesToMac(byte[] bytes) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    private interface IpMatcher {
        boolean matches(InetAddress address);
    }

    private static class AnyIpMatcher implements IpMatcher {
        @Override
        public boolean matches(InetAddress address) {
            return true;
        }
    }

    private static class ExactIpMatcher implements IpMatcher {
        private final byte[] targetBytes;

        ExactIpMatcher(InetAddress address) {
            this.targetBytes = address.getAddress();
        }

        @Override
        public boolean matches(InetAddress address) {
            return Arrays.equals(targetBytes, address.getAddress());
        }
    }

    private static class CidrMatcher implements IpMatcher {
        private final byte[] networkBytes;
        private final int prefixLength;

        CidrMatcher(InetAddress network, int prefixLength) {
            this.networkBytes = network.getAddress();
            this.prefixLength = prefixLength;
        }

        @Override
        public boolean matches(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            if (addrBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < addrBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        }
    }
}
