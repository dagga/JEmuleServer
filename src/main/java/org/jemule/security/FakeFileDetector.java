package org.jemule.security;

import org.jemule.core.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jemule.core.Keywords;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Heuristics-based detector for fake or malicious files, and the banned file names. A file matching a heuristic is
 * rejected without being banned: a heuristic can be wrong. A banned name is rejected for good, whatever the hash and
 * even with the heuristics disabled; names are compared in lower case, without the spaces around them.
 */
public class FakeFileDetector {
    private static final Logger log = LoggerFactory.getLogger(FakeFileDetector.class);

    // Common patterns for fake files in eMule networks
    private static final Pattern MULTIPLE_EXTENSIONS = Pattern.compile("\\.[a-z0-9]+\\.(exe|vbs|scr|bat|cmd|js|pif)$", Pattern.CASE_INSENSITIVE);
    /** Whole words or phrases (words separated by spaces): "crack" does not match "Crackerjack". */
    private static final String[] SUSPICIOUS_KEYWORDS = {
            "virus", "worm", "trojan", "crack", "keygen", "serial", "freeporn", "spam",
            "password list", "account hacker", "credit card", "cheat engine", "private video"
    };

    /** Longest banned name: the files table keeps names of up to 1024 characters. */
    public static final int MAX_NAME_LENGTH = 1024;

    // Written by the admin console and read by every client thread, the Kad and the server search bridges
    private final Set<String> bannedNames = ConcurrentHashMap.newKeySet();
    private volatile DatabaseManager db;
    private volatile boolean enabled = true;

    public void setDatabaseManager(DatabaseManager db) {
        this.db = db;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks if a file should be considered fake or malicious based on its metadata.
     *
     * @param hash The MD4 hash of the file (32-char hex).
     * @param name The filename.
     * @param size The file size in bytes.
     * @return True if the file is detected as fake/malicious.
     */
    public boolean isFake(String hash, String name, long size) {
        // 1. Banned names are rejected even when the heuristics are disabled
        if (isBanned(name)) {
            log.debug("File rejected: name '{}' is banned (hash={})", name, hash);
            return true;
        }
        if (!enabled) return false;

        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase();
        String words = " " + String.join(" ", Keywords.split(name)) + " ";

        boolean suspicious = false;
        String reason = "";

        // 2. Multiple Extensions (e.g., Movie.mp4.exe)
        if (MULTIPLE_EXTENSIONS.matcher(lowerName).find()) {
            suspicious = true;
            reason = "Multiple extensions";
        }

        // 3. Suspicious Keywords
        if (!suspicious) {
            for (String keyword : SUSPICIOUS_KEYWORDS) {
                if (words.contains(" " + keyword + " ")) {
                    suspicious = true;
                    reason = "Suspicious keyword: " + keyword;
                    break;
                }
            }
        }

        // 4. Short names with many special characters (spam)
        if (!suspicious && name.length() < 10 && countSpecialChars(name) > 4) {
            suspicious = true;
            reason = "Abnormal character density";
        }

        if (suspicious) {
            log.info("File rejected: {} in '{}' (hash={})", reason, name, hash);
            return true;
        }

        return false;
    }

    private int countSpecialChars(String s) {
        int count = 0;
        for (char c : s.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) {
                count++;
            }
        }
        return count;
    }

    /** A name as compared and stored: lower case, without the spaces around it; null when blank or too long. */
    public static String normalize(String name) {
        if (name == null) return null;
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH ? null : normalized;
    }

    /**
     * Bans a file name for good: kept in memory and stored in the database, so that the ban survives a restart.
     *
     * @return the name as banned, null when the name is blank or too long
     */
    public String ban(String name, String reason) {
        String normalized = normalize(name);
        if (normalized == null) return null;
        bannedNames.add(normalized);
        DatabaseManager database = db;
        if (database != null) {
            database.addBannedName(normalized, reason == null || reason.length() <= 255 ? reason : reason.substring(0, 255));
        }
        return normalized;
    }

    /** Loads a name banned earlier (from the database), without storing it again. */
    public void addBannedName(String name) {
        String normalized = normalize(name);
        if (normalized != null) bannedNames.add(normalized);
    }

    /** Removes a ban, in memory and in the database. Returns true when the name was banned. */
    public boolean unban(String name) {
        String normalized = normalize(name);
        if (normalized == null) return false;
        boolean removed = bannedNames.remove(normalized);
        DatabaseManager database = db;
        if (database != null) {
            database.removeBannedName(normalized);
        }
        return removed;
    }

    /** True when the name is banned (independently of the heuristics switch). */
    public boolean isBanned(String name) {
        if (bannedNames.isEmpty()) return false;
        String normalized = normalize(name);
        return normalized != null && bannedNames.contains(normalized);
    }

    /** The banned names, sorted. */
    public List<String> bannedNames() {
        return bannedNames.stream().sorted().toList();
    }

    public int bannedNameCount() {
        return bannedNames.size();
    }

    public void clearBlacklist() {
        bannedNames.clear();
    }
}
