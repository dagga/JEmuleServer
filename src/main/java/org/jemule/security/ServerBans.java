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

import org.jemule.core.DatabaseManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * eD2K servers banned by the administrator, by IPv4 address: all the ports of the address. A banned server is never
 * probed, verified, listed, searched, asked for sources or updated from, its UDP server traffic is dropped and its TCP
 * sessions refused; the ban wins over {@code serverPeers} and {@code relayServer}. Kept in memory, read by the UDP
 * thread and every client thread, and stored in the database (banned_servers) so that it survives a restart.
 */
public final class ServerBans {
    /** Longest reason the administrator may give; the database keeps up to 255 characters. */
    public static final int MAX_REASON_LENGTH = 240;

    /** A ban: the address in dotted form, the reason (empty when none), the time of the ban (epoch milliseconds). */
    public record Ban(String ip, String reason, long bannedAt) {
    }

    private final Map<InetAddress, Ban> bans = new ConcurrentHashMap<>();
    private volatile DatabaseManager db;

    public void setDatabaseManager(DatabaseManager db) {
        this.db = db;
    }

    /**
     * An IPv4 address written as four decimal numbers from 0 to 255, without any name resolution; null otherwise.
     * Neither a host name nor an IPv6 address can be banned: ed2k servers are only known by their IPv4 address.
     */
    public static Inet4Address parse(String ip) {
        if (ip == null) return null;
        String[] parts = ip.strip().split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) return null;
            for (int c = 0; c < part.length(); c++) {
                if (part.charAt(c) < '0' || part.charAt(c) > '9') return null;
            }
            int value = Integer.parseInt(part);
            if (value > 255) return null;
            bytes[i] = (byte) value;
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null; // never thrown for 4 bytes
        }
    }

    /**
     * Whether the address is banned. Java gives IPv4-mapped IPv6 addresses as IPv4 ones; another IPv6 address is
     * never banned.
     */
    public boolean isBanned(InetAddress address) {
        return address != null && !bans.isEmpty() && bans.containsKey(address);
    }

    /**
     * Bans a server for good: kept in memory and stored in the database. A server banned again gets the new reason
     * and date.
     *
     * @param reason the administrator's reason, null or blank for none; cut to {@link #MAX_REASON_LENGTH}
     */
    public Ban ban(Inet4Address address, String reason, long now) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() > MAX_REASON_LENGTH) text = text.substring(0, MAX_REASON_LENGTH);
        Ban ban = new Ban(address.getHostAddress(), text, now);
        bans.put(address, ban);
        DatabaseManager database = db;
        if (database != null) database.addBannedServer(ban.ip(), text.isEmpty() ? null : text, new Timestamp(now));
        return ban;
    }

    /** Lifts the ban of a server; false when it was not banned. */
    public boolean unban(Inet4Address address) {
        boolean wasBanned = bans.remove(address) != null;
        DatabaseManager database = db;
        if (database != null) database.removeBannedServer(address.getHostAddress());
        return wasBanned;
    }

    /**
     * Loads a ban stored earlier, without storing it again.
     *
     * @return the banned address, null when the stored address is not a valid IPv4 one
     */
    public Inet4Address addLoaded(DatabaseManager.BannedServer stored) {
        Inet4Address address = parse(stored.ip());
        if (address == null) return null;
        bans.put(address, new Ban(address.getHostAddress(), stored.reason() == null ? "" : stored.reason(),
                stored.bannedAt() == null ? 0 : stored.bannedAt().getTime()));
        return address;
    }

    /** Every ban, the most recent first. */
    public List<Ban> list() {
        return bans.values().stream()
                .sorted(Comparator.comparingLong(Ban::bannedAt).reversed().thenComparing(Ban::ip))
                .toList();
    }

    public int size() {
        return bans.size();
    }
}
