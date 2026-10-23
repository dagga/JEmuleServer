/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package org.jemule;

import org.jemule.config.ConfigFile;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.network.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    public static final String VERSION = "1.2.21";
    /** Server name shown in the clients' server list (ST_SERVERNAME). */
    public static final String SERVER_NAME = "JEmuleServer v" + VERSION + " (no pedo) (https://github.com/dagga/JEmuleServer/)";
    /** Server description shown in the clients' server list (ST_DESCRIPTION). */
    public static final String SERVER_DESCRIPTION = "eMule Server - version " + VERSION;
    public static final String ESERVER_VERSION = "17.16";
    /**
     * ESERVER_VERSION as the integer form of ST_VERSION: (major &lt;&lt; 16) | minor. The client displays it with
     * "%u.%02u" of (v &gt;&gt; 16, v &amp; 0xFFFF) (CUDPSocket::ProcessPacket, OP_SERVER_DESC_RES).
     */
    public static final int ESERVER_VERSION_INT = (17 << 16) | 16;

    public static void main(String[] args) {
        String configPath = null;
        if (args.length > 0 && !args[0].chars().allMatch(Character::isDigit)) {
            configPath = args[0];
        }

        ServerConfig cfg;
        Path configFile = null;
        Path configBaseDir = null;
        if (configPath == null && System.getProperty("jpackage.app-path") != null) {
            // Installed with the .deb/.rpm/.exe package and started without a configuration file: the install
            // directory is not writable, keep the configuration and the database in a per-user data directory.
            Path home = dataDirectory();
            configFile = prepareDataDirectory(home);
            configBaseDir = home;
            cfg = loadConfig(configFile.toString()).withPathsRelativeTo(home);
            log.info("Data directory: {}", home);
        } else {
            String path = configPath != null ? configPath : "server.properties";
            cfg = loadConfig(path);
            if (Files.exists(Paths.get(path))) {
                configFile = Paths.get(path);
            }
        }

        // Override port from CLI if provided as first or second argument
        for (String arg : args) {
            if (!arg.isEmpty() && arg.chars().allMatch(Character::isDigit)) {
                cfg = cfg.withPort(Integer.parseInt(arg));
                break; // Use the first integer found as port
            }
        }
        
        ClientFactory factory = new ClientFactory();
        Server server = new Server(cfg, factory);
        if (configFile != null) {
            server.setConfigFile(new ConfigFile(configFile, configBaseDir));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start();
        } catch (IOException e) {
            log.error("Startup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * %LOCALAPPDATA%\\JEmuleServer on Windows, ~/Library/Application Support/JEmuleServer on macOS,
     * $XDG_DATA_HOME/jemuleserver or ~/.local/share/jemuleserver elsewhere.
     */
    static Path dataDirectory() {
        return dataDirectory(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home"));
    }

    static Path dataDirectory(String osName, java.util.Map<String, String> env, String userHome) {
        String os = osName.toLowerCase(java.util.Locale.ROOT);
        String localAppData = env.get("LOCALAPPDATA");
        if (os.startsWith("windows") && localAppData != null) {
            return Paths.get(localAppData, "JEmuleServer");
        }
        if (os.startsWith("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "JEmuleServer");
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(userHome, ".local", "share");
        return base.resolve("jemuleserver");
    }

    /** Creates the data directory and, on first start, an editable copy of the default server.properties. */
    private static Path prepareDataDirectory(Path home) {
        Path config = home.resolve("server.properties");
        try {
            Files.createDirectories(home);
            if (!Files.exists(config)) {
                try (var is = Main.class.getClassLoader().getResourceAsStream("server.properties")) {
                    if (is != null) Files.copy(is, config);
                }
            }
        } catch (IOException e) {
            log.error("Cannot prepare data directory {}: {}", home, e.getMessage());
        }
        return config;
    }

    private static ServerConfig loadConfig(String pathStr) {
        Path path = Paths.get(pathStr);
        if (Files.exists(path)) {
            log.info("Loading configuration from {}", path.toAbsolutePath());
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                props.load(fis);
                return ServerConfig.fromProperties(props);
            } catch (IOException | IllegalArgumentException e) {
                log.error("Failed to load config from {}: {}. Using defaults.", pathStr, e.getMessage());
            }
        } else {
            // If a custom path was requested, do not try to load embedded resource
            if (!"server.properties".equals(pathStr)) {
                log.warn("Configuration file {} not found. Using defaults.", pathStr);
                return ServerConfig.DEFAULT;
            }

            // Try to load embedded server.properties from the classpath (inside the JAR)
            try (var is = Main.class.getClassLoader().getResourceAsStream("server.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    log.info("Loaded embedded server.properties from application resources.");
                    return ServerConfig.fromProperties(props);
                } else {
                    log.info("No server.properties found on filesystem or in application resources. Using default configuration.");
                }
            } catch (IOException | IllegalArgumentException e) {
                log.error("Failed to load embedded server.properties: {}. Using defaults.", e.getMessage());
            }
        }
        return ServerConfig.DEFAULT;
    }
}