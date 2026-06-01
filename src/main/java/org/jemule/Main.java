/*
 * JEmuleServer - An experimental eMule server.
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
    public static final String VERSION = "1.0beta1";
    public static final String ESERVER_VERSION = "17.15";

    public static void main(String[] args) {
        String configPath = "server.properties";
        if (args.length > 0 && !args[0].chars().allMatch(Character::isDigit)) {
            configPath = args[0];
        }

        ServerConfig cfg = loadConfig(configPath);
        
        // Override port from CLI if provided as first or second argument
        for (String arg : args) {
            try {
                int port = Integer.parseInt(arg);
                cfg = new ServerConfig(
                        port,
                        cfg.maxPacketSize(),
                        cfg.maxSearchResults(),
                        cfg.floodMaxRequestsPerSecond(),
                        cfg.maxUsers(),
                        cfg.maxFiles(),
                        cfg.maxFilesPerUser(),
                        cfg.maxSourcesPerFile(),
                        cfg.databasePath(),
                        cfg.ipFilterPath(),
                        cfg.fakeFileDetectionEnabled(),
                        cfg.cbFailureRateThreshold(),
                        cfg.cbMinimumNumberOfCalls(),
                        cfg.cbWaitDurationInSeconds(),
                        cfg.tcpKeepAliveTimeoutInSeconds(),
                        cfg.heartbeatIntervalSeconds(),
                        cfg.publicIp()
                );
                break; // Use the first integer found as port
            } catch (NumberFormatException ignored) {}
        }
        
        ClientFactory factory = new ClientFactory();
        Server server = new Server(cfg, factory);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start();
        } catch (IOException e) {
            log.error("Startup failed: {}", e.getMessage(), e);
        }
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