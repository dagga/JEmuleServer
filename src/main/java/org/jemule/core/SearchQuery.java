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
    }

    record OrQuery(SearchQuery left, SearchQuery right) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            return left.test(meta) || right.test(meta);
        }
    }

    record NotQuery(SearchQuery left, SearchQuery right) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            return left.test(meta) && !right.test(meta);
        }
    }

    record TermQuery(String term, byte id) implements SearchQuery {
        @Override
        public boolean test(FileMetadata meta) {
            String lowerTerm = term.toLowerCase();
            return switch (id) {
                case ID_FILENAME -> {
                    if (meta.name() == null) yield false;
                    String nameLower = meta.name().toLowerCase();
                    String[] tokens = lowerTerm.split("[^a-zA-Z0-9]+");
                    for (String t : tokens) {
                        if (!t.isEmpty() && !nameLower.contains(t)) yield false;
                    }
                    yield true;
                }
                case ID_FILETYPE -> meta.type() != null && meta.type().equalsIgnoreCase(lowerTerm);
                case ID_FORMAT -> meta.name() != null && meta.name().toLowerCase().endsWith("." + lowerTerm);
                default -> true;
            };
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
    }

    /**
     * Parses a SEARCH_REQUEST payload recursively.
     */
    static SearchQuery parse(ByteBuffer buf) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        byte first = buf.get();
        SearchQueryBuilder builder = new SearchQueryBuilder();
        if (first == 0x00) {
            // Operator node
            byte op = buf.get();
            SearchQuery left = parse(buf);
            SearchQuery right = parse(buf);
            return switch (op) {
                case OP_AND -> builder.and(left, right);
                case OP_OR -> builder.or(left, right);
                case OP_NOT -> builder.not(left, right);
                default -> throw new IllegalArgumentException("Unknown search operator: 0x" + String.format("%02X", op));
            };
        } else {
            // Leaf node (Tag2)
            // Restore the first byte as it's part of the tag type
            buf.position(buf.position() - 1);
            byte type = buf.get();
            return switch (type) {
                case 0x01 -> { // String tag, no ID (Filename)
                    int len = Short.toUnsignedInt(buf.getShort());
                    byte[] b = new byte[len];
                    buf.get(b);
                    String term = new String(b, StandardCharsets.UTF_8);
                    yield builder.term(term, ID_FILENAME);
                }
                case 0x02 -> { // String tag with numeric ID
                    int len = Short.toUnsignedInt(buf.getShort());
                    byte[] b = new byte[len];
                    buf.get(b);
                    String term = new String(b, StandardCharsets.UTF_8);
                    buf.getShort(); // name length (1)
                    byte id = buf.get();
                    yield builder.term(term, id);
                }
                case 0x03 -> { // Numeric tag with numeric ID (Size)
                    long val = Integer.toUnsignedLong(buf.getInt());
                    byte mode = buf.get();
                    buf.getShort(); // name length (1)
                    byte id = buf.get();
                    if (id == ID_FILESIZE) {
                        yield builder.size(val, mode);
                    }
                    yield (meta) -> true;
                }
                default -> throw new IllegalArgumentException("Unknown search tag type: 0x" + String.format("%02X", type));
            };
        }
    }
}
