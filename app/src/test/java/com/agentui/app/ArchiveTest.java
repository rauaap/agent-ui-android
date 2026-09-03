package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the live/archived split of the two list responses. The server
 * deliberately does not filter either one, so everything here is what stands
 * between an archived row and the main list.
 */
public class ArchiveTest {

    private static Project project(String id, String path, String archivedAt) {
        return new Project(id, path, path, 0, 0, "", archivedAt, true, false);
    }

    private static Session session(String id, String projectId, String workingDir,
                                   String archivedAt) {
        // No worktree: the one test that needs one passes its id explicitly.
        return session(id, projectId, workingDir, "", archivedAt);
    }

    private static Session session(String id, String projectId, String workingDir,
                                   String worktreeId, String archivedAt) {
        return new Session(id, id, projectId, workingDir, worktreeId,
                "claude-code", "idle", "", archivedAt, false, false);
    }

    private static List<String> ids(List<Session> sessions) {
        List<String> out = new ArrayList<>();
        for (Session s : sessions) out.add(s.id);
        return out;
    }

    private static List<String> paths(List<Project> projects) {
        List<String> out = new ArrayList<>();
        for (Project p : projects) out.add(p.path);
        return out;
    }

    @Test
    public void sessionsSplitOnArchivedAt() {
        List<Session> all = Arrays.asList(
                session("a", "p1", "/app", ""),
                session("b", "p1", "/app", "2026-08-26T11:02:00Z"));

        assertEquals(Arrays.asList("a"), ids(Archive.liveSessions(all)));
        assertEquals(Arrays.asList("b"), ids(Archive.archivedSessions(all)));
    }

    @Test
    public void projectsSplitOnArchivedAt() {
        List<Project> all = Arrays.asList(
                project("p1", "/app", ""),
                project("p2", "/old", "2026-08-26T11:02:00Z"));

        assertEquals(Arrays.asList("/app"), paths(Archive.liveProjects(all)));
        assertEquals(Arrays.asList("/old"), paths(Archive.archivedProjects(all)));
    }

    @Test
    public void theArchiveIsOrderedByWhenThingsWereFiled() {
        // Not by the order GET /projects returns: archived projects have a null
        // last_active_at and land at the end of it in no useful sequence.
        List<Project> projects = Arrays.asList(
                project("p1", "/older", "2026-08-01T09:00:00Z"),
                project("p2", "/newest", "2026-08-30T09:00:00Z"),
                project("p3", "/middle", "2026-08-15T09:00:00Z"));
        assertEquals(Arrays.asList("/newest", "/middle", "/older"),
                paths(Archive.archivedProjects(projects)));

        List<Session> sessions = Arrays.asList(
                session("older", "p1", "/app", "2026-08-01T09:00:00Z"),
                session("newest", "p1", "/app", "2026-08-30T09:00:00Z"),
                session("middle", "p1", "/app", "2026-08-15T09:00:00Z"));
        assertEquals(Arrays.asList("newest", "middle", "older"),
                ids(Archive.archivedSessions(sessions)));
    }

    @Test
    public void anArchivedProjectSpeaksForItsOwnSessions() {
        // p2's archive cascaded to c and d, so the archive view shows one
        // project row rather than that row plus both sessions. b was archived by
        // hand under a live project, so it lists itself.
        List<Project> projects = Arrays.asList(
                project("p1", "/app", ""),
                project("p2", "/old", "2026-08-26T11:02:00Z"));
        List<Session> sessions = Arrays.asList(
                session("a", "p1", "/app", ""),
                session("b", "p1", "/app", "2026-08-20T10:00:00Z"),
                session("c", "p2", "/old", "2026-08-26T11:02:00Z"),
                session("d", "p2", "/old", "2026-08-26T11:02:00Z"));

        assertEquals(Arrays.asList("b"),
                ids(Archive.looseArchivedSessions(projects, sessions)));
    }

    @Test
    public void aWorktreeSessionIsStillItsProjectsToSpeakFor() {
        // It runs in a worktree, so its working_dir is a sibling directory and
        // only project_id links it back — matching on the path would list it
        // twice, once under the project and once on its own.
        List<Project> projects = Arrays.asList(project("p1", "/app", "2026-08-26T11:02:00Z"));
        List<Session> sessions = Arrays.asList(
                session("a", "p1", "/app-fix-login", "7", "2026-08-26T11:02:00Z"));

        assertEquals(new ArrayList<String>(),
                ids(Archive.looseArchivedSessions(projects, sessions)));
    }

    @Test
    public void anArchivedSessionWithNoProjectRowStillLists() {
        // Nothing owns it — a project deleted from under it, say. Dropping it
        // would strand it somewhere unreachable.
        List<Session> sessions = Arrays.asList(
                session("a", "gone", "/nowhere", "2026-08-26T11:02:00Z"));

        assertEquals(Arrays.asList("a"),
                ids(Archive.looseArchivedSessions(new ArrayList<>(), sessions)));
    }

    @Test
    public void findLocatesTheProjectByPath() {
        Project app = project("p1", "/app", "");
        List<Project> projects = Arrays.asList(app, project("p2", "/old", ""));

        assertSame(app, Archive.find(projects, "/app"));
        assertNull(Archive.find(projects, "/gone"));
    }
}
