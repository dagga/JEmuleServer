package org.jemule.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Represents a search expression (binary tree node or leaf).
 */
public interface SearchQuery extends Predicate<FileMetadata> {

    // Operators
    byte OP_AND = 0x00;
    byte OP_OR = 0x01;
    byte OP_NOT = 0x02;

    // Tag IDs for filters (FT_* in eMule opcodes.h)
    byte ID_FILENAME = 0x01;
    byte ID_FILESIZE = 0x02;
    byte ID_FILETYPE = 0x03;
    byte ID_FORMAT = 0x04;
    byte ID_SOURCES = 0x15;
    byte ID_COMPLETE_SOURCES = 0x30;
    byte ID_MEDIA_ARTIST = (byte) 0xD0;
    byte ID_MEDIA_ALBUM = (byte) 0xD1;
    byte ID_MEDIA_TITLE = (byte) 0xD2;
    byte ID_MEDIA_LENGTH = (byte) 0xD3;
    byte ID_MEDIA_BITRATE = (byte) 0xD4;
    byte ID_MEDIA_CODEC = (byte) 0xD5;
    byte ID_FILERATING = (byte) 0xF7;

    // Comparison operators of numeric parameters (ED2K_SEARCH_OP_* in opcodes.h)
    byte OP_EQUAL = 0;
    byte OP_GREATER = 1;
    byte OP_LESS = 2;
    byte OP_GREATER_EQUAL = 3;
    byte OP_LESS_EQUAL = 4;
    byte OP_NOTEQUAL = 5;

    record AndQuery(SearchQuery left, SearchQuery right) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            return left.test(meta) && right.test(meta);
        }
        @Override
        public String toString() {
            return "(" + left + " AND " + right + ")";
        }
    }

    record OrQuery(SearchQuery left, SearchQuery right) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            return left.test(meta) || right.test(meta);
        }
        @Override
        public String toString() {
            return "(" + left + " OR " + right + ")";
        }
    }

    record NotQuery(SearchQuery left, SearchQuery right) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            return left.test(meta) && !right.test(meta);
        }
        @Override
        public String toString() {
            return "(" + left + " NOT " + right + ")";
        }
    }

    record TermQuery(String term, byte id) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            String lowerTerm = term.toLowerCase();
            return switch (id) {
                case ID_FILENAME -> {
                    if (meta.name() == null) yield false;
                    String nameLower = Keywords.normalize(meta.name());
                    String[] tokens = Keywords.split(term);
                    if (tokens.length == 0) yield false; // a keyword without any letter/digit matches nothing
                    for (String t : tokens) {
                        if (!nameLower.contains(t)) yield false;
                    }
                    yield true;
                }
                case ID_FILETYPE -> meta.type() != null && meta.type().equalsIgnoreCase(lowerTerm);
                case ID_FORMAT -> meta.name() != null && meta.name().toLowerCase().endsWith("." + lowerTerm);
                case ID_MEDIA_CODEC, ID_MEDIA_TITLE, ID_MEDIA_ALBUM, ID_MEDIA_ARTIST -> {
                    String value = stringTag(meta, id);
                    yield value != null && Keywords.normalize(value).contains(Keywords.normalize(term));
                }
                default -> true; // unknown meta tag: not filtered on
            };
        }
        @Override
        public String toString() {
            String type = switch (id) {
                case ID_FILENAME -> "Name";
                case ID_FILETYPE -> "Type";
                case ID_FORMAT -> "Format";
                default -> "Tag(0x" + Integer.toHexString(id & 0xFF) + ")";
            };
            return type + ":" + term;
        }
    }

    /**
     * Numeric comparison <code>&lt;tag&gt; &lt;operator&gt; &lt;value&gt;</code> (ED2K_SEARCH_OP_*). eMule sends "&gt;= v" as
     * "&gt; v-1" and "&lt;= v" as "&lt; v+1" (GetSearchPacket), so the operators must keep their strict meaning.
     */
    record NumericQuery(byte id, byte operator, long value) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            Long actual = switch (id) {
                case ID_FILESIZE -> meta.size();
                case ID_SOURCES -> (long) meta.sources().size();
                case ID_COMPLETE_SOURCES -> (long) meta.completeSourceCount();
                case ID_MEDIA_LENGTH, ID_MEDIA_BITRATE, ID_FILERATING -> intTag(meta, id);
                default -> null;
            };
            if (actual == null) {
                // a file without this information cannot satisfy the filter; an unknown tag is not filtered on
                return !(id == ID_MEDIA_LENGTH || id == ID_MEDIA_BITRATE || id == ID_FILERATING);
            }
            return switch (operator) {
                case OP_EQUAL -> actual == value;
                case OP_GREATER -> actual > value;
                case OP_LESS -> actual < value;
                case OP_GREATER_EQUAL -> actual >= value;
                case OP_LESS_EQUAL -> actual <= value;
                case OP_NOTEQUAL -> actual != value;
                default -> true;
            };
        }
        @Override
        public String toString() {
            String op = switch (operator) {
                case OP_EQUAL -> "=";
                case OP_GREATER -> ">";
                case OP_LESS -> "<";
                case OP_GREATER_EQUAL -> ">=";
                case OP_LESS_EQUAL -> "<=";
                case OP_NOTEQUAL -> "!=";
                default -> "?";
            };
            return "Tag(0x" + Integer.toHexString(id & 0xFF) + ")" + op + value;
        }
    }

    private static String stringTag(FileMetadata meta, byte id) {
        String name = String.valueOf((char) (id & 0xFF));
        for (org.jemule.protocol.Tag t : meta.tags()) {
            if (t.name().equals(name) && t.value() instanceof String str) return str;
        }
        return null;
    }

    private static Long intTag(FileMetadata meta, byte id) {
        String name = String.valueOf((char) (id & 0xFF));
        for (org.jemule.protocol.Tag t : meta.tags()) {
            if (t.name().equals(name) && t.isInt()) return t.unsignedValue();
        }
        return null;
    }

    /** Maximum nesting of boolean operators accepted in a search tree (protects the parser's stack). */
    int MAX_DEPTH = 64;

    /**
     * Parses a search tree as written by eMule's CSearchExprTarget (SearchResultsWnd.cpp), in prefix order:
     * <pre>
     * 0x00 &lt;op 1&gt; &lt;left&gt; &lt;right&gt;                              boolean operator (0 AND, 1 OR, 2 NOT)
     * 0x01 &lt;len 2&gt;&lt;utf8&gt;                                     keyword (file name)
     * 0x02 &lt;len 2&gt;&lt;utf8&gt; &lt;namelen 2&gt;&lt;name&gt;                  string meta tag (FT_FILETYPE, FT_FILEFORMAT, ...)
     * 0x03 &lt;uint32&gt; &lt;op 1&gt; &lt;namelen 2&gt;&lt;name&gt;                 numeric meta tag
     * 0x08 &lt;uint64&gt; &lt;op 1&gt; &lt;namelen 2&gt;&lt;name&gt;                 numeric meta tag &gt; 4 GB (servers supporting large files)
     * </pre>
     * A tag name of length 1 is a numeric FT_* ID; longer names (old ed2k string names) are not filtered on.
     *
     * @throws RuntimeException if the tree is malformed
     */
    static SearchQuery parse(ByteBuffer buf) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return parse(buf, 0);
    }

    private static SearchQuery parse(ByteBuffer buf, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Search tree too deep");
        SearchQueryBuilder builder = new SearchQueryBuilder();
        byte type = buf.get();
        return switch (type) {
            case 0x00 -> {
                byte op = buf.get();
                SearchQuery left = parse(buf, depth + 1);
                SearchQuery right = parse(buf, depth + 1);
                yield switch (op) {
                    case OP_AND -> builder.and(left, right);
                    case OP_OR -> builder.or(left, right);
                    case OP_NOT -> builder.not(left, right);
                    default -> throw new IllegalArgumentException("Unknown search operator: 0x" + String.format("%02X", op));
                };
            }
            case 0x01 -> builder.term(readString(buf), ID_FILENAME);
            case 0x02 -> {
                String term = readString(buf);
                int id = readTagId(buf);
                yield id < 0 ? (meta) -> true : builder.term(term, (byte) id);
            }
            case 0x03, 0x08 -> {
                long val = type == 0x03 ? Integer.toUnsignedLong(buf.getInt()) : buf.getLong();
                byte operator = buf.get();
                int id = readTagId(buf);
                yield id < 0 ? (meta) -> true : builder.numeric((byte) id, operator, val);
            }
            default -> throw new IllegalArgumentException("Unknown search tag type: 0x" + String.format("%02X", type));
        };
    }

    private static String readString(ByteBuffer buf) {
        int len = Short.toUnsignedInt(buf.getShort());
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Reads &lt;namelen 2&gt;&lt;name&gt;: returns the FT_* ID for a 1-byte name, -1 for a string name. */
    private static int readTagId(ByteBuffer buf) {
        int nameLen = Short.toUnsignedInt(buf.getShort());
        if (nameLen == 1) return Byte.toUnsignedInt(buf.get());
        buf.position(buf.position() + nameLen);
        return -1;
    }
}
