package org.jemule.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class IPFilterUpdaterTest {

    @Test
    void bundledSourcesUseHttps() throws IOException {
        assertFalse(IPFilterUpdater.bundledSources().isEmpty());
        assertTrue(IPFilterUpdater.bundledSources().stream().allMatch(source -> source.getScheme().equals("https")));
        assertTrue(IPFilterUpdater.bundledSources().contains(URI.create("https://upd.emule-security.org/ipfilter.zip")));
    }

    @Test
    void downloadsAndAppliesTheFirstFilterFromZip(@TempDir Path tempDir) throws Exception {
        byte[] candidate = ("# update\n" + range(1) + "\n").getBytes(StandardCharsets.ISO_8859_1);
        HttpServer server = serve(zip(candidate));
        try {
            Path destination = tempDir.resolve("ipfilter.dat");
            IPFilter filter = new IPFilter();
            try (IPFilterUpdater updater = updater(filter, destination, server)) {
                assertTrue(updater.refreshNow());
                assertTrue(filter.isBlocked("11.0.1.1"));
                assertTrue(Files.readString(destination).contains(range(1)));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedDownloadLeavesCurrentFilterUntouchedAndIsTraced(@TempDir Path tempDir) throws Exception {
        List<String> oldFilter = ranges(5);
        Path destination = writeFilter(tempDir, oldFilter);
        byte[] originalFile = Files.readAllBytes(destination);
        IPFilter filter = new IPFilter();
        filter.loadFromFile(destination.toString());
        HttpServer server = serve("not an IP filter".getBytes(StandardCharsets.ISO_8859_1));
        try {
            try (IPFilterUpdater updater = updater(filter, destination, server)) {
                assertFalse(updater.refreshNow());
                assertArrayEquals(originalFile, Files.readAllBytes(destination));
                assertTrue(filter.isBlocked("11.0.0.1"));
                assertTrue(Files.readString(tempDir.resolve("ipfilter.dat.trace")).contains("Invalid IP filter line"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesUpdatesWithLessThanTenPercentRangeDifference(@TempDir Path tempDir) throws Exception {
        List<String> oldFilter = ranges(20);
        List<String> updatedFilter = new ArrayList<>(oldFilter);
        updatedFilter.add(range(20));
        Path destination = writeFilter(tempDir, oldFilter);
        IPFilter filter = new IPFilter();
        filter.loadFromFile(destination.toString());
        HttpServer server = serve(String.join("\n", updatedFilter).getBytes(StandardCharsets.ISO_8859_1));
        try {
            try (IPFilterUpdater updater = updater(filter, destination, server)) {
                assertTrue(updater.refreshNow());
                assertEquals(21, filter.getFilterCount());
                assertEquals(updatedFilter, Files.readAllLines(destination, StandardCharsets.ISO_8859_1));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsOldFilterAndWritesTraceWhenDifferenceIsAtLeastTenPercent(@TempDir Path tempDir) throws Exception {
        List<String> oldFilter = ranges(19);
        List<String> updatedFilter = new ArrayList<>(oldFilter.subList(0, 18));
        updatedFilter.add(range(100));
        Path destination = writeFilter(tempDir, oldFilter);
        byte[] originalFile = Files.readAllBytes(destination);
        IPFilter filter = new IPFilter();
        filter.loadFromFile(destination.toString());
        HttpServer server = serve(String.join("\n", updatedFilter).getBytes(StandardCharsets.ISO_8859_1));
        try {
            try (IPFilterUpdater updater = updater(filter, destination, server)) {
                assertFalse(updater.refreshNow());
                assertArrayEquals(originalFile, Files.readAllBytes(destination));
                assertTrue(filter.isBlocked("11.0.0.1"));
                assertFalse(filter.isBlocked("11.0.100.1"));
                String trace = Files.readString(tempDir.resolve("ipfilter.dat.trace"));
                assertTrue(trace.contains("Rejected update"));
                assertTrue(trace.contains("not below 10%"), trace);
            }
        } finally {
            server.stop(0);
        }
    }

    private static IPFilterUpdater updater(IPFilter filter, Path destination, HttpServer server) {
        return new IPFilterUpdater(filter, destination,
                List.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/filter")));
    }

    private static HttpServer serve(byte[] content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/filter", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] zip(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("ipfilter.dat"));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static Path writeFilter(Path directory, List<String> lines) throws IOException {
        Path destination = directory.resolve("ipfilter.dat");
        Files.write(destination, lines, StandardCharsets.ISO_8859_1);
        return destination;
    }

    private static List<String> ranges(int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(range(i));
        return lines;
    }

    private static String range(int index) {
        return String.format("011.000.%03d.001 - 011.000.%03d.001 , 000 , Test %d", index, index, index);
    }
}
