package org.jemule.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(detector.isFake("12345678901234567890123456789012", "bank_account_hacker.exe", 1000));
        assertFalse(detector.isFake("12345678901234567890123456789012", "my_vacation_photos.zip", 1000));
    }

    @Test
    public void testBannedNames() {
        FakeFileDetector detector = new FakeFileDetector();
        assertEquals("bad name.avi", detector.ban("  Bad Name.AVI ", "test"));
        assertTrue(detector.isFake("ABCDEF1234567890ABCDEF1234567890", "bad name.avi", 1000));
        assertTrue(detector.isFake("00000000000000000000000000000000", "BAD NAME.avi", 1000), "whatever the hash");
        assertFalse(detector.isFake("00000000000000000000000000000000", "bad name 2.avi", 1000));
        assertNull(detector.ban("   ", "blank"));
        assertNull(detector.ban("x".repeat(FakeFileDetector.MAX_NAME_LENGTH + 1), "too long"));
        assertTrue(detector.unban("Bad name.avi"));
        assertFalse(detector.isFake("00000000000000000000000000000000", "bad name.avi", 1000));
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

    @Test
    public void keywordsMatchWholeWordsOnly() {
        FakeFileDetector detector = new FakeFileDetector();
        assertFalse(detector.isFake("12345678901234567890123456789012", "Crackerjack (2002).avi", 1000));
        assertFalse(detector.isFake("12345678901234567890123456789012", "Earthworm Jim.iso", 1000));
        assertFalse(detector.isFake("12345678901234567890123456789012", "Spamalot - Original Cast.mp3", 1000));
        assertTrue(detector.isFake("12345678901234567890123456789012", "Office 2010 Crack.zip", 1000));
        assertTrue(detector.isFake("12345678901234567890123456789012", "credit-card generator.exe", 1000));
    }

    @Test
    public void heuristicsDoNotBanTheHash() {
        FakeFileDetector detector = new FakeFileDetector();
        String hash = "12345678901234567890123456789012";
        assertTrue(detector.isFake(hash, "movie.mp4.exe", 1000));
        assertFalse(detector.isFake(hash, "movie.mp4", 1000), "the same file under a legitimate name is accepted");
    }
}
