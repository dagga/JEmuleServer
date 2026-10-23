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

import org.jemule.config.OfferFilesConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OFFERFILES v1 terms of one connection (doc/offerfiles-v1.md), taken at login from the values advertised in its
 * OP_SERVERIDENT and enforced for the connection's whole life: a configuration change reaches new logins only.
 *
 * @param softFiles        ST_SOFTFILES: distinct files indexed for the connection.
 * @param hardFiles        ST_HARDFILES: an OP_OFFERFILES must declare fewer files.
 * @param batchMax         offerfiles_batch_max: most files in one OP_OFFERFILES.
 * @param minIntervalMs    offerfiles_min_interval_ms: least time between two full batches.
 * @param pacer            The connection's bucket: one batch, refilled every {@code minIntervalMs}.
 * @param oversizeReported Whether the client was already told that a batch above {@code batchMax} was ignored.
 */
public record OfferFilesSession(int softFiles, int hardFiles, int batchMax, int minIntervalMs, OfferPacer pacer,
                                AtomicBoolean oversizeReported) {

    public static OfferFilesSession of(int softFiles, int hardFiles, OfferFilesConfig config) {
        return new OfferFilesSession(softFiles, hardFiles, config.batchMax(), config.minIntervalMs(),
                new OfferPacer(config.batchMax(), config.minIntervalMs() * 1_000_000L), new AtomicBoolean());
    }

    /** The advertisement is all or nothing: the contract requires these values, otherwise the server stays legacy. */
    public boolean isValid() {
        return softFiles > 0 && batchMax > 0 && minIntervalMs > 0 && hardFiles > batchMax;
    }
}
