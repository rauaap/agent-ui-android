package com.agentui.app;

import org.json.JSONObject;

/** A single agent session, as returned by the backend's /sessions endpoints. */
final class Session {
    /**
     * Server-side id — a JSON number kept as an opaque string, and used as one
     * throughout: a URL segment, an Intent extra, a map key. Never parsed back
     * into a number, ordered, or truncated for display.
     */
    final String id;
    final String name;
    /**
     * The project this session belongs to. Empty from a server old enough not
     * to send one, where the link is {@link #workingDir} matching the project
     * path instead.
     */
    final String projectId;
    /**
     * The effective directory the agent runs in: normally the project's or an
     * attached worktree's, and preserved as the former path after detachment.
     */
    String workingDir;
    /**
     * The attached worktree, or empty for either the project directory or a
     * former worktree after detachment. A worktree is its own resource: several
     * sessions can share one, and deleting this session removes nothing on disk.
     */
    String worktreeId;
    final String agent;
    String status;
    final String lastActiveAt;
    /**
     * When the session was archived (ISO 8601, UTC), or empty while it is live.
     * A timestamp rather than a flag so the archive can be ordered by when
     * things were filed; re-archiving keeps the original, so a redundant call
     * does not jump the queue. Mutable: the {@code archived} WebSocket event
     * carries changes made on another device.
     */
    String archivedAt;
    // Per-session auto-approve toggles. Reads always run, so only the mutating
    // categories are switchable.
    boolean autoApproveWrite;
    boolean autoApproveCommand;

    Session(String id, String name, String projectId, String workingDir, String worktreeId,
            String agent, String status, String lastActiveAt, String archivedAt,
            boolean autoApproveWrite, boolean autoApproveCommand) {
        this.id = id;
        this.name = name;
        this.projectId = projectId;
        this.workingDir = workingDir;
        this.worktreeId = worktreeId;
        this.agent = agent;
        this.status = status;
        this.lastActiveAt = lastActiveAt;
        this.archivedAt = archivedAt;
        this.autoApproveWrite = autoApproveWrite;
        this.autoApproveCommand = autoApproveCommand;
    }

    boolean isArchived() { return !archivedAt.isEmpty(); }

    /** A released worktree path, rather than the project's own directory. */
    boolean isFormerWorktree(String projectPath) {
        return worktreeId.isEmpty() && projectPath != null
                && !workingDir.equals(projectPath);
    }

    /** Whether this detached session preserves the given worktree's path. */
    boolean dependsOnFormerWorktree(Worktree worktree, String projectPath) {
        return projectId.equals(worktree.projectId)
                && isFormerWorktree(projectPath)
                && workingDir.equals(worktree.path);
    }

    static Session from(JSONObject o) {
        return new Session(
                // Ids arrive as JSON numbers and are held as strings; see Json.
                Json.id(o, "id"),
                o.optString("name", "(unnamed)"),
                Json.id(o, "project_id"),
                o.optString("working_dir", ""),
                // null for a session that runs in the project directory, which
                // Json.id reads as empty.
                Json.id(o, "worktree_id"),
                o.optString("agent", "claude-code"),
                o.optString("status", "idle"),
                o.optString("last_active_at", ""),
                // A live session reports archived_at: null, and optString would
                // hand back the literal "null" for it.
                o.isNull("archived_at") ? "" : o.optString("archived_at", ""),
                o.optBoolean("auto_approve_write", false),
                o.optBoolean("auto_approve_command", false));
    }
}
