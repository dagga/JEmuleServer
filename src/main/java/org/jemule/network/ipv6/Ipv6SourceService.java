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

package org.jemule.network.ipv6;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientState;
import org.jemule.core.ServerThreads;
import org.jemule.network.FirewallChecker;
import org.jemule.network.Server;
import org.jemule.network.handler.ClientContext;
import org.jemule.network.handler.FoundSource;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.SourceHandler;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * IPv6 sources, the server side of the extension agreed between eMuleQt, eNode-go, ed2k-server and aMule (aMule master
 * a6d6290be0, docs/IPv6_Server_Sources.md; ed2k-server README "IPv6"):
 * <ul>
 *   <li>OP_LOGINREQUEST may carry CT_MOD_IP_V6 {@code 0xAE} (TAGTYPE_HASH, 16 raw bytes): the client's IPv6 address,
 *   which also tells that the client reads IPv6 source records;</li>
 *   <li>OP_IDCHANGE announces {@link Tag#TCPFLG_IPV6} {@code 0x4000}, {@link Tag#TCPFLG_IPV6_UPDATE} {@code 0x8000}
 *   and {@link Tag#TCPFLG_IPV6_PROBE} {@code 0x400000};</li>
 *   <li>OP_IPV6_UPDATE {@code 0x63} {@code <IPv6 16>} changes the address after the login, all zeros withdraw it,
 *   no reply. aMule sends an address nothing proved yet only this way, to a server announcing the probe;</li>
 *   <li>the server dials the address back on the client's login TCP port with the HighID test
 *   ({@link FirewallChecker}: the OP_HELLOANSWER must carry the user hash that logged in), off the client's thread,
 *   at most {@link Ipv6Session#MAX_DIALS} times per connection, and sends the verdict in ST_IPV6_STATUS {@code 0xAB}
 *   of a new OP_SERVERIDENT;</li>
 *   <li>a reached address is published in OP_FOUNDSOURCES / OP_FOUNDSOURCES_OBFU as a record whose ID is
 *   {@link #SENTINEL_ID} {@code 0xFFFFFFFF}, followed by the 16 bytes of the address after every other field:
 *   {@code <ID 4><port 2>[<crypt options 1>[<user hash 16>]]<IPv6 16>} (aMule ParseServerSourceRecord).</li>
 * </ul>
 * As ed2k-server since 0.9.83, a record goes only to a LowID requester that reads them, and only for a source that is
 * LowID on IPv4: a HighID source is reachable over IPv4 already, and a HighID requester reaches a LowID source by
 * callback, which an unverified IPv6 path would take away. Nothing of this ever reaches OP_GLOBFOUNDSOURCES, the relay
 * or the other servers: {@code 0xFFFFFFFF} is above {@code 0x00FFFFFF} and would pass there as the HighID
 * 255.255.255.255.
 * <p>
 * Only with {@link ServerConfig#ipv6Sources()}, and never when the JVM runs without IPv6
 * ({@code java.net.preferIPv4Stack}): the address could not be dialled.
 */
public class Ipv6SourceService {
    private static final Logger log = LoggerFactory.getLogger(Ipv6SourceService.class);

    /** ID of an IPv6 source record: 255.255.255.255 is never a real peer. */
    public static final long SENTINEL_ID = 0xFFFFFFFFL;

    /** Dials an address back: true when the client with {@code userHash} answered on it. */
    @FunctionalInterface
    public interface Dialer {
        boolean reached(ClientContext context, InetAddress address, int port, byte[] userHash, int timeoutMs);
    }

    /** The HighID test of the login on the IPv6 address: OP_HELLO, and the user hash of the OP_HELLOANSWER. */
    public static final Dialer FIREWALL_CHECKER = Ipv6SourceService::dialWithFirewallChecker;

    /** The service of every connection: dials with {@link FirewallChecker}, publishes global unicast addresses only. */
    public static final Ipv6SourceService DEFAULT = new Ipv6SourceService(Ipv6SourceService::isGlobalUnicast,
            FIREWALL_CHECKER);

    private final Predicate<byte[]> usable;
    private final Dialer dialer;

    public Ipv6SourceService(Predicate<byte[]> usable, Dialer dialer) {
        this.usable = usable;
        this.dialer = dialer;
    }

    /** Whether the server speaks the extension: {@code ipv6Sources}, on a JVM with IPv6. */
    public static boolean enabled(ServerConfig config) {
        return config != null && config.ipv6Sources() && jvmHasIpv6();
    }

    /** False when the JVM runs with {@code java.net.preferIPv4Stack=true}: no IPv6 socket can be opened. */
    public static boolean jvmHasIpv6() {
        return !Boolean.getBoolean("java.net.preferIPv4Stack");
    }

    /** OP_IDCHANGE flags of the extension, 0 when it is off. */
    public static int tcpFlags(ServerConfig config) {
        return enabled(config) ? Tag.TCPFLG_IPV6 | Tag.TCPFLG_IPV6_UPDATE | Tag.TCPFLG_IPV6_PROBE : 0;
    }

    /**
     * Applies the login's CT_MOD_IP_V6, before OP_SERVERIDENT: an address held is reported there as
     * {@link Ipv6Session#STATUS_HELD} until its dial-back, which {@link #afterLogin} starts.
     */
    public void onLogin(ClientContext context) {
        ClientState state = context.getState();
        byte[] tag = state.ipv6().takeLoginTag();
        if (tag == null || !enabled(context.getConfig())) return;
        state.ipv6().announce(tag, usable);
    }

    /** Starts the dial-back of the login's address, once the login answers are sent. */
    public void afterLogin(ClientContext context) {
        startDial(context);
    }

    /**
     * OP_IPV6_UPDATE {@code <IPv6 16>}: no reply of its own; a new address is dialled back as at login. aMule reads
     * ST_IPV6_STATUS from every OP_SERVERIDENT, so one goes out with {@code 0x01} as the dial starts, then one with
     * the verdict: a client that only announces its address here (aMule's default, to a server that probes) learns
     * that the dial runs, and a client changing a reached address does not keep reading the old {@code 0x07}.
     */
    public void onUpdate(ClientContext context, byte[] data) {
        if (!enabled(context.getConfig())) return;
        ClientState state = context.getState();
        Ipv6Session.Action action = state.ipv6().announce(data, usable);
        log.debug("OP_IPV6_UPDATE from client {}: {}", state.clientId(), action);
        switch (action) {
            case DIAL -> {
                report(context);
                startDial(context);
            }
            case REPORT -> report(context);
            case NONE -> { }
        }
    }

    /** ST_IPV6_STATUS for OP_SERVERIDENT, null when the client holds no address (no tag then). */
    public Tag statusTag(ClientContext context) {
        ClientState state = context.getState();
        if (state == null || !enabled(context.getConfig())) return null;
        int status = state.ipv6().status();
        return status == 0 ? null : new Tag(Tag.TYPE_INT8, Tag.NAME_IPV6_STATUS, (byte) status);
    }

    /**
     * The OP_FOUNDSOURCES / _OBFU records of {@code sources} for the requester of the context: an IPv6 record for each
     * LowID source whose address was reached, when the requester reads them and is LowID; the usual record otherwise.
     */
    public List<FoundSource> foundSources(ClientContext context, List<ClientState> sources) {
        ClientState requester = context.getState();
        if (!readsRecords(context)) return FoundSource.of(sources);
        List<FoundSource> records = new ArrayList<>(sources.size());
        for (ClientState s : sources) {
            byte[] address = s.isHighId() || s.connectionId() == requester.connectionId()
                    ? null : s.ipv6().publishedAddress();
            if (address == null) {
                records.add(FoundSource.of(s));
            } else {
                records.add(new FoundSource(SENTINEL_ID, s.listenPort(), SourceHandler.cryptOptions(s), s.userHash(),
                        address));
            }
        }
        return records;
    }

    /** Whether IPv6 records may go to the requester: a LowID client that reads them, never another server. */
    private static boolean readsRecords(ClientContext context) {
        ClientState requester = context.getState();
        return requester != null && enabled(context.getConfig()) && requester.ipv6().readsRecords()
                && !requester.isHighId() && !context.isFromServer();
    }

    private void startDial(ClientContext context) {
        ClientState state = context.getState();
        Ipv6Session.Dial dial = state.ipv6().dialTarget();
        if (dial == null) return;
        InetAddress address;
        try {
            address = InetAddress.getByAddress(dial.address());
        } catch (UnknownHostException e) {
            return; // 16 bytes are always an address
        }
        // The HighID test's time, even when that test is disabled (0): this dial is the only proof of the address
        int seconds = context.getConfig().lowIdCheckTimeoutSeconds();
        int timeoutMs = (seconds > 0 ? seconds : ServerConfig.DEFAULT_LOWID_CHECK_TIMEOUT_SECONDS) * 1000;
        ServerThreads.start(ServerThreads.Tier.CLIENT, "ipv6-dial-" + state.connectionId(), () -> {
            boolean reached = state.listenPort() > 0
                    && dialer.reached(context, address, state.listenPort(), state.userHash(), timeoutMs);
            if (!state.ipv6().conclude(dial.generation(), reached)) return;
            log.info("IPv6 address of client {} {} on port {}", state.clientId(),
                    reached ? "reached" : "not reached", state.listenPort());
            report(context);
        });
    }

    /** A new OP_SERVERIDENT carrying the verdict, unless the connection is gone. */
    private static void report(ClientContext context) {
        ClientState state = context.getState();
        context.getRegistry().sendToConnection(state.connectionId(), LoginHandler.serverIdent(context));
    }

    private static boolean dialWithFirewallChecker(ClientContext context, InetAddress address, int port,
                                                   byte[] userHash, int timeoutMs) {
        Server server = context.getServer();
        byte[] serverHash = server != null ? server.getServerHash() : new byte[16];
        InetAddress serverIp = server != null ? server.getPublicIp() : null;
        int clientCaps = context.getState() != null ? context.getState().serverCaps() : 0;
        return new FirewallChecker(timeoutMs).isReachable(address, port, userHash, clientCaps, serverHash, serverIp,
                context.getConfig().port());
    }

    /**
     * Whether an announced address can be published: global unicast ({@code 2000::/3}). Never loopback, link-local,
     * unique local, multicast, IPv4-mapped or unspecified, whose records would send peers nowhere.
     */
    public static boolean isGlobalUnicast(byte[] address) {
        return address != null && address.length == 16 && (address[0] & 0xE0) == 0x20;
    }
}
