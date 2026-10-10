package com.agentui.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class ProjectSearchTest {
    private final Project app = new Project("1", "/projects/app", "App", 2, 0, "", "", true, true);
    private final Project old = new Project("2", "/projects/old", "Old", 0, 1, "", "archived", true, false);
    private final Session root = session("10", "Refactor", "1", app.path, "");
    private final Session worktree = session("11", "Fix Login", "1", "/worktrees/login", "");
    private final Session archived = session("12", "Login notes", "2", old.path, "archived");
    private final List<Project> projects = Arrays.asList(app, old);
    private final List<Session> sessions = Arrays.asList(root, worktree, archived);

    private static Session session(String id, String name, String projectId, String dir, String archive) {
        return new Session(id, name, projectId, dir, "", "pi", "idle", "", archive, false, false);
    }

    @Test public void startsEmptyAndClearingRestoresEmpty() {
        assertTrue(ProjectSearch.filter(projects, sessions, "").isEmpty());
        assertFalse(ProjectSearch.filter(projects, sessions, "app").isEmpty());
        assertTrue(ProjectSearch.filter(projects, sessions, "  \n ").isEmpty());
    }

    @Test public void matchingProjectShowsAllItsSessionsInSourceOrder() {
        List<ProjectSearch.Group> result = ProjectSearch.filter(projects, sessions, " APP ");
        assertEquals(1, result.size());
        assertSame(app, result.get(0).project);
        assertEquals(Arrays.asList(root, worktree), result.get(0).sessions);
        assertEquals(Arrays.asList(root, worktree, archived), sessions);
    }

    @Test public void matchingSessionsKeepTheirParentsIncludingArchiveAndWorktrees() {
        List<ProjectSearch.Group> result = ProjectSearch.filter(projects, sessions, "login");
        assertEquals(2, result.size());
        assertSame(app, result.get(0).project);
        assertEquals(Collections.singletonList(worktree), result.get(0).sessions);
        assertSame(old, result.get(1).project);
        assertEquals(Collections.singletonList(archived), result.get(1).sessions);
    }

    @Test public void matchesPathsAndEmptyProjects() {
        assertEquals(2, ProjectSearch.filter(projects, sessions, "/projects/").size());
        assertEquals(1, ProjectSearch.filter(Collections.singletonList(app),
                Collections.emptyList(), "app").size());
        assertTrue(ProjectSearch.filter(projects, sessions, "no match").isEmpty());
    }

    @Test public void idOwnershipWinsOverPathAndLegacyPathsStillWork() {
        Session wrong = session("20", "needle", "2", app.path, "");
        Session legacy = session("21", "needle", "", app.path, "");
        List<ProjectSearch.Group> result = ProjectSearch.filter(projects,
                Arrays.asList(wrong, legacy), "needle");
        assertEquals(Collections.singletonList(legacy), result.get(0).sessions);
        assertEquals(Collections.singletonList(wrong), result.get(1).sessions);
    }

    @Test public void caseFoldingDoesNotDependOnDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(2, ProjectSearch.filter(projects, sessions, "LOGIN").size());
        } finally {
            Locale.setDefault(previous);
        }
    }
}
