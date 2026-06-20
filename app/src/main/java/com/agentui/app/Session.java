package com.agentui.app;

import org.json.JSONObject;

/** A single agent session, as returned by the backend's /sessions endpoints. */
final class Session {
    final String id;
    final String name;
    final String workingDir;
    final String agent;
    String status;
    final String lastActiveAt;
    // Per-session auto-approve toggles. Reads always run, so only the mutating
    // categories are switchable.
    boolean autoApproveWrite;
    boolean autoApproveCommand;

    Session(String id, String name, String workingDir, String agent, String status, String lastActiveAt,
            boolean autoApproveWrite, boolean autoApproveCommand) {
        this.id = id;
        this.name = name;
        this.workingDir = workingDir;
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
                o.optString("working_dir", ""),
                o.optString("agent", "claude-code"),
                o.optString("status", "idle"),
                o.optString("last_active_at", ""),
                o.optBoolean("auto_approve_write", false),
                o.optBoolean("auto_approve_command", false));
    }
}
