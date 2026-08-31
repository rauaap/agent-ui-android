package com.agentui.app;

import org.json.JSONObject;

/**
 * A project — a working directory registered server-side, plus the aggregates
 * of the sessions that belong to it. It has a server-side {@code id}, but the
 * HTTP API still addresses one by {@code path}: the id is what sessions link to.
 */
final class Project {
    /**
     * Server-side id — a JSON number kept as an opaque string, never parsed
     * back or compared as one. Empty from a server old enough not to send one.
     */
    final String id;
    final String path;
    final String name;
    final int sessionCount;
    /** Newest activity across the project's sessions; empty when it has none. */
    final String lastActiveAt;
    /** Whether the directory is still on the server's disk. */
    final boolean exists;
    /**
     * Whether the directory has a {@code .git} — a hint, not a guarantee, for
     * whether to offer a worktree. Creating one is where it is really checked.
     */
    final boolean isGitRepo;

    Project(String id, String path, String name, int sessionCount, String lastActiveAt,
            boolean exists, boolean isGitRepo) {
        this.id = id;
        this.path = path;
        this.name = name;
        this.sessionCount = sessionCount;
        this.lastActiveAt = lastActiveAt;
        this.exists = exists;
        this.isGitRepo = isGitRepo;
    }

    static Project from(JSONObject o) {
        // A project with no sessions reports last_active_at: null, and
        // optString would hand back the literal "null" for it.
        String lastActive = o.isNull("last_active_at")
                ? "" : o.optString("last_active_at", "");
        return new Project(
                // A JSON number, held as a string; see Json.
                Json.id(o, "id"),
                o.optString("path", ""),
                o.optString("name", "(unnamed)"),
                o.optInt("session_count", 0),
                lastActive,
                // Default true: a server that does not report it must not make
                // every project look broken.
                o.optBoolean("exists", true),
                // Default false: a server that does not report it cannot make
                // worktrees either, so the toggle stays hidden.
                o.optBoolean("is_git_repo", false));
    }
}
