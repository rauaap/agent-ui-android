package com.agentui.app;

import java.util.List;

final class WorktreeRemoval {
    static boolean affected(Worktree worktree, Session session) {
        return worktree.id.equals(session.worktreeId) || worktree.path.equals(session.workingDir);
    }
    static String warning(Worktree worktree, List<Session> sessions) {
        int count = 0;
        for (Session session : sessions) if (affected(worktree, session)) count++;
        if (count == 0) return "";
        return "\n\n" + count + (count == 1 ? " session uses" : " sessions use")
                + " this directory (including attached and already-detached sessions). "
                + "Sessions, conversation history, queued inputs and settings are preserved. Attached sessions "
                + "become former-worktree sessions and keep this exact working directory. "
                + "Attempts to run fail while the directory is missing. "
                + "Recreate a directory at the same absolute path to use them again. "
                + "Active agent turns or shell commands block removal.";
    }
}
