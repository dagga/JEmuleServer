package org.jemule.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IPFilterTest {

    @Test
    void testBasicBlocking() {
        IPFilter filter = new IPFilter();
        // Manually create some ranges by parsing strings if possible, 
        // or just use loadFromFile with a temp file
    }

    @Test
    void testParsingAndFiltering(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        String loopback = InetAddress.getLoopbackAddress().getHostAddress();
        Files.write(filterFile, List.of(
            "001.002.003.004 - 001.002.003.007 , 000 , Blocked Range 1",
            "192.168.1.1 - 192.168.1.1 , 000 , Single IP",
            "10.0.0.0 - 10.255.255.255 , 000 , Private Network",
            "  # Comment line",
            "",
            loopback + " - " + loopback + " , 000 , Localhost"
        ));

        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        assertEquals(4, filter.getFilterCount());

        // Test Range 1
        assertTrue(filter.isBlocked("1.2.3.4"));
        assertTrue(filter.isBlocked("1.2.3.5"));
        assertTrue(filter.isBlocked("1.2.3.6"));
        assertTrue(filter.isBlocked("1.2.3.7"));
        assertFalse(filter.isBlocked("1.2.3.3"));
        assertFalse(filter.isBlocked("1.2.3.8"));

        // Test Single IP
        assertTrue(filter.isBlocked("192.168.1.1"));
        assertFalse(filter.isBlocked("192.168.1.2"));
        assertFalse(filter.isBlocked("192.168.1.0"));

        // Test Large Range
        assertTrue(filter.isBlocked("10.0.0.0"));
        assertTrue(filter.isBlocked("10.123.45.67"));
        assertTrue(filter.isBlocked("10.255.255.255"));
        assertFalse(filter.isBlocked("11.0.0.0"));
        assertFalse(filter.isBlocked("9.255.255.255"));

        // Test Localhost
        assertTrue(filter.isBlocked(loopback));
    }

    @Test
    void testInvalidIps() {
        IPFilter filter = new IPFilter();
        assertFalse(filter.isBlocked("not.an.ip"));
        assertFalse(filter.isBlocked("999.999.999.999"));
    }

    @Test
    void rangesAtOrAboveTheFilterLevelAreAllowed(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of(
            "001.000.000.000 - 001.000.000.255 , 000 , Blocked",
            "002.000.000.000 - 002.000.000.255 , 099 , Blocked",
            "003.000.000.000 - 003.000.000.255 , 100 , Allowed",
            "004.000.000.000 - 004.000.000.255 , 127 , Allowed",
            "005.000.000.000 - 005.000.000.255"
        ));
        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        assertTrue(filter.isBlocked("1.0.0.1"));
        assertTrue(filter.isBlocked("2.0.0.1"));
        assertFalse(filter.isBlocked("3.0.0.1"));
        assertFalse(filter.isBlocked("4.0.0.1"));
        assertTrue(filter.isBlocked("5.0.0.1"), "a line without level blocks");
    }

    @Test
    void overlappingRangesAreAllFound(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of(
            "010.000.000.000 - 010.255.255.255 , 000 , Large range",
            "010.001.000.000 - 010.001.000.010 , 000 , Nested range",
            "010.200.000.000 - 011.000.000.255 , 000 , Overlapping range",
            "011.000.001.000 - 011.000.001.255 , 000 , Adjacent range"
        ));
        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());

        // Before the merge, 10.2.0.0 was only compared with the nested range 10.1.0.0-10.1.0.10
        assertTrue(filter.isBlocked("10.2.0.0"));
        assertTrue(filter.isBlocked("10.255.255.255"));
        assertTrue(filter.isBlocked("11.0.0.128"));
        assertTrue(filter.isBlocked("11.0.1.255"));
        assertFalse(filter.isBlocked("11.0.2.0"));
        assertEquals(1, filter.getFilterCount());
        assertEquals(List.of("10.0.0.0 - 11.0.1.255"), filter.getBlockedRanges());
    }

    @Test
    void hostNamesAreNeverResolved() {
        assertEquals(-1, IPFilter.ipToLong("localhost"));
        assertEquals(-1, IPFilter.ipToLong("1.2.3"));
        assertEquals(-1, IPFilter.ipToLong("::1"));
        assertEquals(0x01020304L, IPFilter.ipToLong("001.002.003.004"));
    }

    @Test
    void binaryCacheIsCreatedAndUsed(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Path binFile = tempDir.resolve("ipfilter.dat.bin");
        Files.write(filterFile, List.of(
            "001.002.003.004 - 001.002.003.007 , 000 , Blocked Range 1",
            "192.168.1.1 - 192.168.1.1 , 000 , Single IP"
        ));

        assertFalse(Files.exists(binFile));

        IPFilter filter1 = new IPFilter();
        filter1.loadFromFile(filterFile.toString());
        assertEquals(2, filter1.getFilterCount());
        assertTrue(filter1.isBlocked("1.2.3.5"));
        assertTrue(Files.exists(binFile));

        // Second load should use the binary cache
        IPFilter filter2 = new IPFilter();
        filter2.loadFromFile(filterFile.toString());
        assertEquals(2, filter2.getFilterCount());
        assertTrue(filter2.isBlocked("1.2.3.5"));
        assertTrue(filter2.isBlocked("192.168.1.1"));
        assertFalse(filter2.isBlocked("192.168.1.2"));
    }

    @Test
    void binaryCacheIsInvalidatedWhenDatFileChanges(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Path binFile = tempDir.resolve("ipfilter.dat.bin");
        Files.write(filterFile, List.of(
            "001.002.003.004 - 001.002.003.007 , 000 , Blocked Range 1"
        ));

        IPFilter filter1 = new IPFilter();
        filter1.loadFromFile(filterFile.toString());
        assertEquals(1, filter1.getFilterCount());
        assertTrue(Files.exists(binFile));

        // Modify dat file with an additional entry
        Thread.sleep(10); // Ensure timestamp changes if filesystem precision requires it
        Files.write(filterFile, List.of(
            "001.002.003.004 - 001.002.003.007 , 000 , Blocked Range 1",
            "010.000.000.001 - 010.000.000.005 , 000 , New Range"
        ));

        IPFilter filter2 = new IPFilter();
        filter2.loadFromFile(filterFile.toString());
        assertEquals(2, filter2.getFilterCount());
        assertTrue(filter2.isBlocked("10.0.0.3"));
    }

    @Test
    void corruptedBinaryCacheFallsBackToTextParsing(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Path binFile = tempDir.resolve("ipfilter.dat.bin");
        Files.write(filterFile, List.of(
            "001.002.003.004 - 001.002.003.007 , 000 , Blocked Range 1"
        ));

        // Create corrupt binary file
        Files.write(binFile, new byte[]{0, 1, 2, 3, 4, 5});

        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());
        assertEquals(1, filter.getFilterCount());
        assertTrue(filter.isBlocked("1.2.3.5"));
    }

    @Test
    void edgeCasesInParsing(@TempDir Path tempDir) throws IOException {
        Path filterFile = tempDir.resolve("ipfilter.dat");
        Files.write(filterFile, List.of(
            "// C++ style comment",
            "# Shell style comment",
            "   ",
            "invalid line without dash",
            "1.2.3.4 - 1.2.3.2 , 000 , Inverted range",
            "256.0.0.1 - 256.0.0.2 , 000 , Invalid IP",
            "001.001.001.001 - 001.001.001.001 , invalid_level , Should block",
            "002.002.002.002 - 002.002.002.002 , 050 , Blocked level 50"
        ));

        IPFilter filter = new IPFilter();
        filter.loadFromFile(filterFile.toString());
        assertEquals(2, filter.getFilterCount());
        assertTrue(filter.isBlocked("1.1.1.1"));
        assertTrue(filter.isBlocked("2.2.2.2"));
    }
}
