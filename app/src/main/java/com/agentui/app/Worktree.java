package com.agentui.app;

import org.json.JSONObject;

/**
 * A git worktree of a project, as returned by the backend's /worktrees
 * endpoints.
 *
 * <p>A worktree is its own resource: created and removed through its own
 * endpoints, shared by any number of sessions, and outliving all of them. It
 * used to be something a session created and destroyed, which is why a session
 * now carries a {@link Session#worktreeId} rather than an "owns it" flag.
 */
final class Worktree {
    /**
     * Server-side id — a JSON number kept as an opaque string, like every other
     * id in this app. See {@link Json}.
     */
    final String id;
    /** The project this worktree belongs to; a session may not cross projects. */
    final String projectId;
    /** The worktree directory, absolute and lexically normalised server-side. */
    final String path;
    /**
     * The branch the worktree was <em>created on</em>, empty when the server
     * has none — a row carried over by its migration. Not live state: an agent
     * working in the worktree can switch branches and nothing here notices,
     * which is why the UI says "created on" rather than naming it flatly.
     */
    final String branch;
    /**
     * How many sessions remain attached to this worktree. Explicitly detached
     * sessions are excluded even though they preserve the same working path.
     * Zero is an ordinary state — an unused worktree still there to attach to.
     */
    final int sessionCount;
    /**
     * Whether the directory is still on disk, {@code stat}ed at request time.
     * False means it was removed outside the app: attaching a session still
     * succeeds server-side but the session fails on its first turn, and the
     * useful move is to clean the row away.
     */
    final boolean exists;
    final String createdAt;

    Worktree(String id, String projectId, String path, String branch,
             int sessionCount, boolean exists, String createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.path = path;
        this.branch = branch;
        this.sessionCount = sessionCount;
        this.exists = exists;
        this.createdAt = createdAt;
    }

    static Worktree from(JSONObject o) {
        // branch is null for a migrated worktree, and optString would hand back
        // the literal "null" for it.
        String branch = o.isNull("branch") ? "" : o.optString("branch", "");
        return new Worktree(
                // Ids arrive as JSON numbers and are held as strings; see Json.
                Json.id(o, "id"),
                Json.id(o, "project_id"),
                o.optString("path", ""),
                branch,
                o.optInt("session_count", 0),
                // Default true: a server that does not report it must not make
                // every worktree look broken.
                o.optBoolean("exists", true),
                o.optString("created_at", ""));
    }

    /** "created on fix-login · 2 sessions", skipping whichever part is absent. */
    String subtitle() {
        StringBuilder s = new StringBuilder();
        if (!branch.isEmpty()) s.append("created on ").append(branch);
        if (sessionCount > 0) {
            if (s.length() > 0) s.append("  ·  ");
            s.append(sessionCount).append(sessionCount == 1 ? " session" : " sessions");
        }
        return s.toString();
    }
}
