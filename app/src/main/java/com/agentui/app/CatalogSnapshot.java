package com.agentui.app;

import java.util.List;

/** Only catalogs requested by the screen are populated. */
final class CatalogSnapshot {
    final List<Project> projects;
    final List<Worktree> worktrees;
    final List<Session> sessions;
    final DirectorySnapshot existence;
    final boolean worktreesSupported;

    CatalogSnapshot(List<Project> projects, List<Worktree> worktrees, List<Session> sessions,
                    DirectorySnapshot existence, boolean worktreesSupported) {
        this.projects = projects;
        this.worktrees = worktrees;
        this.sessions = sessions;
        this.existence = existence;
        this.worktreesSupported = worktreesSupported;
    }
}
