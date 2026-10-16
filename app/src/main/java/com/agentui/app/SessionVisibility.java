package com.agentui.app;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Local visibility only: never discard catalog entries or change status summaries. */
final class SessionVisibility {
    static final long DAY = 86_400_000L;

    static boolean visible(Session session, Integer days, String query, long now) {
        String name = session.name.isEmpty() ? session.id : session.name;
        if (!query.isEmpty() && name.toLowerCase(Locale.ROOT).contains(query)) return true;
        if (days == null) return true;
        Long timestamp = timestamp(session);
        // Missing/legacy metadata must not make a session inaccessible.
        return timestamp == null || timestamp >= now - days * DAY;
    }

    private static Long timestamp(Session session) {
        try { return Instant.parse(session.lastActiveAt).toEpochMilli(); }
        catch (DateTimeParseException | NullPointerException e) { return null; }
    }
}
