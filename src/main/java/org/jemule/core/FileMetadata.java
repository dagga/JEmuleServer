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


package org.jemule.core;

import org.jemule.protocol.Tag;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents metadata for a file indexed by the server.
 * Includes hash, name, size, type, and the collection of sources (clients) sharing it.
 *
 * @param hash    The 16-byte MD4 hash as a 32-character hex string.
 * @param name    The filename.
 * @param size    The file size in bytes.
 * @param type    The file category (e.g., Video, Audio, Archive).
 * @param tags    Additional metadata tags.
 * @param sources Map of client IDs to {@link ClientState} currently sharing this file.
 */
public record FileMetadata(
        String hash,
        String name,
        long size,
        String type,
        List<Tag> tags,
        ConcurrentHashMap<String, ClientState> sources
) {
    public FileMetadata(String hash, String name, long size, String type) {
        this(hash, name, size, type, List.of(), new ConcurrentHashMap<>());
    }

    public FileMetadata(String hash, String name, long size, String type, List<Tag> tags) {
        this(hash, name, size, type, tags, new ConcurrentHashMap<>());
    }
}
