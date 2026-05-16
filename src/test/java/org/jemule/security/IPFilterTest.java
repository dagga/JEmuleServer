package org.jemule.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.net.InetAddress;

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
}
