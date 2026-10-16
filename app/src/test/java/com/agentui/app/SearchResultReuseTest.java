package com.agentui.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Tests the production search coordinator. The adapter supplies fake widgets only,
 * not a second implementation of matching, retention or expansion coordination. */
public class SearchResultReuseTest {
    private static final class Rows implements CardViews.Rows<Object> {
        final List<Object> values = new ArrayList<>();
        int adds, removes;
        public int size() { return values.size(); }
        public Object get(int index) { return values.get(index); }
        public void remove(int index) { removes++; values.remove(index); }
        public void add(int index, Object value) { adds++; values.add(index, value); }
        void resetCounts() { adds = removes = 0; }
    }
    private static final class SessionCard { Session session; }
    private static final class Group {
        final Rows children = new Rows();
        Project project;
        boolean expanded;
        String status;
    }
    private final ProjectSearch.Expansion expansion = new ProjectSearch.Expansion();
    private final Rows rows = new Rows();
    private int groupsCreated, sessionsCreated;
    private final SearchResults<Object, Group, SessionCard> results = new SearchResults<>(
            new SearchResults.Adapter<Object, Group, SessionCard>() {
                public Group createGroup() { groupsCreated++; return new Group(); }
                public Object projectRow(Group group) { return group; }
                public Object childrenRow(Group group) { return group.children; }
                public CardViews.Rows<Object> childRows(Group group) { return group.children; }
                public void bindGroup(Group view, ProjectSearch.Group data, boolean expanded) {
                    view.project = data.project; view.expanded = expanded; view.status = data.status;
                }
                public SessionCard createSession(Group group) { sessionsCreated++; return new SessionCard(); }
                public void bindSession(Group group, SessionCard view, Session fresh) { view.session = fresh; }
            });

    private void render() { CardViews.reconcile(rows, results.rows(expansion)); }
    private Group type(String query) {
        results.setQuery(query); render();
        return (Group) rows.get(0);
    }
    private static Project project(String id, String path, String name) {
        return new Project(id, path, name, 2, "", true);
    }
    private static Session session(String id, String name, String projectId) {
        return new Session(id, name, projectId, "/tree", "w", "pi", "idle", "", false, false);
    }

    @Test public void togglingAgeRetainsProjectAndRestoresHiddenChildren() {
        Project app = project("p", "/app", "Application");
        Session old = new Session("a", "Old", "p", "/app", "", "pi", "idle",
                "2020-01-01T00:00:00Z", false, false);
        results.updateCatalog(List.of(app), List.of(old));
        Group group = type("app");
        results.setAgeDays(7); render();
        assertSame(group, rows.get(0));
        assertEquals(0, group.children.size());
        assertEquals(1, results.hiddenCount());
        List<ProjectSearch.Group> matches = results.matches();
        results.setAgeDays(7);
        assertSame(matches, results.matches());
        results.setAgeDays(null); render();
        assertSame(group, rows.get(0));
        assertEquals(1, group.children.size());
        assertEquals(0, results.hiddenCount());
        results.setAgeDays(7);
        type("old");
        assertEquals(1, group.children.size());
        assertEquals(0, results.hiddenCount());
    }

    @Test public void typingWithinSameProjectMatchesDoesNotRecreateOrDetachCards() {
        Project app = project("p", "/app", "Application");
        results.updateCatalog(List.of(app), List.of(session("a", "One", "p"), session("b", "Two", "p")));
        Group first = type("app"); Object child = first.children.get(0);
        rows.resetCounts(); first.children.resetCounts();
        Group updated = type("appli");
        assertSame(first, updated); assertSame(child, updated.children.get(0));
        assertEquals(1, groupsCreated); assertEquals(2, sessionsCreated);
        assertEquals(0, rows.adds); assertEquals(0, rows.removes);
        assertEquals(0, updated.children.adds); assertEquals(0, updated.children.removes);
    }

    @Test public void narrowingSessionMatchesOnlyRemovesTheUnmatchedChild() {
        Project app = project("p", "/app", "Application");
        results.updateCatalog(List.of(app), List.of(session("a", "Login notes", "p"), session("b", "Login fix", "p")));
        Group first = type("login"); Object retained = first.children.get(0);
        rows.resetCounts(); first.children.resetCounts();
        Group narrowed = type("login n");
        assertSame(first, narrowed); assertSame(retained, narrowed.children.get(0));
        assertEquals(1, narrowed.children.size());
        assertEquals(1, narrowed.children.removes); assertEquals(0, narrowed.children.adds);
        assertEquals(0, rows.removes); assertEquals(0, rows.adds);
        assertEquals(2, sessionsCreated);
    }

    @Test public void expansionAndPresentationRebindingDoNotRecomputeMatches() {
        Project app = project("p", "/app", "Application");
        results.updateCatalog(List.of(app), List.of(session("a", "One", "p")));
        Group first = type("app"); Object retained = first.children.get(0);
        List<ProjectSearch.Group> matches = results.matches();
        expansion.toggle(app); render();
        assertFalse(first.expanded); assertSame(matches, results.matches());
        // Agent-label/existence updates use this same presentation-only path.
        render(); assertSame(matches, results.matches());
        expansion.toggle(app); render();
        assertTrue(first.expanded); assertSame(retained, first.children.get(0));
        assertSame(matches, results.matches());
        assertEquals(1, groupsCreated); assertEquals(1, sessionsCreated);
    }

    @Test public void refreshedModelsAreReboundBeforeOpeningSessionFromRetainedGroup() {
        Project original = project("p", "/old", "App");
        results.updateCatalog(List.of(original), List.of(session("a", "Old", "p")));
        Group first = type("app"); SessionCard retained = (SessionCard) first.children.get(0);
        Project fresh = project("p", "/new", "App renamed");
        Session freshSession = session("a", "New", "p");
        results.updateCatalog(List.of(fresh), List.of(freshSession)); render();
        Group updated = (Group) rows.get(0);
        assertSame(first, updated); assertSame(retained, updated.children.get(0));
        assertSame(fresh, updated.project); assertSame(freshSession, retained.session);
        assertEquals("/new", updated.project.path);
    }

    @Test public void legacyProjectKeysDoNotCollideAndIdentityResetPreservesQueryOnly() {
        List<Project> projects = List.of(project("", "/first", "App"), project("", "/second", "App"));
        results.updateCatalog(projects, List.of());
        Group original = type("app"); assertNotSame(original, rows.get(2));
        results.clear();
        assertTrue(results.hasQuery()); assertTrue(results.matches().isEmpty());
        render(); assertEquals(0, rows.size());
        results.updateCatalog(projects, List.of()); render();
        assertNotSame(original, rows.get(0)); assertEquals(4, groupsCreated);
    }

    @Test public void equivalentNormalizedQueryDoesNotRecomputeMatches() {
        results.updateCatalog(List.of(project("p", "/app", "App")), List.of());
        type("app"); List<ProjectSearch.Group> matches = results.matches();
        type(" APP "); assertSame(matches, results.matches());
    }

    @Test public void clearingQueryRemovesRowsAndReapplyingItComputesMatchesAgain() {
        results.updateCatalog(List.of(project("p", "/app", "App")), List.of());
        type("app");
        results.setQuery(" \n "); render();
        assertFalse(results.hasQuery()); assertTrue(results.matches().isEmpty()); assertEquals(0, rows.size());
        type("app"); assertEquals(2, rows.size());
    }
}
