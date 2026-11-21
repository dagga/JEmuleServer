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

package org.jemule.network.ipv6;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * The IPv6 address one client connection announced, and what the server knows about it: whether the client reads
 * IPv6 source records, whether the address is being dialled back, and the verdict. Only an address the dial-back
 * reached is published.
 * <p>
 * The verdict is ST_IPV6_STATUS (0xAB) of OP_SERVERIDENT, a byte of bits read by aMule's ParseServerIPv6Verdict
 * (aMule master a6d6290be0, src/ServerIPv6Verdict.h): {@link #STATUS_HELD} {@code 0x01} address held,
 * {@link #STATUS_REACHABLE} {@code 0x02} reachable, {@link #STATUS_PROBED} {@code 0x04} dialled back. So {@code 0x01}
 * while the dial runs, {@code 0x07} when it reached the client, {@code 0x05} when it did not.
 */
public final class Ipv6Session {
    public static final int STATUS_HELD = 0x01;
    public static final int STATUS_REACHABLE = 0x02;
    public static final int STATUS_PROBED = 0x04;
    /** Dials per connection, the login's address included, as ed2k-server (ipv6_probe.rs MAX_DIALS). */
    public static final int MAX_DIALS = 4;

    /** What the server knows about the held address. */
    public enum Verdict { NONE, PROBING, REACHABLE, UNREACHABLE }

    /** What a new announcement asks the server to do. */
    public enum Action {
        /** Nothing changed for the client. */
        NONE,
        /** Dial the address back ({@link #dialTarget()}). */
        DIAL,
        /** The verdict changed without a dial: tell the client. */
        REPORT
    }

    /** A dial to make: the address, and the generation its verdict must still match. */
    public record Dial(byte[] address, long generation) {}

    private byte[] loginTag;
    private byte[] address;
    private Verdict verdict = Verdict.NONE;
    private boolean readsRecords;
    private int dials;
    private long generation;
    private boolean dialDue;

    /** CT_MOD_IP_V6 of OP_LOGINREQUEST, kept until the login is applied ({@link Ipv6SourceService#onLogin}). */
    public synchronized void setLoginTag(byte[] raw) {
        loginTag = raw == null ? null : raw.clone();
    }

    synchronized byte[] takeLoginTag() {
        byte[] raw = loginTag;
        loginTag = null;
        return raw;
    }

    /**
     * Applies an announced address: the login tag or OP_IPV6_UPDATE, 16 raw bytes. Rules of ed2k-server (README
     * "IPv6", ipv6_probe.rs) and aMule (docs/IPv6_Server_Sources.md):
     * <ul>
     *   <li>all zeros withdraw the address, and the records with it;</li>
     *   <li>any other address makes the client a reader of IPv6 records, but only a usable one is held;</li>
     *   <li>the address held is dialled back, the same address is dialled again only after a failure;</li>
     *   <li>at most {@link #MAX_DIALS} dials per connection: beyond, a new address is not published.</li>
     * </ul>
     */
    synchronized Action announce(byte[] raw, Predicate<byte[]> usable) {
        if (raw == null || raw.length != 16) return Action.NONE;
        if (isZero(raw)) {
            boolean changed = address != null || readsRecords;
            readsRecords = false;
            clear();
            return changed ? Action.REPORT : Action.NONE;
        }
        readsRecords = true;
        if (!usable.test(raw)) {
            boolean changed = address != null;
            clear();
            return changed ? Action.REPORT : Action.NONE;
        }
        if (Arrays.equals(raw, address) && verdict != Verdict.UNREACHABLE) return Action.NONE;
        address = raw.clone();
        generation++;
        dialDue = false;
        if (dials >= MAX_DIALS) {
            verdict = Verdict.UNREACHABLE;
            return Action.REPORT;
        }
        verdict = Verdict.PROBING;
        dialDue = true;
        return Action.DIAL;
    }

    private void clear() {
        address = null;
        verdict = Verdict.NONE;
        dialDue = false;
        generation++;
    }

    /** The dial due for the held address, counted against {@link #MAX_DIALS}; null if none is due. */
    synchronized Dial dialTarget() {
        if (!dialDue || address == null) return null;
        dialDue = false;
        dials++;
        return new Dial(address.clone(), generation);
    }

    /**
     * Records the verdict of the dial {@code generation}.
     *
     * @return false when the client announced something else meanwhile: that verdict is stale
     */
    synchronized boolean conclude(long generation, boolean reached) {
        if (generation != this.generation || verdict != Verdict.PROBING) return false;
        verdict = reached ? Verdict.REACHABLE : Verdict.UNREACHABLE;
        return true;
    }

    /** Whether the client reads IPv6 source records: it announced an address, and did not withdraw it. */
    public synchronized boolean readsRecords() {
        return readsRecords;
    }

    /** The address the dial-back reached, null if none: the only one ever published. */
    public synchronized byte[] publishedAddress() {
        return verdict == Verdict.REACHABLE ? address.clone() : null;
    }

    public synchronized Verdict verdict() {
        return verdict;
    }

    /** Dials made for this connection. */
    public synchronized int dials() {
        return dials;
    }

    /** ST_IPV6_STATUS for the held address, 0 when none is held (the tag is then not sent). */
    public synchronized int status() {
        return switch (verdict) {
            case NONE -> 0;
            case PROBING -> STATUS_HELD;
            case REACHABLE -> STATUS_HELD | STATUS_REACHABLE | STATUS_PROBED;
            case UNREACHABLE -> STATUS_HELD | STATUS_PROBED;
        };
    }

    private static boolean isZero(byte[] raw) {
        for (byte b : raw) if (b != 0) return false;
        return true;
    }
}
