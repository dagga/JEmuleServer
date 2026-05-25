package org.jemule.network.handler;

import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.core.event.EventManager;
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

    ClientState getState();
    void setState(ClientState state);

    OutputStream getWrappedOut();
    void setWrappedOut(OutputStream out);

    void setObfuscated(boolean obfuscated);

    void disconnect() throws IOException;
}
