package org.jemule.security;

import org.jemule.core.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jemule.core.Keywords;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Heuristics-based detector for fake or malicious files. A file matching a heuristic is rejected, but its hash is
 * not banned: a heuristic can be wrong, and the same file must be accepted under a legitimate name. Only the
 * hashes explicitly banned stay rejected under any name.
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

    // Written by the admin console and read by every client thread
    private final Set<String> bannedHashes = ConcurrentHashMap.newKeySet();
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
        String upperHash = hash.toUpperCase();

        // 1. Check Blacklist: banned hashes are rejected even when the heuristics are disabled
        if (bannedHashes.contains(upperHash)) {
            log.debug("File rejected: Hash {} is blacklisted", hash);
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

    /** Bans a hash for good: kept in memory and stored in the database, so that the ban survives a restart. */
    public void ban(String hash, String reason) {
        addBannedHash(hash);
        DatabaseManager database = db;
        if (database != null) {
            database.addBannedHash(hash.toUpperCase(), reason.length() > 255 ? reason.substring(0, 255) : reason);
        }
    }

    public void addBannedHash(String hash) {
        if (hash != null && hash.length() == 32) {
            bannedHashes.add(hash.toUpperCase());
        }
    }

    /** Removes a ban, in memory and in the database. Returns true when the hash was banned. */
    public boolean unban(String hash) {
        if (hash == null || hash.length() != 32) return false;
        boolean removed = bannedHashes.remove(hash.toUpperCase());
        DatabaseManager database = db;
        if (database != null) {
            database.removeBannedHash(hash.toUpperCase());
        }
        return removed;
    }

    /** True when the hash is banned (independently of the heuristics switch). */
    public boolean isBanned(String hash) {
        return hash != null && hash.length() == 32 && bannedHashes.contains(hash.toUpperCase());
    }
    
    public void clearBlacklist() {
        bannedHashes.clear();
    }
}
