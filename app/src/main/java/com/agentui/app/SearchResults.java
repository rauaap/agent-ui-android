package com.agentui.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Search match state and keyed row coordination, independent of Android views.
 * The screen adapter only creates/binds widgets; tests use the same coordinator. */
final class SearchResults<R, G, S extends R> {
    interface Adapter<R, G, S extends R> {
        G createGroup();
        R projectRow(G group);
        R childrenRow(G group);
        CardViews.Rows<R> childRows(G group);
        void bindGroup(G view, ProjectSearch.Group data, boolean expanded);
        S createSession(G group);
        void bindSession(G group, S view, Session session);
    }
    private final Adapter<R, G, S> adapter;
    private final KeyedCards<ProjectSearch.Group, RetainedGroup> groups = new KeyedCards<>();
    private ProjectSearch.Index index = new ProjectSearch.Index(Collections.emptyList(), Collections.emptyList());
    private String query = "";
    private List<ProjectSearch.Group> matches = Collections.emptyList();

    SearchResults(Adapter<R, G, S> adapter) { this.adapter = adapter; }

    void updateCatalog(List<Project> projects, List<Session> sessions) {
        index = new ProjectSearch.Index(projects, sessions);
        matches = index.filter(query);
    }
    void setQuery(String value) {
        String normalized = ProjectSearch.normalizeQuery(value);
        if (query.equals(normalized)) return;
        query = normalized;
        matches = index.filter(query);
    }
    boolean hasQuery() { return !query.isEmpty(); }
    List<ProjectSearch.Group> matches() { return matches; }

    /** Server changes discard views/catalogs, but preserve the user's query. */
    void clear() {
        groups.clear();
        updateCatalog(Collections.emptyList(), Collections.emptyList());
    }

    /** Expansion, agent labels and existence changes rebind current matches only;
     * they do not rebuild the index or run the query again. */
    List<R> rows(ProjectSearch.Expansion expansion) {
        if (!hasQuery()) return Collections.emptyList();
        List<RetainedGroup> bound = groups.bind(matches,
                group -> ProjectSearch.projectKey(group.project),
                ignored -> new RetainedGroup(),
                (retained, data) -> {
                    boolean expanded = expansion.isExpanded(data.project);
                    adapter.bindGroup(retained.view, data, expanded);
                    if (expanded) {
                        List<S> children = retained.sessions.bind(data.sessions, session -> session.id,
                                ignored -> adapter.createSession(retained.view),
                                (view, session) -> adapter.bindSession(retained.view, view, session));
                        CardViews.reconcile(adapter.childRows(retained.view), children);
                    }
                });
        List<R> rows = new ArrayList<>();
        for (RetainedGroup retained : bound) {
            rows.add(adapter.projectRow(retained.view));
            rows.add(adapter.childrenRow(retained.view));
        }
        return rows;
    }

    private final class RetainedGroup {
        final G view = adapter.createGroup();
        final KeyedCards<Session, S> sessions = new KeyedCards<>();
    }
}
