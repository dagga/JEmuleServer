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

package org.jemule.core;

import org.jemule.network.handler.HandlerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives the files their original name back, when a client or a server altered it by adding a text: some write
 * {@code [kad emule-qt.org] } before the names they relay, or {@code [torrent emule-qt.org]}, {@code [web
 * emule-qt.org]}, sometimes in the middle of the name or before its extension.
 * <p>
 * A rule is a text, compared without case. Each group between brackets ({@code [...]}, {@code (...)} or
 * {@code {...}}) that holds it goes, the brackets with it, and so does the text itself where it stands outside such a
 * group. Then the spaces are tidied up: a run of spaces becomes one, and the spaces, underscores and dashes left
 * dangling at the start or the end of the name before its extension go. The extension is never changed. A name that
 * holds no rule is never touched at all; a name that would be empty or invalid once cleaned
 * ({@link HandlerUtils#isValidFilename}) is kept as it is.
 * <p>
 * The rules live in the {@code name_cleaning_rules} table, in lower case, and in memory. {@link FileIndex} cleans every
 * file it is given, whatever its channel, before indexing it, saving it or handing it to the relay feed; the search
 * results sent to the clients are cleaned too ({@link FileIndex#cleanedForClient}), and the files indexed before a
 * rule existed are renamed by {@link NameCleaningJob}.
 */
public final class NameCleaner {
    private static final Logger log = LoggerFactory.getLogger(NameCleaner.class);
    /** Rule written at the first start of a database. */
    public static final String DEFAULT_RULE = "emule-qt.org";
    /** Longest rule, in characters. */
    public static final int MAX_RULE_LENGTH = 120;
    /** Database stat set once the default rule was written: a default rule the administrator removed stays removed. */
    static final String SEEDED_STAT = "name_cleaning_seeded";
    /** Innermost groups between brackets, parentheses or braces. */
    private static final Pattern GROUP = Pattern.compile("\\[[^\\[\\]]*]|\\([^()]*\\)|\\{[^{}]*}");
    private static final Pattern SPACES = Pattern.compile(" {2,}");
    /** Longest extension kept apart from the name; a longer "extension" is part of the name. */
    private static final int MAX_EXTENSION_LENGTH = 10;

    /** A rule: its text in lower case, when it was added (epoch milliseconds, 0 if unknown), its matcher. */
    public record Rule(String text, long addedAt, Pattern pattern) {
        Rule(String text, long addedAt) {
            this(text, addedAt, Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
    }

    private final DatabaseManager db;
    private volatile List<Rule> rules = List.of();
    /** Files whose name was cleaned at their ingestion since the start. */
    private final LongAdder cleanedAtIngestion = new LongAdder();

    /** Rules kept in memory only (tests, a server without database). */
    public NameCleaner(String... rules) {
        this.db = null;
        for (String rule : rules) add(rule);
    }

    private NameCleaner(DatabaseManager db) {
        this.db = db;
    }

    /**
     * The rules of the database, the default one written at its first start. Without database, the default rule
     * alone, in memory.
     */
    public static NameCleaner load(DatabaseManager db) {
        if (db == null) return new NameCleaner(DEFAULT_RULE);
        NameCleaner cleaner = new NameCleaner(db);
        if (db.getStat(SEEDED_STAT, 0) == 0) {
            db.addNameCleaningRule(DEFAULT_RULE);
            db.setStat(SEEDED_STAT, 1);
        }
        List<Rule> loaded = new ArrayList<>();
        for (DatabaseManager.NameCleaningRule rule : db.loadNameCleaningRules()) {
            String text = normalize(rule.text());
            if (text != null) loaded.add(new Rule(text, rule.addedAt() == null ? 0 : rule.addedAt().getTime()));
        }
        cleaner.rules = List.copyOf(loaded);
        log.info("Loaded {} file name cleaning rule(s)", loaded.size());
        return cleaner;
    }

    /** A rule as stored: trimmed, in lower case; null if empty or longer than {@link #MAX_RULE_LENGTH}. */
    public static String normalize(String text) {
        if (text == null) return null;
        String trimmed = text.strip().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() || trimmed.length() > MAX_RULE_LENGTH ? null : trimmed;
    }

    /** The rules, the oldest first. */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * Adds a rule, saved in the database. It applies at once to the files coming in; the files already indexed are
     * renamed by {@link NameCleaningJob}.
     *
     * @return the rule as stored
     * @throws IllegalArgumentException if the text is empty or too long
     */
    public synchronized String add(String text) {
        String normalized = normalize(text);
        if (normalized == null) {
            throw new IllegalArgumentException("A rule must hold 1 to " + MAX_RULE_LENGTH + " characters");
        }
        for (Rule rule : rules) {
            if (rule.text().equals(normalized)) return normalized;
        }
        List<Rule> updated = new ArrayList<>(rules);
        updated.add(new Rule(normalized, System.currentTimeMillis()));
        rules = List.copyOf(updated);
        if (db != null) db.addNameCleaningRule(normalized);
        return normalized;
    }

    /**
     * Removes a rule. The files it cleaned keep their new name.
     *
     * @return true if the rule existed
     * @throws IllegalArgumentException if the text is empty or too long
     */
    public synchronized boolean remove(String text) {
        String normalized = normalize(text);
        if (normalized == null) {
            throw new IllegalArgumentException("A rule must hold 1 to " + MAX_RULE_LENGTH + " characters");
        }
        List<Rule> updated = new ArrayList<>(rules);
        boolean removed = updated.removeIf(rule -> rule.text().equals(normalized));
        if (!removed) return false;
        rules = List.copyOf(updated);
        if (db != null) db.removeNameCleaningRule(normalized);
        return true;
    }

    /** Files whose name was cleaned at their ingestion since the start. */
    public long cleanedAtIngestion() {
        return cleanedAtIngestion.sum();
    }

    /** The file with its name cleaned, counted as cleaned at ingestion when it changed. */
    FileMetadata cleanForIngestion(FileMetadata file) {
        FileMetadata cleaned = clean(file);
        if (cleaned != file) cleanedAtIngestion.increment();
        return cleaned;
    }

    /** The file with its name cleaned; the same instance when the name does not change. */
    public FileMetadata clean(FileMetadata file) {
        if (file == null || file.name() == null) return file;
        String name = clean(file.name());
        return name.equals(file.name()) ? file : file.withName(name);
    }

    /** The name cleaned by the current rules; the same instance when nothing is to remove or the result is invalid. */
    public String clean(String name) {
        return clean(name, rules);
    }

    static String clean(String name, List<Rule> rules) {
        if (name == null || rules.isEmpty()) return name;
        String text = name;
        boolean changed = false;
        for (Rule rule : rules) {
            if (!rule.pattern().matcher(text).find()) continue;
            changed = true;
            // The groups holding the rule go whole; a space keeps apart the words they separated
            Matcher group = GROUP.matcher(text);
            StringBuilder out = new StringBuilder(text.length());
            int from = 0;
            while (group.find()) {
                Matcher inside = rule.pattern().matcher(text).region(group.start() + 1, group.end() - 1);
                if (!inside.find()) continue;
                out.append(text, from, group.start()).append(' ');
                from = group.end();
            }
            out.append(text, from, text.length());
            // Then the text itself, outside any group
            text = rule.pattern().matcher(out).replaceAll(" ");
        }
        if (!changed) return name;
        String cleaned = tidy(text);
        return cleaned == null || cleaned.equals(name) || !HandlerUtils.isValidFilename(cleaned) ? name : cleaned;
    }

    /**
     * A name once its rules removed: one space at most in a row, and neither spaces nor dangling separators at the
     * start or the end of the name before its extension, which stays as it is. Null if nothing is left of the name.
     */
    private static String tidy(String text) {
        String name = SPACES.matcher(text).replaceAll(" ").strip();
        int dot = name.lastIndexOf('.');
        String base = name, extension = "";
        if (dot >= 0 && isExtension(name, dot + 1)) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        base = trimSeparators(base);
        return base.isEmpty() ? null : base + extension;
    }

    /** Whether the end of {@code name} from {@code start} is an extension: 1 to 10 letters or digits. */
    private static boolean isExtension(String name, int start) {
        int length = name.length() - start;
        if (length < 1 || length > MAX_EXTENSION_LENGTH) return false;
        for (int i = start; i < name.length(); i++) {
            if (!Character.isLetterOrDigit(name.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Without the spaces and underscores at both ends, nor a dash left there apart from the words (" - " before a
     * title whose artist went): a dash stuck to a word ("-=Title=-") stays.
     */
    private static String trimSeparators(String base) {
        int start = 0, end = base.length();
        while (start < end) {
            char c = base.charAt(start);
            if (Character.isWhitespace(c) || c == '_') {
                start++;
            } else if (c == '-') {
                int run = start;
                while (run < end && base.charAt(run) == '-') run++;
                if (run < end && !Character.isWhitespace(base.charAt(run)) && base.charAt(run) != '_') break;
                start = run;
            } else {
                break;
            }
        }
        while (end > start) {
            char c = base.charAt(end - 1);
            if (Character.isWhitespace(c) || c == '_') {
                end--;
            } else if (c == '-') {
                int run = end;
                while (run > start && base.charAt(run - 1) == '-') run--;
                if (run > start && !Character.isWhitespace(base.charAt(run - 1)) && base.charAt(run - 1) != '_') break;
                end = run;
            } else {
                break;
            }
        }
        return base.substring(start, end);
    }
}
