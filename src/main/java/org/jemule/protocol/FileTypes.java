package org.jemule.protocol;

/**
 * ed2k file types (FT_FILETYPE). Servers announcing SRV_TCPFLG_TYPETAGINTEGER receive the type as an
 * integer (EED2KFileType in eMule otherfunctions.h), but search requests and search results use the
 * string form (ED2KFTSTR_* in opcodes.h, see GetED2KFileTypeSearchTerm). The index always stores the string.
 */
public final class FileTypes {
    public static final String AUDIO = "Audio";
    public static final String VIDEO = "Video";
    public static final String IMAGE = "Image";
    public static final String PROGRAM = "Pro";
    public static final String DOCUMENT = "Doc";
    public static final String EMULE_COLLECTION = "EmuleCollection";

    private FileTypes() {}

    /** Maps an ED2KFT_* integer to the search term the client uses; null for unknown values. */
    public static String fromId(long id) {
        return switch ((int) id) {
            case 1 -> AUDIO;
            case 2 -> VIDEO;
            case 3 -> IMAGE;
            case 4, 6, 7 -> PROGRAM; // archives and CD images are published and searched as "Pro"
            case 5 -> DOCUMENT;
            case 8 -> EMULE_COLLECTION;
            default -> null;
        };
    }

    /** Normalizes a received FT_FILETYPE tag (integer or string) to its string form; "" if unknown. */
    public static String fromTag(Tag tag) {
        if (tag.isInt()) {
            String s = fromId(tag.unsignedValue());
            return s == null ? "" : s;
        }
        return tag.value() instanceof String s ? s : "";
    }
}
