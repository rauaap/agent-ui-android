package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MissingWorktreeTest {
    private Session session(String worktreeId, boolean archived) {
        return new Session("s1", "Session", "p1", "/app-fix", worktreeId,
                "pi", "idle", "", archived ? "2026-06-01" : "", false, false);
    }

    private Worktree worktree(String id, boolean exists) {
        return new Worktree(id, "p1", "/app-fix", "fix", 1, exists, "");
    }

    @Test public void explicitMissingAttachedWorktreeWarnsForLiveAndArchivedSessions() {
        for (boolean archived : new boolean[] {false, true}) {
            assertTrue(Worktree.isMissingFor(session("w1", archived),
                    Arrays.asList(worktree("w2", true), worktree("w1", false))));
        }
    }

    @Test public void existingAndUnknownWorktreesDoNotWarn() {
        Session attached = session("w1", false);
        assertFalse(Worktree.isMissingFor(attached, Collections.singletonList(worktree("w1", true))));
        assertFalse(Worktree.isMissingFor(attached, Collections.emptyList()));
        assertFalse(Worktree.isMissingFor(attached, Collections.singletonList(worktree("w2", false))));
    }

    @Test public void formerWorktreePathDoesNotImplyAttachmentOrMissingDirectory() {
        Session detached = session("", true);
        assertTrue(detached.isFormerWorktree("/app"));
        assertFalse(Worktree.isMissingFor(detached, Collections.singletonList(worktree("w1", false))));
        detached.workingDir = "/app";
        assertFalse(Worktree.isMissingFor(detached, Collections.singletonList(worktree("w1", false))));
    }

    @Test public void absentExistenceMetadataDefaultsToNotMissing() throws Exception {
        JSONObject metadata = new JSONObject("{\"id\":1,\"project_id\":1,\"path\":\"/app-fix\"}");
        Session attached = session("1", false);
        assertFalse(Worktree.isMissingFor(attached, Collections.singletonList(Worktree.from(metadata))));
        metadata.put("exists", false);
        assertTrue(Worktree.isMissingFor(attached, Collections.singletonList(Worktree.from(metadata))));
    }

    @Test public void refreshedExistenceStateRemovesWarning() {
        Session attached = session("w1", false);
        assertTrue(Worktree.isMissingFor(attached, Collections.singletonList(worktree("w1", false))));
        assertFalse(Worktree.isMissingFor(attached, Collections.singletonList(worktree("w1", true))));
    }
}
