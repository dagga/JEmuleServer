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

    /** ED2KFT_* type of each known extension (g_aED2KFileTypes in eMule's OtherFunctions.cpp). */
    private static final java.util.Map<String, Integer> EXTENSIONS = extensions();

    private static java.util.Map<String, Integer> extensions() {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        String[][] byType = {
                {"aac ac3 aif aifc aiff amr ape au aud audio cda dmf dsm dts far flac it m1a m2a m4a mdl med mid midi "
                        + "mka mod mp1 mp2 mp3 mpa mpc mtm ogg psm ptm ra rmi s3m snd stm umx wav wma xm"},
                {"3g2 3gp 3gp2 3gpp amv asf avi bik divx dvr-ms flc fli flic flv hdmov ifo m1v m2t m2ts m2v m4b m4v mkv "
                        + "mov movie mp1v mp2v mp4 mpe mpeg mpg mpv mpv1 mpv2 ogm pva qt ram ratdvd rm rmm rmvb rv smil "
                        + "smk swf tp ts vid video vob vp6 wm wmv xvid"},
                {"bmp emf gif ico jfif jpe jpeg jpg pct pcx pic pict png psd psp tga tif tiff wmf wmp xif"},
                {"bat cmd com exe hta js jse msc vbe vbs wsf wsh"},
                {"chm css diz doc dot hlp htm html nfo pdf pps ppt ps rtf text txt wri xls xml"},
                {"7z ace alz arc arj bz2 cab cbr cbz gz hqx lha lzh msi pak par par2 rar sit sitx tar tbz2 tgz xpi z zip"},
                {"bin bwa bwi bws bwt ccd cue dmg img iso mdf mds nrg sub toast"},
                {"emulecollection"},
        };
        for (int type = 1; type <= byType.length; type++) {
            for (String extension : byType[type - 1][0].split(" ")) map.putIfAbsent(extension, type);
        }
        return java.util.Map.copyOf(map);
    }

    /**
     * Guesses the type of a file from its extension, as eMule does for a file announced without FT_FILETYPE;
     * "" if the extension is unknown.
     */
    public static String fromFileName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        Integer id = EXTENSIONS.get(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        String type = id == null ? null : fromId(id);
        return type == null ? "" : type;
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
