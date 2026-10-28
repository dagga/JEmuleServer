package org.jemule.network.handler;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.event.EventManager;
import org.jemule.network.Server;
import org.jemule.kad.KadSearchBridge;
import org.jemule.network.ServerSearchBridge;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public interface ClientContext {
    Socket getSocket();
    ServerConfig getConfig();
    ClientRegistry getRegistry();
    FileIndex getFileIndex();
    FloodProtector getFloodProtector();
    FakeFileDetector getFakeFileDetector();
    EventManager getEventManager();
    ClientFactory getClientFactory();
    default Server getServer() { return null; }
    /**
     * Whether the client is another eD2K server (its relay or index update sessions): it gets the local answers only,
     * see {@link Server#isPeerServer}.
     */
    default boolean isFromServer() {
        Server server = getServer();
        Socket socket = getSocket();
        return server != null && socket != null && server.isPeerServer(socket.getInetAddress());
    }
    default KadSearchBridge getKadSearchBridge() {
        return getServer() != null ? getServer().getKadSearchBridge() : null;
    }
    default ServerSearchBridge getServerSearchBridge() {
        return getServer() != null ? getServer().getServerSearchBridge() : null;
    }
    default org.jemule.network.ServerIndexUpdater getServerIndexUpdater() {
        return getServer() != null ? getServer().getServerIndexUpdater() : null;
    }
    default org.jemule.network.ServerRelay getServerRelay() {
        return getServer() != null ? getServer().getServerRelay() : null;
    }

    /** Kad source searches for OP_GETSOURCES, null without a server or a Kad node. */
    default org.jemule.network.KadSourceService getKadSourceService() {
        return getServer() != null ? getServer().getKadSourceService() : null;
    }

    /** Server-wide OFFERFILES v1 ceiling and counters. */
    default org.jemule.core.OfferFilesV1 getOfferFilesV1() {
        return getServer() != null ? getServer().getOfferFilesV1() : DETACHED_OFFER_FILES;
    }

    /** Used by contexts without a server, which share no ceiling with real connections. */
    org.jemule.core.OfferFilesV1 DETACHED_OFFER_FILES = new org.jemule.core.OfferFilesV1();

    ClientState getState();
    void setState(ClientState state);

    OutputStream getWrappedOut();
    void setWrappedOut(OutputStream out);

    void setObfuscated(boolean obfuscated);

    void disconnect() throws IOException;
}
