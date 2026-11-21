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

import java.util.Properties;

/**
 * Server-coordinated NAT traversal between LowID clients (aMule master a6d6290be0, {@code docs/NAT_T_Protocol.md}).
 * Every part is opt-in and off by default.
 *
 * @param natCallback Lugdunum eserver's LowID-to-LowID NAT callback on this server: clients setting
 *                    {@code SRVCAP_NATTRAVERSAL} (0x1000) in {@code CT_SERVER_FLAGS} get an odd LowID and the 24-byte
 *                    OP_IDCHANGE, and may send UDP 0x9F keepalives and 0xA6 callback requests (key
 *                    {@code natCallback}).
 */
public record NatTraversalConfig(boolean natCallback) {

    public static final NatTraversalConfig DEFAULT = new NatTraversalConfig(false);

    /** Creates a NatTraversalConfig from a Properties object, using DEFAULT values for missing keys. */
    public static NatTraversalConfig fromProperties(Properties props) {
        return new NatTraversalConfig(
                Boolean.parseBoolean(props.getProperty("natCallback", String.valueOf(DEFAULT.natCallback())).trim())
        );
    }
}
