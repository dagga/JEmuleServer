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

import java.util.ArrayList;
import java.util.List;

/**
 * Result of applying a set of configuration changes to a running server.
 */
public final class ConfigApplyResult {
    private final List<String> applied;
    private final List<String> deferred;
    private final List<String> errors;

    private ConfigApplyResult(List<String> applied, List<String> deferred, List<String> errors) {
        this.applied = List.copyOf(applied);
        this.deferred = List.copyOf(deferred);
        this.errors = List.copyOf(errors);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The keys of the parameters that were successfully applied while the server was running. */
    public List<String> applied() {
        return applied;
    }

    /** The keys of the parameters whose new values require a server restart. */
    public List<String> deferred() {
        return deferred;
    }

    /** Errors encountered when applying the changes (validation failures, for example). */
    public List<String> errors() {
        return errors;
    }

    public boolean success() {
        return errors.isEmpty();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (!applied.isEmpty()) sb.append("applied=").append(join(applied)).append(' ');
        if (!deferred.isEmpty()) sb.append("restart-required=").append(join(deferred)).append(' ');
        if (!errors.isEmpty()) sb.append("errors=").append(join(errors));
        return sb.toString().trim();
    }

    private static String join(List<String> list) {
        return list.stream().sorted().reduce((a, b) -> a + ',' + b).orElse("");
    }

    public static final class Builder {
        private final List<String> applied = new ArrayList<>();
        private final List<String> deferred = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        public Builder applied(String key) {
            if (key != null) applied.add(key);
            return this;
        }

        public Builder deferred(String key) {
            if (key != null) deferred.add(key);
            return this;
        }

        public Builder error(String message) {
            if (message != null) errors.add(message);
            return this;
        }

        public ConfigApplyResult build() {
            return new ConfigApplyResult(applied, deferred, errors);
        }
    }
}
