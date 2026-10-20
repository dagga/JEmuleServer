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

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates every thread of the server in one of five tiers, highest priority first:
 * <ol>
 *     <li>{@link Tier#ADMIN}: admin console, admin port and web panel, which must stay usable under load;</li>
 *     <li>{@link Tier#CLIENT}: TCP and UDP access of the eD2K clients (virtual threads, normal priority);</li>
 *     <li>{@link Tier#SEARCH}: searches forked to Kad and to the eD2K servers;</li>
 *     <li>{@link Tier#INGESTION}: indexing and persisting files, which must never hold a search or a client back;</li>
 *     <li>{@link Tier#MAINTENANCE}: regular operations (Kad maintenance, gossip polling, list updates, loading).</li>
 * </ol>
 * Only platform threads have a priority: the client tier uses virtual threads, which all run at the normal priority
 * of their carrier threads, so the other tiers use platform threads placed above or below it. Virtual threads are not
 * preempted, which on a small server (one vCPU: one carrier) lets a long task starve the others: searches, ingestion
 * and maintenance therefore never run on them.
 * <p>
 * On Linux the JVM ignores thread priorities unless started with {@code -XX:ThreadPriorityPolicy=1}; lowering a
 * priority then works for any user, raising it above normal needs root or {@code CAP_SYS_NICE}.
 */
public final class ServerThreads {

    /** Priority tiers of the server threads. */
    public enum Tier {
        ADMIN(Thread.MAX_PRIORITY),
        CLIENT(Thread.NORM_PRIORITY),
        SEARCH(Thread.NORM_PRIORITY - 1),
        INGESTION(Thread.NORM_PRIORITY - 2),
        MAINTENANCE(Thread.MIN_PRIORITY);

        public final int priority;

        Tier(int priority) {
            this.priority = priority;
        }
    }

    private ServerThreads() {}

    /** Starts a daemon platform thread of the given tier. */
    public static Thread start(Tier tier, String name, Runnable task) {
        if (tier == Tier.CLIENT) return Thread.ofVirtual().name(name).start(task);
        return Thread.ofPlatform().name(name).daemon(true).priority(tier.priority).start(task);
    }

    /** Thread factory of the given tier, naming its threads {@code <prefix>-<n>}. */
    public static ThreadFactory factory(Tier tier, String prefix) {
        if (tier == Tier.CLIENT) return Thread.ofVirtual().name(prefix + "-", 1).factory();
        AtomicInteger counter = new AtomicInteger();
        return task -> Thread.ofPlatform().name(prefix + "-" + counter.incrementAndGet()).daemon(true)
                .priority(tier.priority).unstarted(task);
    }

    /** Executor of the client tier: one virtual thread per task. */
    public static ExecutorService clientExecutor() {
        return Executors.newThreadPerTaskExecutor(factory(Tier.CLIENT, "client"));
    }

    /** Executor of the admin tier, for the web panel and admin port requests. */
    public static ExecutorService adminExecutor(String prefix) {
        return Executors.newCachedThreadPool(factory(Tier.ADMIN, prefix));
    }

    /** Single-threaded scheduler of the maintenance tier. */
    public static ScheduledExecutorService maintenanceScheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(factory(Tier.MAINTENANCE, name));
    }

    /**
     * Whether the operating system applies the thread priorities: always outside Linux, and on Linux only with
     * {@code -XX:ThreadPriorityPolicy=1}.
     */
    public static boolean prioritiesApplied() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return true;
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(argument -> argument.replace(" ", "").equals("-XX:ThreadPriorityPolicy=1"));
    }
}
