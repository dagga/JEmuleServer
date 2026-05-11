package org.jemule.core.event;

import java.time.Instant;

public interface Event {
    Instant getTimestamp();
    String getType();
    String getMessage();
}
