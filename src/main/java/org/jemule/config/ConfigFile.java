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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The {@code server.properties} file of a running server: it is read for a reload and edited in place so that the
 * comments and the ordering of the file survive a change made from the admin interfaces.
 */
public final class ConfigFile {
    private final Path path;
    private final Path baseDir;

    public ConfigFile(Path path) {
        this(path, null);
    }

    /**
     * @param baseDir directory the relative file paths of the configuration are resolved against, as done at startup
     *                by {@link ServerConfig#withPathsRelativeTo(Path)}; null when they are kept as they are
     */
    public ConfigFile(Path path, Path baseDir) {
        this.path = path;
        this.baseDir = baseDir;
    }

    public Path path() {
        return path;
    }

    /** The configuration read from this file with the same path resolution as at startup. */
    public ServerConfig resolve(ServerConfig config) {
        return baseDir == null ? config : config.withPathsRelativeTo(baseDir);
    }

    public boolean exists() {
        return Files.exists(path);
    }

    /** The file content; an empty result when the file does not exist yet. */
    public Properties load() throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(path)) return properties;
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }

    /**
     * Writes the given key/value pairs into the file: an existing key is replaced in place (duplicated definitions
     * are dropped), an unknown key is appended. Comments and key order are preserved and the file is replaced
     * atomically.
     */
    public synchronized void update(Map<String, String> values) throws IOException {
        List<String> lines = Files.exists(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.ISO_8859_1))
                : new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            lines = replace(lines, entry.getKey(), entry.getKey() + "=" + escape(entry.getValue()));
        }
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = absolute.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.ISO_8859_1);
        try {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> replace(List<String> lines, String key, String replacement) {
        Pattern keyLine = Pattern.compile("^\\s*" + Pattern.quote(key) + "(?:\\s*[=:]|\\s+\\S)");
        List<String> result = new ArrayList<>(lines.size() + 1);
        boolean found = false;
        for (String line : lines) {
            if (keyLine.matcher(line).find()) {
                if (!found) {
                    result.add(replacement);
                    found = true;
                }
            } else {
                result.add(line);
            }
        }
        if (!found) {
            if (!result.isEmpty() && !result.get(result.size() - 1).isBlank()) {
                result.add("");
            }
            result.add(replacement);
        }
        return result;
    }

    /** Escapes a value the way {@link Properties#load(java.io.InputStream)} reads it back. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 0 && c == ' ') {
                out.append("\\ ");
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\f') {
                out.append("\\f");
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}