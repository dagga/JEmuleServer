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
    default KadSearchBridge getKadSearchBridge() {
        return getServer() != null ? getServer().getKadSearchBridge() : null;
    }
    default ServerSearchBridge getServerSearchBridge() {
        return getServer() != null ? getServer().getServerSearchBridge() : null;
    }

    ClientState getState();
    void setState(ClientState state);

    OutputStream getWrappedOut();
    void setWrappedOut(OutputStream out);

    void setObfuscated(boolean obfuscated);

    void disconnect() throws IOException;
}
