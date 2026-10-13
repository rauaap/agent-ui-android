package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class MissingWorktreeTest {
    private Session session(String worktreeId) {
        return new Session("s1", "Session", "p1", "/app-fix", worktreeId,
                "pi", "idle", "", false, false);
    }

    @Test public void missingStateComesFromEffectivePathNotAssociation() {
        DirectorySnapshot snapshot = new DirectorySnapshot(Map.of("/app-fix", false));
        assertTrue(snapshot.missing(session("w1").workingDir));
        assertTrue(snapshot.missing(session("").workingDir));
    }

    @Test public void unknownPathDoesNotWarn() {
        assertFalse(DirectorySnapshot.unknown().missing(session("w1").workingDir));
    }

    @Test public void formerAndMissingAreIndependent() {
        Session former = session("");
        assertTrue(former.isFormerWorktree("/app"));
        assertFalse(new DirectorySnapshot(Map.of("/app-fix", true)).missing(former.workingDir));
        former.workingDir = "/app";
        assertFalse(former.isFormerWorktree("/app"));
    }

    @Test public void sharedLocationRuleMatchesCatalogModel() {
        for (String worktreeId : new String[] {"", "w1"}) {
            Session session = session(worktreeId);
            for (String projectPath : new String[] {null, "/app", "/app-fix"}) {
                assertEquals(session.isFormerWorktree(projectPath),
                        Session.isFormerWorktree(worktreeId, session.workingDir, projectPath));
            }
        }
        assertTrue(Session.isFormerWorktree("", "/app-fix", "/app"));
        assertFalse(Session.isFormerWorktree("w1", "/app-fix", "/app"));
        assertFalse(Session.isFormerWorktree("", "/app", "/app"));
        assertFalse(Session.isFormerWorktree("", "/app-fix", null));
    }

    @Test public void worktreeMetadataNeedsNoExistenceField() throws Exception {
        Worktree worktree = Worktree.from(new JSONObject("{\"id\":1,\"project_id\":1,\"path\":\"/app-fix\"}"));
        assertEquals("1", worktree.id);
        assertEquals("/app-fix", worktree.path);
        assertEquals("", worktree.branch);
    }

    @Test public void refreshedExistenceStateRemovesWarning() {
        Session attached = session("w1");
        assertTrue(new DirectorySnapshot(Map.of("/app-fix", false)).missing(attached.workingDir));
        assertFalse(new DirectorySnapshot(Map.of("/app-fix", true)).missing(attached.workingDir));
    }
}
