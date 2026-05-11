package org.jemule.core.event;

public class ClientEvent extends BaseEvent {
    public static final String CONNECTED = "CLIENT_CONNECTED";
    public static final String DISCONNECTED = "CLIENT_DISCONNECTED";
    public static final String LOGIN = "CLIENT_LOGIN";

    private final String clientIp;
    private final String username;

    public ClientEvent(String type, String clientIp, String username, String message) {
        super(type, message);
        this.clientIp = clientIp;
        this.username = username;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getUsername() {
        return username;
    }
}
