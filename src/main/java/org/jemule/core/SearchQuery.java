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

    // Tag IDs for filters
    byte ID_FILENAME = 0x01;
    byte ID_FILESIZE = 0x02;
    byte ID_FILETYPE = 0x03;
    byte ID_FORMAT = 0x04;

    // Filter modes for numeric tags (size)
    byte MODE_MIN = 0x01;
    byte MODE_MAX = 0x02;

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
                default -> true;
            };
        }
        @Override
        public String toString() {
            String type = switch (id) {
                case ID_FILENAME -> "Name";
                case ID_FILETYPE -> "Type";
                case ID_FORMAT -> "Format";
                default -> "Unknown(" + id + ")";
            };
            return type + ":" + term;
        }
    }

    record SizeQuery(long value, byte mode) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            return switch (mode) {
                case MODE_MIN -> meta.size() >= value;
                case MODE_MAX -> meta.size() <= value;
                default -> true;
            };
        }
        @Override
        public String toString() {
            String m = (mode == MODE_MIN) ? ">=" : (mode == MODE_MAX) ? "<=" : "?";
            return "Size" + m + value;
        }
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
                byte mode = buf.get();
                int id = readTagId(buf);
                if (id == ID_FILESIZE) {
                    yield builder.size(val, mode);
                }
                yield (meta) -> true;
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
