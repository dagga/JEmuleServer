package org.jemule.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FakeFileDetectorTest {

    @Test
    public void testMultipleExtensions() {
        FakeFileDetector detector = new FakeFileDetector();
        assertTrue(detector.isFake("12345678901234567890123456789012", "movie.mp4.exe", 1000));
        assertTrue(detector.isFake("12345678901234567890123456789012", "image.jpg.scr", 1000));
        assertTrue(detector.isFake("12345678901234567890123456789012", "document.pdf.vbs", 1000));
        assertFalse(detector.isFake("12345678901234567890123456789012", "archive.tar.gz", 1000)); // Legit
    }

    @Test
    public void testSuspiciousKeywords() {
        FakeFileDetector detector = new FakeFileDetector();
        assertTrue(detector.isFake("12345678901234567890123456789012", "cool_virus_remover.exe", 1000));
        assertTrue(detector.isFake("12345678901234567890123456789012", "winrar_crack_keygen.zip", 1000));
        assertFalse(detector.isFake("12345678901234567890123456789012", "my_vacation_photos.zip", 1000));
    }

    @Test
    public void testBlacklist() {
        FakeFileDetector detector = new FakeFileDetector();
        String badHash = "ABCDEF1234567890ABCDEF1234567890";
        detector.addBannedHash(badHash);
        assertTrue(detector.isFake(badHash, "legit_name.txt", 1000));
        assertFalse(detector.isFake("00000000000000000000000000000000", "legit_name.txt", 1000));
    }

    @Test
    public void testAbnormalCharacters() {
        FakeFileDetector detector = new FakeFileDetector();
        assertTrue(detector.isFake("12345678901234567890123456789012", "!!!$$$!!!", 100));
        assertFalse(detector.isFake("12345678901234567890123456789012", "file.txt", 100));
    }

    @Test
    public void testDisabled() {
        FakeFileDetector detector = new FakeFileDetector();
        detector.setEnabled(false);
        assertFalse(detector.isFake("12345678901234567890123456789012", "movie.mp4.exe", 1000));
    }
}
