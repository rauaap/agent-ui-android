package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Decoding the current catalog contract without removed lifecycle fields. */
public class RetainedRecordsTest {
    @Test public void projectCountsAllSessionsAndKeepsActivity() throws Exception {
        Project project = Project.from(new JSONObject("{\"id\":1,\"path\":\"/app\","
                + "\"name\":\"App\",\"session_count\":3,\"last_active_at\":\"2026-06-01T12:00:00Z\","
                + "\"is_git_repo\":true,\"sandbox_network_allowlist\":[]}"));
        assertEquals(3, project.sessionCount);
        assertEquals("2026-06-01T12:00:00Z", project.lastActiveAt);
        assertTrue(project.isGitRepo);
    }

    @Test public void removalMetadataPreservesSessionPathIdentityAndSettings() throws Exception {
        Session session = Session.from(new JSONObject("{\"id\":2,\"name\":\"Fix\",\"project_id\":1,"
                + "\"worktree_id\":null,\"working_dir\":\"/app-fix\",\"agent\":\"pi\",\"status\":\"idle\","
                + "\"model\":null,\"auto_approve_write\":true,\"sandbox\":true}"));
        assertEquals("2", session.id);
        assertEquals("Fix", session.name);
        assertEquals("1", session.projectId);
        assertEquals("/app-fix", session.workingDir);
        assertEquals("", session.worktreeId);
        assertTrue(session.isFormerWorktree("/app"));
        assertTrue(session.autoApproveWrite);
        assertEquals(Boolean.TRUE, session.sandbox);
        assertNull(session.model);
    }
}
