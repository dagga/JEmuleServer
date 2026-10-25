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

    /**
     * The compiled keyword lists, replaced as a whole by {@link #reload(String)}. The terms starting with '*' are
     * kept apart ({@link WordTerms}): in the regular expression, each of them would scan every word from each of its
     * letters, which made the whole filter cost about 13 µs per name.
     */
    private record Rules(Terms banned, Terms minor, Terms sexual, Pattern age, int maxMinorAge) {
    }

    /**
     * A list of terms: the regular expression of most of them, and the word tests of the '*x' and '*x*' ones. Every
     * term of the expression starts with letters (its head), so it can only match where a word starts with them:
     * {@code heads} holds the first {@link #HEAD} letters of each head, and the expression only runs on a name having
     * a word that starts with one of them, which most names do not.
     */
    private record Terms(Pattern pattern, java.util.Set<String> heads, WordTerms words) {
        boolean find(String text) {
            return (pattern != null && mayMatch(text) && pattern.matcher(text).find()) || words.find(text);
        }

        private boolean mayMatch(String text) {
            if (heads.contains("")) return true; // a term without head: the expression decides
            int start = 0;
            while (start < text.length()) {
                int end = text.indexOf(' ', start);
                if (end < 0) end = text.length();
                for (int length = 1; length <= HEAD && start + length <= end; length++) {
                    if (heads.contains(text.substring(start, start + length))) return true;
                }
                start = end + 1;
            }
            return false;
        }
    }

    /** Letters of a term's head kept to pick the names the expression runs on. */
    private static final int HEAD = 3;

    /** Terms '*x' (a word ending with x) and '*x*' (a word containing x), x being letters, digits or '@' only. */
    private record WordTerms(List<String> endings, List<String> parts) {
        boolean find(String text) {
            if (endings.isEmpty() && parts.isEmpty()) return false;
            for (String word : text.split(" ")) {
                for (String ending : endings) if (word.endsWith(ending)) return true;
                for (String part : parts) if (word.contains(part)) return true;
            }
            return false;
        }
    }

    private volatile Rules rules;

    private ChildAbuseFilter(Properties props) {
        String suffixes = alternation(props.getProperty("ageSuffixes"), null, null);
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
        if (current.banned().find(text)) return true;
        return (current.minor().find(text) || hasMinorAge(current, text)) && current.sexual().find(text);
    }

    private static boolean hasMinorAge(Rules rules, String text) {
        if (rules.age() == null) return false;
        var m = rules.age().matcher(text);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) <= rules.maxMinorAge()) return true;
        }
        return false;
    }

    /** Lower case, every run of characters other than letters, digits and '@' replaced by one space. */
    static String normalize(String s) {
        return SEPARATORS.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * Compiles a comma-separated list of terms. A term matches whole words; '*' stands for any letters or digits;
     * spaces (or any separator) match one separator.
     */
    static Terms terms(String list) {
        WordTerms words = new WordTerms(new ArrayList<>(), new ArrayList<>());
        java.util.Set<String> heads = new java.util.HashSet<>();
        String alternation = alternation(list, words, heads);
        Pattern pattern = alternation == null ? null
                : Pattern.compile("(?<!" + WORD_CHAR + ")(?:" + alternation + ")(?!" + WORD_CHAR + ")");
        return new Terms(pattern, java.util.Set.copyOf(heads),
                new WordTerms(List.copyOf(words.endings()), List.copyOf(words.parts())));
    }

    /** Letters, digits or '@' only: a term of one word without wildcard. */
    private static final Pattern WORD = Pattern.compile(WORD_CHAR + "+");

    /**
     * The terms of a comma-separated list as a regular expression alternation, without word boundaries; the '*x'
     * and '*x*' terms go to {@code words} instead when it is not null. Null when no term is left.
     */
    private static String alternation(String list, WordTerms words, java.util.Set<String> heads) {
        if (list == null) return null;
        List<String> alternatives = new ArrayList<>();
        for (String raw : list.split(",")) {
            String term = raw.trim().toLowerCase(Locale.ROOT);
            if (term.replace("*", "").isBlank()) continue;
            if (words != null && term.startsWith("*")) {
                String part = term.endsWith("*") ? term.substring(1, term.length() - 1) : term.substring(1);
                if (WORD.matcher(part).matches()) {
                    (term.endsWith("*") ? words.parts() : words.endings()).add(part);
                    continue;
                }
            }
            StringBuilder re = new StringBuilder();
            String[] parts = term.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) re.append(WORD_CHAR).append('*');
                String part = SEPARATORS.matcher(parts[i]).replaceAll(" ");
                if (!part.isEmpty()) re.append(Pattern.quote(part));
            }
            alternatives.add(re.toString());
            if (heads != null) {
                // The letters before the first wildcard or separator; a term starting with '*' could start anywhere
                String head = SEPARATORS.matcher(term.split("\\*", -1)[0]).replaceAll(" ").split(" ", -1)[0];
                heads.add(head.isEmpty() ? "" : head.substring(0, Math.min(HEAD, head.length())));
            }
        }
        return alternatives.isEmpty() ? null : String.join("|", alternatives);
    }
}
