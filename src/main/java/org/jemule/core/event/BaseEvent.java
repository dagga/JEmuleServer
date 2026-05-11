package org.jemule.core.event;

import java.time.Instant;

public abstract class BaseEvent implements Event {
    private final Instant timestamp = Instant.now();
    private final String type;
    private final String message;

    protected BaseEvent(String type, String message) {
        this.type = type;
        this.message = message;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
