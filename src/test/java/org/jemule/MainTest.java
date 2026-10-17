package org.jemule;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Data directory of a server installed from a package and started without a configuration file. */
class MainTest {

    @Test
    void dataDirectoryFollowsTheConventionOfEachSystem() {
        assertEquals(Paths.get("C:\\Users\\bob\\AppData\\Local", "JEmuleServer"),
                Main.dataDirectory("Windows 11", Map.of("LOCALAPPDATA", "C:\\Users\\bob\\AppData\\Local"), "C:\\Users\\bob"));
        assertEquals(Paths.get("/Users/bob", "Library", "Application Support", "JEmuleServer"),
                Main.dataDirectory("Mac OS X", Map.of(), "/Users/bob"));
        assertEquals(Paths.get("/home/bob/.local/share/jemuleserver"),
                Main.dataDirectory("Linux", Map.of(), "/home/bob"));
        assertEquals(Paths.get("/data/xdg/jemuleserver"),
                Main.dataDirectory("Linux", Map.of("XDG_DATA_HOME", "/data/xdg"), "/home/bob"));
    }
}
