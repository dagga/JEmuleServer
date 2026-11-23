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

package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientBans;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.HashPrefixFilter;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.PublishHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The clients refused besides the IP filter: by nickname at login ({@code bannedClientNicks}), and for a day when they
 * publish a file of a banned hash prefix ({@code banClientsPublishingBannedHashes}), as eMule-Qt clients share the
 * made-up hashes of the emule-qt.org bridges.
 */
class ClientBansTest {
    private static final ServerConfig BASE = new ServerConfig(4661, 2 * 1024 * 1024, 300, 50, 100, 1000, 5000, 200,
            null, null, true, 50f, 10, 60, 300, 120, null, 0);
    private static final byte[] GATEWAY = EmuleTestSupport.hash("ED2B011200008A8F033CDCEF7349E8EB");
    private static final byte[] REAL = EmuleTestSupport.hash("12345678901234567890123456789012");

    private final ClientRegistry registry = new ClientRegistry();
    private FileIndex index;

    @BeforeEach
    void open() {
        index = new FileIndex(null);
        index.setBannedHashPrefixes(() -> HashPrefixFilter.DEFAULT);
    }

    @AfterEach
    void close() {
        index.close();
    }

    /** A login of {@code nick} from {@code ip}; the context tells whether it was disconnected. */
    private EmuleTestSupport.FakeContext login(ServerConfig config, String ip, String nick) throws Exception {
        var ctx = new EmuleTestSupport.FakeContext(new EmuleTestSupport.DummySocket(InetAddress.getByName(ip), 50000),
                config, registry, index);
        byte[] login = EmuleTestSupport.loginRequest(EmuleTestSupport.hash("00112233445566778899aabbccddeeff"), 4662,
                nick, EmuleTestSupport.EMULE_DEFAULT_SRVCAPS);
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, login),
                new ByteArrayOutputStream());
        return ctx;
    }

    private static void offer(EmuleTestSupport.FakeContext ctx, ByteArrayOutputStream out) throws Exception {
        byte[] payload = EmuleTestSupport.offerFiles(EmuleTestSupport.completeFile(REAL, "real.avi", 1024),
                EmuleTestSupport.completeFile(GATEWAY, "torrent download.mp4", 2048));
        new PublishHandler().handlePublish(ctx, OpCode.OFFER_FILES, payload, out);
    }

    @Test
    void aBannedNicknameIsComparedWholeAndWithoutCase() {
        String banned = "https://emule-qt.org, Other Bot ";
        assertTrue(ClientBans.nickBanned("https://emule-qt.org", banned));
        assertTrue(ClientBans.nickBanned(" HTTPS://eMule-Qt.org ", banned));
        assertTrue(ClientBans.nickBanned("other bot", banned));
        assertFalse(ClientBans.nickBanned("https://emule-qt.org/fan", banned), "whole nicknames only");
        assertFalse(ClientBans.nickBanned("emule-qt", banned));
        assertFalse(ClientBans.nickBanned("", banned));
        assertFalse(ClientBans.nickBanned("https://emule-qt.org", ""));
        assertFalse(ClientBans.nickBanned(null, banned));
    }

    @Test
    void theLoginOfABannedNicknameIsRefused() throws Exception {
        ServerConfig config = BASE.withClientBans("https://emule-qt.org", true);
        var refused = login(config, "10.0.0.2", "https://eMule-Qt.org");
        assertTrue(refused.isDisconnected());
        assertEquals(0, registry.size());
        assertEquals(1, registry.clientBans().nickLoginsRefused());

        var accepted = login(config, "10.0.0.3", "an eMule user");
        assertFalse(accepted.isDisconnected());
        assertEquals(1, registry.size());

        var noBan = login(BASE, "10.0.0.4", "https://emule-qt.org");
        assertFalse(noBan.isDisconnected(), "no nickname banned by default");
    }

    @Test
    void aClientPublishingABannedHashIsDisconnectedAndItsIpRefusedForADay() throws Exception {
        assertTrue(BASE.banClientsPublishingBannedHashes(), "on by default");
        var ctx = EmuleTestSupport.loggedInContext("10.0.0.5", 50000, BASE, registry, index);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        offer(ctx, out);

        assertTrue(ctx.isDisconnected());
        assertEquals(0, index.fileCount(), "none of the files of the offer is indexed");
        assertEquals(0, out.size(), "no message");
        ClientBans bans = registry.clientBans();
        assertEquals(1, bans.publishersBanned());
        InetAddress ip = InetAddress.getByName("10.0.0.5");
        long now = System.currentTimeMillis();
        assertTrue(bans.isBanned(ip, now));
        assertTrue(bans.isBanned(ip, now + ClientBans.BAN_MILLIS - 60_000));
        assertFalse(bans.isBanned(InetAddress.getByName("10.0.0.6"), now));
        assertEquals(1, bans.bannedIpCount(now));

        var again = login(BASE, "10.0.0.5", "back again");
        assertTrue(again.isDisconnected(), "its next login is refused");
        assertEquals(1, bans.connectionsRefused());

        assertFalse(bans.isBanned(ip, now + ClientBans.BAN_MILLIS + 60_000), "a day later");
        assertEquals(0, bans.bannedIpCount(now + ClientBans.BAN_MILLIS + 60_000));
    }

    @Test
    void withoutTheBehaviouralBanTheFileIsOnlySkipped() throws Exception {
        ServerConfig config = BASE.withClientBans("", false);
        var ctx = EmuleTestSupport.loggedInContext("10.0.0.7", 50000, config, registry, index);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        offer(ctx, out);

        assertFalse(ctx.isDisconnected());
        assertEquals(1, index.fileCount(), "the real file");
        assertFalse(index.contains("ED2B011200008A8F033CDCEF7349E8EB"));
        assertEquals(1, ctx.getState().publishedFilesCount().get(), "out of the client's quota");
        assertEquals(1, index.bannedHashRejectedCount());
        assertEquals(0, out.size(), "no warning");
        assertEquals(0, registry.clientBans().publishersBanned());
        assertFalse(registry.clientBans().isBanned(InetAddress.getByName("10.0.0.7"), System.currentTimeMillis()));
    }

    @Test
    void aCleanOfferIsIndexedWithTheBanOn() throws Exception {
        var ctx = EmuleTestSupport.loggedInContext("10.0.0.8", 50000, BASE, registry, index);
        new PublishHandler().handlePublish(ctx, OpCode.OFFER_FILES, EmuleTestSupport.offerFiles(
                EmuleTestSupport.completeFile(REAL, "real.avi", 1024)), new ByteArrayOutputStream());
        assertFalse(ctx.isDisconnected());
        assertEquals(1, index.fileCount());
    }
}
