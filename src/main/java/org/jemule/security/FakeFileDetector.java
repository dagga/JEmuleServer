package org.jemule.security;

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
            "virus", "worm", "trojan", "crack", "keygen", "serial", "freeporn", "spam"
    };

    private final Set<String> bannedHashes = new HashSet<>();
    private boolean enabled = true;

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

        // 1. Check Blacklist
        if (bannedHashes.contains(hash.toUpperCase())) {
            log.debug("File rejected: Hash {} is blacklisted", hash);
            return true;
        }

        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase();

        // 2. Multiple Extensions (e.g., Movie.mp4.exe)
        if (MULTIPLE_EXTENSIONS.matcher(lowerName).find()) {
            log.info("File rejected: Multiple extensions in '{}'", name);
            return true;
        }

        // 3. Suspicious Keywords
        for (String keyword : SUSPICIOUS_KEYWORDS) {
            if (lowerName.contains(keyword)) {
                log.info("File rejected: Suspicious keyword '{}' in '{}'", keyword, name);
                return true;
            }
        }

        // 4. Heuristic: Large file with executable extension
        if (size > 50 * 1024 * 1024 && (lowerName.endsWith(".exe") || lowerName.endsWith(".scr"))) {
             // Most legitimate eMule executables aren't huge, but this is a soft heuristic
             // Lugdunum often limited this. Let's be cautious but it's a common sign of a "wrapped" malware.
             // We'll stick to a higher limit for now or just skip it if too risky.
        }
        
        // 5. Short names with many special characters (spam)
        if (name.length() < 10 && countSpecialChars(name) > 4) {
             log.info("File rejected: Abnormal character density in '{}'", name);
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
