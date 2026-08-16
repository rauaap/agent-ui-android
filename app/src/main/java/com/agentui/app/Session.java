package com.agentui.app;

import org.json.JSONObject;

/** A single agent session, as returned by the backend's /sessions endpoints. */
final class Session {
    final String id;
    final String name;
    /**
     * The project this session belongs to. Empty from a server old enough not
     * to send one, where the link is {@link #workingDir} matching the project
     * path instead.
     */
    final String projectId;
    /**
     * The directory the agent runs in. The project's own directory normally,
     * but a worktree of it when {@link #ownsWorktree} is set.
     */
    final String workingDir;
    /**
     * Whether the server created {@link #workingDir} as a git worktree and will
     * remove it with the session. A worktree the user made by hand reads false.
     */
    final boolean ownsWorktree;
    final String agent;
    String status;
    final String lastActiveAt;
    // Per-session auto-approve toggles. Reads always run, so only the mutating
    // categories are switchable.
    boolean autoApproveWrite;
    boolean autoApproveCommand;

    Session(String id, String name, String projectId, String workingDir, boolean ownsWorktree,
            String agent, String status, String lastActiveAt,
            boolean autoApproveWrite, boolean autoApproveCommand) {
        this.id = id;
        this.name = name;
        this.projectId = projectId;
        this.workingDir = workingDir;
        this.ownsWorktree = ownsWorktree;
        this.agent = agent;
        this.status = status;
        this.lastActiveAt = lastActiveAt;
        this.autoApproveWrite = autoApproveWrite;
        this.autoApproveCommand = autoApproveCommand;
    }

    static Session from(JSONObject o) {
        return new Session(
                o.optString("id", ""),
                o.optString("name", "(unnamed)"),
                o.optString("project_id", ""),
                o.optString("working_dir", ""),
                o.optBoolean("owns_worktree", false),
                o.optString("agent", "claude-code"),
                o.optString("status", "idle"),
                o.optString("last_active_at", ""),
                o.optBoolean("auto_approve_write", false),
                o.optBoolean("auto_approve_command", false));
    }
}
