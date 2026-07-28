package com.agentui.app;

import org.json.JSONObject;

/**
 * A project — a working directory the backend derives from disk and from the
 * sessions table. Nothing is stored server-side, so there is no id: the path
 * is the identity.
 */
final class Project {
    final String path;
    final String name;
    final int sessionCount;
    /** Newest activity across the project's sessions; empty when it has none. */
    final String lastActiveAt;
    /** Whether the directory is still on the server's disk. */
    final boolean exists;

    Project(String path, String name, int sessionCount, String lastActiveAt, boolean exists) {
        this.path = path;
        this.name = name;
        this.sessionCount = sessionCount;
        this.lastActiveAt = lastActiveAt;
        this.exists = exists;
    }

    static Project from(JSONObject o) {
        // A project with no sessions reports last_active_at: null, and
        // optString would hand back the literal "null" for it.
        String lastActive = o.isNull("last_active_at")
                ? "" : o.optString("last_active_at", "");
        return new Project(
                o.optString("path", ""),
                o.optString("name", "(unnamed)"),
                o.optInt("session_count", 0),
                lastActive,
                // Default true: a server that does not report it must not make
                // every project look broken.
                o.optBoolean("exists", true));
    }
}
