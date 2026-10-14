package com.agentui.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class ProjectSearchIndexTest {
    private static Project project(String id, String path) {
        return new Project(id, path, "Project " + path, 0, "", true);
    }
    private static Session session(String id, String name, String projectId, String path, String status) {
        return new Session(id, name, projectId, path, "", "pi", status, "", false, false);
    }

    @Test public void idAndLegacyPathOwnershipPreserveSourceOrderIncludingWorktrees() {
        Project modern = project("p", "/app"), legacy = project("", "/app");
        Session byId = session("1", "Needle ID", "p", "/worktree", "idle");
        Session byPath = session("2", "Needle path", "", "/app", "idle");
        Session wrongId = session("3", "Needle other", "other", "/app", "idle");
        Session byIdAgain = session("4", "Needle ID again", "p", "/another", "idle");
        List<Session> source = List.of(byId, byPath, wrongId, byIdAgain);
        ProjectSearch.Index index = new ProjectSearch.Index(List.of(modern, legacy), source);
        List<ProjectSearch.Group> groups = index.filter("needle");
        assertEquals(List.of(byId, byPath, byIdAgain), groups.get(0).sessions);
        assertEquals(List.of(byPath, wrongId), groups.get(1).sessions);
        assertEquals(List.of(byId, byPath, wrongId, byIdAgain), source);
    }

    @Test public void badgesUseAllOwnedSessionsEvenWhenOnlyIdleChildMatchesQuery() {
        Project project = project("p", "/app");
        Session matching = session("1", "Needle", "p", "/app", "idle");
        Session running = session("2", "Background", "p", "/tree", "running");
        Session approval = session("3", "Approval", "p", "/tree", "awaiting_approval");
        ProjectSearch.Group group = new ProjectSearch.Index(List.of(project),
                List.of(matching, running, approval)).filter("needle").get(0);
        assertEquals(List.of(matching), group.sessions);
        assertEquals("awaiting_approval", group.status);
        assertEquals("running", new ProjectSearch.Index(List.of(project), List.of(matching, running))
                .filter("needle").get(0).status);
        assertNull(new ProjectSearch.Index(List.of(project), List.of(matching)).filter("needle").get(0).status);
    }

    @Test public void statusSummaryIsCatalogSnapshotNotARepeatScanOnEveryQuery() {
        Project project = project("p", "/app");
        Session session = session("1", "Needle", "p", "/app", "running");
        ProjectSearch.Index index = new ProjectSearch.Index(List.of(project), List.of(session));
        session.status = "idle";
        assertEquals("running", index.filter("needle").get(0).status);
        assertEquals("running", index.filter("project").get(0).status);
        assertNull(new ProjectSearch.Index(List.of(project), List.of(session)).filter("needle").get(0).status);
    }

    @Test public void emptyNameMatchesSessionIdAndEmptyProjectsStillMatchTheirOwnFields() {
        Project project = project("p", "/app"), empty = project("empty", "/empty");
        Session session = session("opaque-id", "", "p", "/tree", "idle");
        ProjectSearch.Index index = new ProjectSearch.Index(List.of(project, empty), List.of(session));
        assertEquals(List.of(session), index.filter("OPAQUE").get(0).sessions);
        assertSame(empty, index.filter("/empty").get(0).project);
        assertTrue(index.filter(" \n ").isEmpty());
    }

    @Test public void indexedOwnershipAndMatchingAgreeWithTheReferenceRuleAcrossMixedCatalogs() {
        Random random = new Random(317);
        List<Project> projects = new ArrayList<>();
        for (int i = 0; i < 30; i++) projects.add(project(i % 4 == 0 ? "" : "p" + i, "/path" + (i % 9)));
        List<Session> sessions = new ArrayList<>();
        String[] statuses = {"idle", "running", "awaiting_approval"};
        for (int i = 0; i < 150; i++) sessions.add(session("s" + i, i % 7 == 0 ? "" : "Topic " + (i % 5),
                i % 3 == 0 ? "" : "p" + random.nextInt(35), "/path" + random.nextInt(12), statuses[i % 3]));
        ProjectSearch.Index index = new ProjectSearch.Index(projects, sessions);
        for (String query : new String[]{"topic 1", "project", "/path2", "s14", "no match"}) {
            List<ProjectSearch.Group> actual = index.filter(query);
            int groupIndex = 0;
            for (Project project : projects) {
                boolean projectMatches = project.name.toLowerCase(java.util.Locale.ROOT).contains(query)
                        || project.path.contains(query);
                List<Session> expected = new ArrayList<>();
                for (Session session : sessions) {
                    String name = session.name.isEmpty() ? session.id : session.name;
                    if (project.owns(session) && (projectMatches || name.toLowerCase(java.util.Locale.ROOT).contains(query))) {
                        expected.add(session);
                    }
                }
                if (projectMatches || !expected.isEmpty()) {
                    ProjectSearch.Group group = actual.get(groupIndex++);
                    assertSame(project, group.project);
                    assertEquals(expected, group.sessions);
                    assertEquals(ProjectStatus.aggregate(project, sessions), group.status);
                }
            }
            assertEquals(groupIndex, actual.size());
        }
    }
}
