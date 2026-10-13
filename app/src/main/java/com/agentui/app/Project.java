package com.agentui.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
    /** This project's own TCP exceptions; server entries are inherited separately. */
    final List<SandboxNetworkDestination> sandboxNetworkAllowlist;
    final List<SandboxPath> sandboxPaths;
    final boolean hasSandboxPaths;
    final String path;
    final String name;
    /** All sessions belonging to the project. */
    final int sessionCount;
    /** Newest activity across all sessions; empty when there is none. */
    final String lastActiveAt;
    /**
     * Whether the directory has a {@code .git} — a hint, not a guarantee, for
     * whether to offer a worktree. Creating one is where it is really checked.
     */
    final boolean isGitRepo;

    Project(String id, String path, String name, int sessionCount,
            String lastActiveAt, boolean isGitRepo) {
        this(id, path, name, sessionCount, lastActiveAt,
                isGitRepo, new ArrayList<>(), false, new ArrayList<>());
    }

    private Project(String id, String path, String name, int sessionCount,
            String lastActiveAt, boolean isGitRepo,
            List<SandboxPath> sandboxPaths, boolean hasSandboxPaths,
            List<SandboxNetworkDestination> sandboxNetworkAllowlist) {
        this.sandboxNetworkAllowlist = sandboxNetworkAllowlist;
        this.sandboxPaths = sandboxPaths;
        this.hasSandboxPaths = hasSandboxPaths;
        this.id = id;
        this.path = path;
        this.name = name;
        this.sessionCount = sessionCount;
        this.lastActiveAt = lastActiveAt;
        this.isGitRepo = isGitRepo;
    }

    static Project find(List<Project> projects, String path) {
        for (Project project : projects) {
            if (project.path.equals(path)) return project;
        }
        return null;
    }

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
                lastActive,
                // Default false: a server that does not report it cannot make
                // worktrees either, so the toggle stays hidden.
                o.optBoolean("is_git_repo", false), pathsFrom(o), o.optJSONArray("sandbox_paths") != null,
                networkFrom(o));
    }

    private static List<SandboxNetworkDestination> networkFrom(JSONObject o) {
        try {
            return SandboxNetworkDestination.from(o.getJSONArray("sandbox_network_allowlist"));
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }

    private static List<SandboxPath> pathsFrom(JSONObject o) {
        try {
            return SandboxPath.from(o.optJSONArray("sandbox_paths"));
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
}
