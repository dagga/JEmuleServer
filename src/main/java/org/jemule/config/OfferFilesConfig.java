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
 * OFFERFILES v1 (doc/offerfiles-v1.md): the publication pace announced in OP_SERVERIDENT to the clients that ask for
 * it at login, and enforced on their OP_OFFERFILES.
 *
 * @param enabled              Whether clients asking for v1 are answered and held to it. When false, every client
 *                             publishes as on a legacy server.
 * @param batchMax             Most files a v1 client may offer in one OP_OFFERFILES ({@code offerfiles_batch_max}).
 * @param minIntervalMs        Least time, in milliseconds, between two full batches ({@code offerfiles_min_interval_ms}).
 * @param globalRecordsPerSec  Files per second accepted from all v1 clients together, 0 for no ceiling.
 */
public record OfferFilesConfig(boolean enabled, int batchMax, int minIntervalMs, int globalRecordsPerSec) {

    /** Opt-in; when enabled, the pace agreed with aMule and ed2k-server: 200 files every 500 ms. */
    public static final OfferFilesConfig DEFAULT = new OfferFilesConfig(false, 200, 500, 5000);

    /** Largest offerfiles_batch_max: it must stay below ST_HARDFILES. */
    public static final int MAX_BATCH = 100_000;

    public OfferFilesConfig {
        if (batchMax < 1 || batchMax > MAX_BATCH) {
            throw new IllegalArgumentException("offerFilesBatchMax must be between 1 and " + MAX_BATCH);
        }
        if (minIntervalMs < 1 || minIntervalMs > 3_600_000) {
            throw new IllegalArgumentException("offerFilesMinIntervalMs must be between 1 and 3600000");
        }
        if (globalRecordsPerSec < 0) {
            throw new IllegalArgumentException("offerFilesGlobalRecordsPerSec must be at least 0");
        }
    }

    /** Creates an OfferFilesConfig from a Properties object, using DEFAULT values for missing keys. */
    public static OfferFilesConfig fromProperties(Properties props) {
        return new OfferFilesConfig(
                Boolean.parseBoolean(props.getProperty("offerFilesV1Enabled", String.valueOf(DEFAULT.enabled())).trim()),
                Integer.parseInt(props.getProperty("offerFilesBatchMax", String.valueOf(DEFAULT.batchMax())).trim()),
                Integer.parseInt(props.getProperty("offerFilesMinIntervalMs", String.valueOf(DEFAULT.minIntervalMs())).trim()),
                Integer.parseInt(props.getProperty("offerFilesGlobalRecordsPerSec",
                        String.valueOf(DEFAULT.globalRecordsPerSec())).trim())
        );
    }
}
