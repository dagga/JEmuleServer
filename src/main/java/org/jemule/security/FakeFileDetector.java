package org.jemule.security;

import org.jemule.core.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristics-based detector for fake or malicious files.
 */
public class FakeFileDetector {
    private static final Logger log = LoggerFactory.getLogger(FakeFileDetector.class);

    // Common patterns for fake files in eMule networks
    private static final Pattern MULTIPLE_EXTENSIONS = Pattern.compile("\\.[a-z0-9]+\\.(exe|vbs|scr|bat|cmd|js|pif)$", Pattern.CASE_INSENSITIVE);
    private static final String[] SUSPICIOUS_KEYWORDS = {
            "virus", "worm", "trojan", "crack", "keygen", "serial", "freeporn", "spam",
            "password_list", "account_hacker", "credit_card", "cheat_engine", "private_video"
    };

    private final Set<String> bannedHashes = new HashSet<>();
    private DatabaseManager db;
    private boolean enabled = true;

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
        if (!enabled) return false;

        String upperHash = hash.toUpperCase();

        // 1. Check Blacklist
        if (bannedHashes.contains(upperHash)) {
            log.debug("File rejected: Hash {} is blacklisted", hash);
            return true;
        }

        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase();

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
                if (lowerName.contains(keyword)) {
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
            // Don't auto-ban in-memory if we're just checking
            // addBannedHash(upperHash); 
            if (db != null) {
                db.addBannedHash(upperHash, "Auto-banned: " + reason + " (Name: " + name + ")");
                addBannedHash(upperHash); // Now add it to memory if DB persist succeeded (or at least was called)
            }
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

    public void addBannedHash(String hash) {
        if (hash != null && hash.length() == 32) {
            bannedHashes.add(hash.toUpperCase());
        }
    }
    
    public void clearBlacklist() {
        bannedHashes.clear();
    }
}
