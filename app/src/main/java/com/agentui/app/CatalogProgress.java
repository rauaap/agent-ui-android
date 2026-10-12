package com.agentui.app;

import java.util.List;

/** Early catalog delivery keeps cards independent of other requests and filesystem checks. */
interface CatalogProgress {
    default boolean isActive() { return true; }
    default void projects(List<Project> projects) {}
    default void sessions(List<Session> sessions) {}
    default void worktrees(List<Worktree> worktrees) {}
}
