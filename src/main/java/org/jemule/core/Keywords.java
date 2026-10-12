package org.jemule.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits file names and search keywords into lower-case words. Letters and digits of every script are
 * kept (é, ü, Cyrillic, CJK...): eMule sends keywords in UTF-8 to servers announcing SRV_TCPFLG_UNICODE.
 */
public final class Keywords {
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private Keywords() {}

    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Lower-case words of the given text, without empty entries. */
    public static String[] split(String s) {
        if (s == null) return new String[0];
        return Arrays.stream(SEPARATORS.split(normalize(s))).filter(t -> !t.isEmpty()).toArray(String[]::new);
    }
}
