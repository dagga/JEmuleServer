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

package org.jemule.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {

    @Test
    void h2ByDefault() {
        ServerConfig config = ServerConfig.fromProperties(new Properties());
        assertEquals(DatabaseConfig.Type.H2, config.database().type());
        assertFalse(config.database().isMySql());
        assertEquals("h2", config.toProperties().get("databaseType"));
        assertEquals(DatabaseConfig.DEFAULT, ServerConfig.DEFAULT.database());
    }

    @Test
    void mySqlSettingsAreReadAndWritten() {
        Properties props = new Properties();
        props.setProperty("databaseType", "MySQL");
        props.setProperty("databaseUrl", " jdbc:mysql://db.example.net:3306/jemule ");
        props.setProperty("databaseUser", "jemule");
        props.setProperty("databasePassword", "secret");
        props.setProperty("databaseImportFromH2", "false");
        ServerConfig config = ServerConfig.fromProperties(props);
        DatabaseConfig database = config.database();
        assertTrue(database.isMySql());
        assertEquals("jdbc:mysql://db.example.net:3306/jemule", database.url());
        assertEquals("jemule", database.user());
        assertEquals("secret", database.effectivePassword());
        assertFalse(database.importFromH2());

        Properties written = new Properties();
        config.toProperties().forEach(written::setProperty);
        assertEquals(database, ServerConfig.fromProperties(written).database(), "kept by a settings change");
        assertFalse(database.toString().contains("secret"));
        assertFalse(config.toString().contains("secret"));
        assertEquals(database, config.withPort(4662).withAdminWebPort(0).database());
    }

    @Test
    void mySqlNeedsAJdbcUrlAndTheTypeIsChecked() {
        Properties props = new Properties();
        props.setProperty("databaseType", "mysql");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
        props.setProperty("databaseUrl", "mysql://host/db");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
        assertThrows(IllegalArgumentException.class, () -> ConfigParameter.DATABASE_TYPE.validate("postgres"));
        ConfigParameter.DATABASE_TYPE.validate("mysql");
        ConfigParameter.DATABASE_TYPE.validate("H2");
    }

    @Test
    void connectorSettingsAreAddedUnlessTheUrlSetsThem() {
        DatabaseConfig plain = new DatabaseConfig(DatabaseConfig.Type.MYSQL, "jdbc:mysql://h/db", "u", "", true);
        assertEquals("jdbc:mysql://h/db?rewriteBatchedStatements=true&tcpKeepAlive=true&connectTimeout=10000"
                + "&characterEncoding=UTF-8", plain.jdbcUrl());
        DatabaseConfig tuned = new DatabaseConfig(DatabaseConfig.Type.MYSQL,
                "jdbc:mysql://h/db?sslMode=REQUIRED&connectTimeout=3000&rewriteBatchedStatements=false", "u", "", true);
        assertEquals("jdbc:mysql://h/db?sslMode=REQUIRED&connectTimeout=3000&rewriteBatchedStatements=false"
                + "&tcpKeepAlive=true&characterEncoding=UTF-8", tuned.jdbcUrl());
    }
}
