package com.agentui.app;

import org.junit.Test;
import java.util.Collections;

import static org.junit.Assert.*;

public class ProjectSearchExpansionTest {
    private Project project(String id, String path) {
        return new Project(id, path, "App", 0, 0, "", "", true, false);
    }

    @Test public void projectsDefaultToExpandedAndToggleIndependently() {
        ProjectSearch.Expansion state = new ProjectSearch.Expansion();
        Project a = project("1", "/app");
        Project b = project("2", "/other");
        assertTrue(state.isExpanded(a));
        assertTrue(state.isExpanded(b));
        state.toggle(a);
        assertFalse(state.isExpanded(a));
        assertTrue(state.isExpanded(b));
        state.toggle(a);
        assertTrue(state.isExpanded(a));
    }

    @Test public void differentAndClearedQueriesDoNotResetCollapsedProjects() {
        ProjectSearch.Expansion state = new ProjectSearch.Expansion();
        Project a = project("1", "/app");
        state.toggle(a);
        for (String query : new String[] {"App", "no match", "", "/app"}) {
            ProjectSearch.filter(Collections.singletonList(a), Collections.emptyList(), query);
            assertFalse(state.isExpanded(a));
        }
        assertFalse(state.isExpanded(project("1", "/renamed")));
    }

    @Test public void legacyProjectsUsePathAndSavedStateRestoresCollapsedRows() {
        ProjectSearch.Expansion state = new ProjectSearch.Expansion();
        Project a = project("", "/app");
        Project b = project("", "/other");
        state.toggle(a);
        ProjectSearch.Expansion restored = new ProjectSearch.Expansion();
        restored.restore(state.snapshot());
        assertFalse(restored.isExpanded(project("", "/app")));
        assertTrue(restored.isExpanded(b));
        restored.restore(null);
        assertTrue(restored.isExpanded(a));
        assertFalse(state.isExpanded(a));
    }
}
