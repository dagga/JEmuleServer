package org.jemule.core;

import org.jemule.protocol.Tag;

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

    /**
     * Parses a SEARCH_REQUEST payload recursively.
     */
    static SearchQuery parse(ByteBuffer buf) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        byte first = buf.get();
        if (first == 0x00) {
            // Operator node
            byte op = buf.get();
            SearchQuery left = parse(buf);
            SearchQuery right = parse(buf);
            return switch (op) {
                case OP_AND -> (meta) -> left.test(meta) && right.test(meta);
                case OP_OR -> (meta) -> left.test(meta) || right.test(meta);
                case OP_NOT -> (meta) -> left.test(meta) && !right.test(meta);
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
                    String term = new String(b, StandardCharsets.UTF_8).toLowerCase();
                    yield (meta) -> meta.name() != null && meta.name().toLowerCase().contains(term);
                }
                case 0x02 -> { // String tag with numeric ID
                    int len = Short.toUnsignedInt(buf.getShort());
                    byte[] b = new byte[len];
                    buf.get(b);
                    String term = new String(b, StandardCharsets.UTF_8).toLowerCase();
                    buf.getShort(); // name length (1)
                    byte id = buf.get();
                    yield switch (id) {
                        case ID_FILENAME -> (meta) -> meta.name() != null && meta.name().toLowerCase().contains(term);
                        case ID_FILETYPE -> (meta) -> meta.type() != null && meta.type().equalsIgnoreCase(term);
                        case ID_FORMAT -> (meta) -> meta.name() != null && meta.name().toLowerCase().endsWith("." + term);
                        default -> (meta) -> true; // Unknown filter, ignore
                    };
                }
                case 0x03 -> { // Numeric tag with numeric ID (Size)
                    long val = Integer.toUnsignedLong(buf.getInt());
                    byte mode = buf.get();
                    buf.getShort(); // name length (1)
                    byte id = buf.get();
                    if (id == ID_FILESIZE) {
                        yield switch (mode) {
                            case MODE_MIN -> (meta) -> meta.size() >= val;
                            case MODE_MAX -> (meta) -> meta.size() <= val;
                            default -> (meta) -> true;
                        };
                    }
                    yield (meta) -> true;
                }
                default -> throw new IllegalArgumentException("Unknown search tag type: 0x" + String.format("%02X", type));
            };
        }
    }
}
