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

import java.io.IOException;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    public static final String VERSION = "0.3.2";

    public static void main(String[] args) {
        ServerConfig cfg = ServerConfig.DEFAULT;
        if (args.length > 0) {
            try {
                        cfg = new ServerConfig(
                                Integer.parseInt(args[0]),
                                2 * 1024 * 1024,
                                300,
                                50,
                                ServerConfig.DEFAULT.maxUsers(),
                                ServerConfig.DEFAULT.maxFiles(),
                                ServerConfig.DEFAULT.maxFilesPerUser(),
                                ServerConfig.DEFAULT.maxSourcesPerFile(),
                                ServerConfig.DEFAULT.databasePath(),
                                ServerConfig.DEFAULT.cbFailureRateThreshold(),
                                ServerConfig.DEFAULT.cbMinimumNumberOfCalls(),
                                ServerConfig.DEFAULT.cbWaitDurationInSeconds()
                        );
            } catch (NumberFormatException e) {
                log.error("Usage: java -jar JEmuleServer.jar [port]");
                return;
            }
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
}
