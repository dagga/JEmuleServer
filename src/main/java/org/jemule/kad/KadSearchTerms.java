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

package org.jemule.kad;

import org.jemule.core.SearchQuery;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a client search into a Kad keyword search: the keyword whose index is queried, and the search expression
 * the index holders filter their entries with (restrictive KADEMLIA2_SEARCH_KEY_REQ).
 */
public final class KadSearchTerms {

    /** Characters separating the words indexed by Kad (INV_KAD_KEYWORD_CHARS in eMule's Defines.h). */
    private static final String KEYWORD_SEPARATORS = " ()[]{}<>,._-!?:;\\/\"";
    /** Kad only indexes words of at least 3 UTF-8 bytes (CSearchManager::GetWords). */
    private static final int MIN_KEYWORD_BYTES = 3;
    /** Nodes reject a search expression nested 24 levels deep or more (CreateSearchExpressionTree). */
    private static final int MAX_DEPTH = 23;
    /** Larger expressions are not sent: the request must fit in one UDP datagram. */
    private static final int MAX_TERMS_BYTES = 512;

    private KadSearchTerms() {}

    /**
     * Words of {@code text} as Kad indexes them: lower case, split on eMule's keyword separators, at least 3 bytes.
     */
    public static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) return words;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (KEYWORD_SEPARATORS.indexOf(c) < 0) {
                word.append(c);
            } else if (!word.isEmpty()) {
                String w = word.toString().toLowerCase(Locale.ROOT);
                if (w.getBytes(StandardCharsets.UTF_8).length >= MIN_KEYWORD_BYTES && !words.contains(w)) words.add(w);
                word.setLength(0);
            }
        }
        return words;
    }

    /**
     * Chooses the keyword whose index is queried: the longest word every result must contain, as eMule does when
     * it rearranges Kad keywords (a longer word is rarer, so its index is smaller and its holders less loaded).
     * Falls back to the first indexable word of the query when no word is required (e.g. "a OR b").
     *
     * @return the keyword, or null if the query has no indexable word
     */
    public static String keyword(SearchQuery query) {
        List<String> required = new ArrayList<>();
        collectWords(query, required, true);
        if (required.isEmpty()) collectWords(query, required, false);
        String best = null;
        for (String w : required) {
            if (best == null || w.length() > best.length()) best = w;
        }
        return best;
    }

    private static void collectWords(SearchQuery query, List<String> out, boolean requiredOnly) {
        switch (query) {
            case SearchQuery.TermQuery t when t.id() == SearchQuery.ID_FILENAME -> out.addAll(words(t.term()));
            case SearchQuery.AndQuery and -> {
                collectWords(and.left(), out, requiredOnly);
                collectWords(and.right(), out, requiredOnly);
            }
            case SearchQuery.OrQuery or when !requiredOnly -> {
                collectWords(or.left(), out, false);
                collectWords(or.right(), out, false);
            }
            case SearchQuery.NotQuery not -> collectWords(not.left(), out, requiredOnly);
            case null, default -> { }
        }
    }

    /**
     * Encodes the search expression sent with a restrictive search, in the ed2k search tree format that
     * CKademliaUDPListener::CreateSearchExpressionTree reads. Parts that cannot be encoded are widened (an unknown
     * filter matches everything): the node may then return more files, which the server filters again.
     *
     * @return the expression, or null when it would not restrict the keyword's entries or cannot be sent
     */
    public static byte[] encode(SearchQuery query, String keyword) {
        if (query instanceof SearchQuery.TermQuery t && t.id() == SearchQuery.ID_FILENAME
                && words(t.term()).equals(List.of(keyword))) {
            return null; // every entry of the keyword index matches
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!write(query, out, 0) || out.size() > MAX_TERMS_BYTES) return null;
        return out.toByteArray();
    }

    /** Writes {@code query}; returns false, writing nothing, when it is equivalent to "match everything". */
    private static boolean write(SearchQuery query, ByteArrayOutputStream out, int depth) {
        if (depth > MAX_DEPTH) return false;
        return switch (query) {
            case SearchQuery.AndQuery and -> writeBoolean(SearchQuery.OP_AND, and.left(), and.right(), out, depth);
            case SearchQuery.OrQuery or -> writeBoolean(SearchQuery.OP_OR, or.left(), or.right(), out, depth);
            case SearchQuery.NotQuery not -> writeBoolean(SearchQuery.OP_NOT, not.left(), not.right(), out, depth);
            case SearchQuery.TermQuery t when t.id() == SearchQuery.ID_FILENAME -> {
                out.write(0x01);
                writeString(out, t.term());
                yield true;
            }
            case SearchQuery.TermQuery t -> {
                out.write(0x02);
                writeString(out, t.term());
                writeTagName(out, t.id());
                yield true;
            }
            case SearchQuery.NumericQuery n -> {
                boolean large = n.value() > 0xFFFFFFFFL;
                out.write(large ? 0x08 : 0x03);
                writeLittleEndian(out, n.value(), large ? 8 : 4);
                out.write(n.operator());
                writeTagName(out, n.id());
                yield true;
            }
            case null, default -> false;
        };
    }

    private static boolean writeBoolean(byte op, SearchQuery left, SearchQuery right, ByteArrayOutputStream out,
                                        int depth) {
        ByteArrayOutputStream l = new ByteArrayOutputStream();
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        boolean hasLeft = write(left, l, depth + 1);
        boolean hasRight = write(right, r, depth + 1);
        if (hasLeft && hasRight) {
            out.write(0x00);
            out.write(op);
            out.writeBytes(l.toByteArray());
            out.writeBytes(r.toByteArray());
            return true;
        }
        if (op == SearchQuery.OP_OR || !hasLeft) return false; // "x OR all" and "all NOT x" are widened to all
        out.writeBytes(l.toByteArray()); // "x AND all" is x; "x NOT all" is widened to x
        return true;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeLittleEndian(out, b.length, 2);
        out.writeBytes(b);
    }

    private static void writeTagName(ByteArrayOutputStream out, byte id) {
        writeLittleEndian(out, 1, 2);
        out.write(id);
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (8 * i)));
    }
}
