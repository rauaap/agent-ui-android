package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class DirectorySnapshotTest {
    private final Project project = new Project("p", "/app", "App", 0, 0, "", "", true);
    private final Worktree worktree = new Worktree("w", "p", "/app-fix", "fix", 1, "");
    private Session session(String id, String path, String worktreeId, String archive) {
        return new Session(id, id, "p", path, worktreeId, "pi", "idle", "", archive, false, false);
    }
    @Test public void absentKeysAreUnknownNotMissing() throws Exception {
        DirectorySnapshot snapshot = DirectorySnapshot.from(new JSONObject("{\"/app\":true,\"/gone\":false,\"/invalid\":null}"));
        assertFalse(snapshot.missing("/app"));
        assertTrue(snapshot.missing("/gone"));
        assertFalse(snapshot.missing("/unknown"));
        assertFalse(snapshot.paths.containsKey("/invalid"));
        assertFalse(snapshot.covers(List.of(project), List.of(worktree), List.of()));
    }
    @Test public void formerAndMissingAreIndependentAndRecreationWorks() {
        Session former = session("s", "/app-fix", "", "archived");
        assertTrue(former.isFormerWorktree(project.path));
        assertTrue(new DirectorySnapshot(Map.of(former.workingDir, false)).missing(former.workingDir));
        assertFalse(new DirectorySnapshot(Map.of(former.workingDir, true)).missing(former.workingDir));
        assertTrue(former.isFormerWorktree(project.path));
        assertTrue(former.isArchived());
    }
    @Test public void coverageIncludesUnusedCatalogsAndFormerDirectories() {
        Session former = session("s", "/former", "", "archived");
        DirectorySnapshot complete = new DirectorySnapshot(Map.of("/app", true, "/app-fix", false, "/former", false));
        assertTrue(complete.covers(List.of(project), List.of(worktree), List.of(former)));
        assertFalse(new DirectorySnapshot(Map.of("/app", true, "/app-fix", true))
                .covers(List.of(project), List.of(worktree), List.of(former)));
    }
    @Test public void confirmationCountsAttachedAndDetachedLiveAndArchivedSessions() {
        List<Session> sessions = List.of(session("attached", "/app-fix", "w", ""),
                session("archived", "/app-fix", "w", "archived"),
                session("detached", "/app-fix", "", ""),
                session("detachedArchive", "/app-fix", "", "archived"),
                session("root", "/app", "", ""));
        assertTrue(WorktreeRemoval.warning(worktree, sessions).contains("4 sessions use"));
        assertTrue(WorktreeRemoval.warning(worktree, sessions).contains("history and archive state are preserved"));
        assertFalse(WorktreeRemoval.affected(worktree, sessions.get(4)));
        assertEquals("", WorktreeRemoval.warning(worktree, List.of(sessions.get(4))));
    }
}
