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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hash prefixes of files that do not exist on eD2K ({@code bannedHashPrefixes}): an MD4 hash is spread evenly, so
 * thousands of files sharing their first bytes are identifiers made up by a gateway, which no eD2K client can
 * download: the bridges of emule-qt.org (its eNode server and its eMule-Qt clients) give the torrent ({@code ED2B0111},
 * {@code ED2B0112}, {@code ED2B0113}), usenet ({@code ED2B0133}) and web ({@code ED2B0153}) downloads hashes starting
 * with {@code ED2B01}. Such files are refused by every ingestion channel, clients publishing them included, and never
 * sent in a search result.
 * <p>
 * A prefix holds {@link #MIN_PREFIX_DIGITS} hexadecimal digits at least, so that a mistake cannot ban a large share
 * of the files.
 */
public final class HashPrefixFilter {
    /**
     * The default prefixes: the emule-qt.org bridges. Three bytes: about 2 real files of 29 million share them by
     * chance, against thousands of made-up ones.
     */
    public static final String DEFAULT = "ED2B01";
    /** Fewest hexadecimal digits of a prefix: 24 bits, one file in 16 million by chance. */
    public static final int MIN_PREFIX_DIGITS = 6;
    /** Most hexadecimal digits of a prefix: a whole MD4 hash. */
    public static final int MAX_PREFIX_DIGITS = 32;

    /** No prefix: nothing is banned. */
    public static final HashPrefixFilter NONE = new HashPrefixFilter(List.of());

    /** The prefixes, in lower case. */
    private final List<String> prefixes;

    private HashPrefixFilter(List<String> prefixes) {
        this.prefixes = List.copyOf(prefixes);
    }

    /**
     * Parses a comma (or space, or semicolon) separated list of hexadecimal prefixes; empty: none.
     *
     * @throws IllegalArgumentException when a prefix is not hexadecimal or its length is out of range
     */
    public static HashPrefixFilter parse(String value) {
        if (value == null || value.isBlank()) return NONE;
        List<String> prefixes = new ArrayList<>();
        for (String token : value.split("[,;\\s]+")) {
            if (token.isEmpty()) continue;
            String prefix = normalizePrefix(token);
            if (!prefixes.contains(prefix)) prefixes.add(prefix);
        }
        return prefixes.isEmpty() ? NONE : new HashPrefixFilter(prefixes);
    }

    /**
     * One prefix in lower case.
     *
     * @throws IllegalArgumentException when it is not hexadecimal or its length is out of range
     */
    public static String normalizePrefix(String token) {
        String prefix = token == null ? "" : token.strip().toLowerCase(Locale.ROOT);
        if (prefix.length() < MIN_PREFIX_DIGITS || prefix.length() > MAX_PREFIX_DIGITS) {
            throw new IllegalArgumentException("A hash prefix holds " + MIN_PREFIX_DIGITS + " to " + MAX_PREFIX_DIGITS
                    + " hexadecimal digits, got '" + token + "'");
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.digit(prefix.charAt(i), 16) < 0) {
                throw new IllegalArgumentException("A hash prefix holds hexadecimal digits only, got '" + token + "'");
            }
        }
        return prefix;
    }

    /** Whether the hash (hexadecimal, any case) starts with one of the prefixes. */
    public boolean matches(String hash) {
        if (hash == null || prefixes.isEmpty()) return false;
        for (String prefix : prefixes) {
            if (hash.regionMatches(true, 0, prefix, 0, prefix.length())) return true;
        }
        return false;
    }

    /** The prefixes, in lower case. */
    public List<String> prefixes() {
        return prefixes;
    }

    public boolean isEmpty() {
        return prefixes.isEmpty();
    }

    @Override
    public String toString() {
        return String.join(",", prefixes);
    }
}
