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

package org.jemule.core;

/**
 * Origin of a file inserted into the index and the database, used to report what proportion of the new files
 * comes from each ingestion channel.
 */
public enum FileSource {
    /** A connected client published the file (OP_OFFERFILES). */
    CLIENT_PUSH("client push"),
    /** A forked Kad keyword search returned the file. */
    KAD_SEARCH("Kad search"),
    /** A search fanned out to verified eD2K servers returned the file. */
    SERVER_SEARCH("server search"),
    /** Any other origin (tests, administrative insertion). */
    UNKNOWN("other");

    private final String label;

    FileSource(String label) {
        this.label = label;
    }

    /** Human readable label for the administration interfaces. */
    public String label() {
        return label;
    }
}
