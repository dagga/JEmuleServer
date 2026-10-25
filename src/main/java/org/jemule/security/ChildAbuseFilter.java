package org.jemule.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Detects file names advertising child sexual abuse material. A name matches if it contains a "banned" term, or a
 * term designating a minor (a "minor" term or an age up to {@code maxMinorAge}) together with a "sexual" term.
 * The terms are read from a properties file (see {@code child-abuse-keywords.properties} for the syntax); unlike
 * {@link FakeFileDetector}, this filter cannot be disabled.
 */
public final class ChildAbuseFilter {
    private static final Logger log = LoggerFactory.getLogger(ChildAbuseFilter.class);

    /** Keyword file embedded in the jar, used when no other file is configured. */
    public static final String DEFAULT_RESOURCE = "/child-abuse-keywords.properties";
    /** Reason given for the files rejected by this filter. */
    public static final String BAN_REASON = "Child abuse material";

    private static final String WORD_CHAR = "[\\p{L}\\p{N}@]";
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}@]+");

    /** The compiled keyword lists, replaced as a whole by {@link #reload(String)}. */
    private record Rules(Pattern banned, Pattern minor, Pattern sexual, Pattern age, int maxMinorAge) {
    }

    private volatile Rules rules;

    private ChildAbuseFilter(Properties props) {
        String suffixes = alternation(props.getProperty("ageSuffixes"));
        Pattern age = suffixes == null ? null
                : Pattern.compile("(?<!" + WORD_CHAR + ")(\\d{1,3}) ?(?:" + suffixes + ")(?!" + WORD_CHAR + ")");
        this.rules = new Rules(terms(props.getProperty("banned")), terms(props.getProperty("minor")),
                terms(props.getProperty("sexual")), age,
                Integer.parseInt(props.getProperty("maxMinorAge", "17").trim()));
    }

    public static ChildAbuseFilter fromProperties(Properties props) {
        return new ChildAbuseFilter(props);
    }

    /** The filter built from the keyword file embedded in the jar. */
    public static ChildAbuseFilter defaults() {
        try (InputStream in = ChildAbuseFilter.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing resource " + DEFAULT_RESOURCE);
            Properties props = new Properties();
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return new ChildAbuseFilter(props);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + DEFAULT_RESOURCE, e);
        }
    }

    /**
     * Loads the keyword file at {@code path}, or the embedded one if {@code path} is null or blank. A file that
     * cannot be read falls back to the embedded keywords: the filter is never left empty by a configuration error.
     */
    public static ChildAbuseFilter load(String path) {
        if (path == null || path.isBlank()) return defaults();
        try (Reader in = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(in);
            log.info("Loaded child abuse keywords from {}", path);
            return new ChildAbuseFilter(props);
        } catch (IOException | RuntimeException e) {
            log.error("Cannot read child abuse keywords from {} ({}), using the embedded keywords", path, e.getMessage());
            return defaults();
        }
    }

    /**
     * Replaces the keywords in use with those of {@code path} (the embedded ones if it is null or blank), with the
     * same fallback as {@link #load(String)}. Files already indexed are not checked again.
     */
    public void reload(String path) {
        this.rules = load(path).rules;
    }

    /** True if the file name advertises child sexual abuse material. */
    public boolean matches(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        Rules current = rules;
        String text = normalize(fileName);
        if (find(current.banned(), text)) return true;
        return (find(current.minor(), text) || hasMinorAge(current, text)) && find(current.sexual(), text);
    }

    private static boolean hasMinorAge(Rules rules, String text) {
        if (rules.age() == null) return false;
        var m = rules.age().matcher(text);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) <= rules.maxMinorAge()) return true;
        }
        return false;
    }

    private static boolean find(Pattern p, String text) {
        return p != null && p.matcher(text).find();
    }

    /** Lower case, every run of characters other than letters, digits and '@' replaced by one space. */
    static String normalize(String s) {
        return SEPARATORS.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * Compiles a comma-separated list of terms into one pattern. A term matches whole words; '*' stands for any
     * letters or digits; spaces (or any separator) match one separator. Returns null for an empty list.
     */
    static Pattern terms(String list) {
        String alternation = alternation(list);
        return alternation == null ? null
                : Pattern.compile("(?<!" + WORD_CHAR + ")(?:" + alternation + ")(?!" + WORD_CHAR + ")");
    }

    /** The terms of a comma-separated list as a regular expression alternation, without word boundaries. */
    private static String alternation(String list) {
        if (list == null) return null;
        List<String> alternatives = new ArrayList<>();
        for (String raw : list.split(",")) {
            String term = raw.trim().toLowerCase(Locale.ROOT);
            if (term.replace("*", "").isBlank()) continue;
            StringBuilder re = new StringBuilder();
            String[] parts = term.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) re.append(WORD_CHAR).append('*');
                String part = SEPARATORS.matcher(parts[i]).replaceAll(" ");
                if (!part.isEmpty()) re.append(Pattern.quote(part));
            }
            alternatives.add(re.toString());
        }
        return alternatives.isEmpty() ? null : String.join("|", alternatives);
    }
}
