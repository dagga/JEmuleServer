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

package org.jemule.core;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The clients this server refuses besides the IP filter, which its daily update rewrites:
 * <ul>
 * <li>by nickname ({@code bannedClientNicks}): a login with one of these nicknames, compared without case, is
 * refused, as eMule-Qt clients log in with {@code https://emule-qt.org} and share the made-up hashes of its
 * bridges;</li>
 * <li>by behaviour ({@code banClientsPublishingBannedHashes}): a client publishing a file of a banned hash prefix is
 * disconnected and its IP refused for {@link #BAN_MILLIS}, in memory only (a restart lifts it).</li>
 * </ul>
 * Each refusal is counted for the admin status.
 */
public final class ClientBans {
    /** How long the IP of a client publishing a made-up hash is refused. */
    public static final long BAN_MILLIS = 24 * 3_600_000L;
    /** Most IPs banned at once: beyond, the expired ones are dropped first, then the ban is not recorded. */
    static final int MAX_BANNED_IPS = 100_000;

    /** Expiry (epoch ms) of each IP banned for its publications. */
    private final Map<InetAddress, Long> bannedIps = new ConcurrentHashMap<>();
    private final LongAdder nickLoginsRefused = new LongAdder();
    private final LongAdder publishersBanned = new LongAdder();
    private final LongAdder connectionsRefused = new LongAdder();

    /** Whether {@code nickname} is one of {@code bannedNicks} (comma-separated, exact match without case). */
    public static boolean nickBanned(String nickname, String bannedNicks) {
        if (nickname == null || bannedNicks == null || bannedNicks.isBlank()) return false;
        String nick = nickname.strip().toLowerCase(Locale.ROOT);
        if (nick.isEmpty()) return false;
        for (String banned : bannedNicks.split(",")) {
            if (banned.strip().toLowerCase(Locale.ROOT).equals(nick)) return true;
        }
        return false;
    }

    /** Counts a login refused for its nickname. */
    public void nickLoginRefused() {
        nickLoginsRefused.increment();
    }

    /**
     * Refuses the IP of a client that published a file of a banned hash prefix for {@link #BAN_MILLIS} from
     * {@code now}. Counted.
     */
    public void banPublisher(InetAddress address, long now) {
        publishersBanned.increment();
        if (address == null) return;
        if (bannedIps.size() >= MAX_BANNED_IPS) {
            bannedIps.values().removeIf(expiry -> expiry <= now);
            if (bannedIps.size() >= MAX_BANNED_IPS) return;
        }
        bannedIps.put(address, now + BAN_MILLIS);
    }

    /** Whether {@code address} is refused at {@code now}; an expired ban is forgotten. */
    public boolean isBanned(InetAddress address, long now) {
        if (address == null || bannedIps.isEmpty()) return false;
        Long expiry = bannedIps.get(address);
        if (expiry == null) return false;
        if (expiry <= now) {
            bannedIps.remove(address, expiry);
            return false;
        }
        return true;
    }

    /** Checks a new connection, counting the refusal. */
    public boolean refuseConnection(InetAddress address, long now) {
        if (!isBanned(address, now)) return false;
        connectionsRefused.increment();
        return true;
    }

    /** The IPs banned now. */
    public int bannedIpCount(long now) {
        bannedIps.values().removeIf(expiry -> expiry <= now);
        return bannedIps.size();
    }

    /** Lifts every ban by IP. */
    public void clear() {
        bannedIps.clear();
    }

    public long nickLoginsRefused() {
        return nickLoginsRefused.sum();
    }

    public long publishersBanned() {
        return publishersBanned.sum();
    }

    public long connectionsRefused() {
        return connectionsRefused.sum();
    }
}
