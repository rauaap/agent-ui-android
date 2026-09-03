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
    /** Live sessions only — the archived ones are counted separately. */
    final int sessionCount;
    /** How many of the project's sessions are archived. */
    final int archivedSessionCount;
    /**
     * Newest activity across the project's <em>live</em> sessions; empty when it
     * has none, which an archived project never does. Do not read it as "never
     * used" without checking {@link #isArchived()} first.
     */
    final String lastActiveAt;
    /**
     * When the project was archived (ISO 8601, UTC), or empty while it is live.
     * Archiving a project cascades to its sessions, so an archived one always
     * reports {@code sessionCount == 0}.
     */
    final String archivedAt;
    /** Whether the directory is still on the server's disk. */
    final boolean exists;
    /**
     * Whether the directory has a {@code .git} — a hint, not a guarantee, for
     * whether to offer a worktree. Creating one is where it is really checked.
     */
    final boolean isGitRepo;

    Project(String id, String path, String name, int sessionCount, int archivedSessionCount,
            String lastActiveAt, String archivedAt, boolean exists, boolean isGitRepo) {
        this.id = id;
        this.path = path;
        this.name = name;
        this.sessionCount = sessionCount;
        this.archivedSessionCount = archivedSessionCount;
        this.lastActiveAt = lastActiveAt;
        this.archivedAt = archivedAt;
        this.exists = exists;
        this.isGitRepo = isGitRepo;
    }

    boolean isArchived() { return !archivedAt.isEmpty(); }

    /** Whether {@code s} belongs to this project. See the static overload. */
    boolean owns(Session s) {
        return owns(id, path, s);
    }

    /**
     * Whether a session belongs to the project identified by {@code projectId} /
     * {@code projectPath}. The link is the id: a session running in a worktree
     * has a {@code working_dir} somewhere else entirely, so matching on the path
     * would disown it. Path matching survives only as the fallback for a server
     * old enough not to send an id — which is also a server old enough to have
     * no worktrees.
     */
    static boolean owns(String projectId, String projectPath, Session s) {
        if (projectId != null && !projectId.isEmpty() && !s.projectId.isEmpty()) {
            return projectId.equals(s.projectId);
        }
        return projectPath != null && projectPath.equals(s.workingDir);
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
                o.optInt("archived_session_count", 0),
                lastActive,
                // Same "null" trap as last_active_at, and the same treatment.
                o.isNull("archived_at") ? "" : o.optString("archived_at", ""),
                // Default true: a server that does not report it must not make
                // every project look broken.
                o.optBoolean("exists", true),
                // Default false: a server that does not report it cannot make
                // worktrees either, so the toggle stays hidden.
                o.optBoolean("is_git_repo", false));
    }
}
