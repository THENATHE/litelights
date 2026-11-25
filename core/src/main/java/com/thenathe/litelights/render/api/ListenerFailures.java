package com.thenathe.litelights.render.api;

import com.thenathe.litelights.LiteLightsClient;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Logs exceptions thrown by event listeners, at most once every five seconds per listener, so one
 * broken listener can neither end the session nor flood the log. Render thread only.
 */
final class ListenerFailures {
    private static final long LOG_INTERVAL_MILLIS = 5_000L;
    private static final Map<Object, Long> LAST_LOGGED = new IdentityHashMap<>();

    private ListenerFailures() {
    }

    static void report(String event, Object listener, RuntimeException failure) {
        long now = System.currentTimeMillis();
        Long last = LAST_LOGGED.get(listener);
        if (last != null && now - last < LOG_INTERVAL_MILLIS) {
            return;
        }
        LAST_LOGGED.put(listener, now);
        LiteLightsClient.LOG.error("{} listener {} failed", event, listener.getClass().getName(), failure);
    }
}
