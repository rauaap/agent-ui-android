package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Full-screen project/session search, using the same cards as the main lists. */
public class SearchActivity extends Activity {
    private Api api;
    private CatalogRepository catalogs;
    private CatalogLoader loader;
    private String dataIdentity;
    private boolean projectsInitialized;
    private boolean sessionsInitialized;
    private final RefreshController agentRefresh = new RefreshController();
    private EditText query;
    private LinearLayout results;
    private ScrollView scroll;
    private List<Project> projects = Collections.emptyList();
    private List<Session> sessions = Collections.emptyList();
    private List<Agent> agents = Collections.emptyList();
    private boolean loading;
    private String error;
    private final ProjectSearch.Expansion expansion = new ProjectSearch.Expansion();
    private final SearchResults<View, SearchGroup, SessionCards.Card> searchResults =
            new SearchResults<>(new SearchResults.Adapter<View, SearchGroup, SessionCards.Card>() {
                public SearchGroup createGroup() { return new SearchGroup(); }
                public View projectRow(SearchGroup group) { return group.card; }
                public View childrenRow(SearchGroup group) { return group.children; }
                public CardViews.Rows<View> childRows(SearchGroup group) { return CardViews.rows(group.children); }
                public void bindGroup(SearchGroup view, ProjectSearch.Group data, boolean expanded) {
                    view.bind(data, expanded);
                }
                public SessionCards.Card createSession(SearchGroup group) {
                    return new SessionCards.Card(SearchActivity.this,
                            fresh -> startActivity(SessionActivity.intent(SearchActivity.this, fresh, group.project.path)),
                            null);
                }
                public void bindSession(SearchGroup group, SessionCards.Card view, Session fresh) {
                    view.bind(fresh, group.project.path, agents, existence);
                }
            });
    private TextView resultNotice;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        catalogs = new CatalogRepository(api);
        loader = new CatalogLoader(catalogs);
        setContentView(buildRoot());
        if (savedInstanceState != null) {
            expansion.restore(savedInstanceState.getStringArrayList("collapsed_projects"));
            query.setText(savedInstanceState.getString("query", ""));
        }
        query.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!api.prefs().isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        load();
    }

    @Override protected void onPause() {
        loader.cancel();
        agentRefresh.cancel();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query.getText().toString());
        state.putStringArrayList("collapsed_projects", expansion.snapshot());
        super.onSaveInstanceState(state);
    }

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);
        int pad = Theme.dp(this, 16);

        LinearLayout topbar = Widgets.row(this);
        topbar.setPadding(pad, pad, pad, pad);
        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setContentDescription("Back");
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        topbar.addView(back);
        topbar.addView(Widgets.text(this, "Search", Theme.INK, 18, true));
        root.addView(topbar);
        View divider = new View(this);
        divider.setBackgroundColor(Theme.LINE_SOFT);
        divider.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        root.addView(divider);

        LinearLayout input = Widgets.column(this);
        input.setPadding(pad, pad, pad, 0);
        query = Widgets.field(this, "Search projects and sessions", InputType.TYPE_CLASS_TEXT, false);
        query.setContentDescription("Search projects and sessions");
        input.addView(query);
        root.addView(input);
        visibility = new SessionVisibilityControl(this, api.prefs(), () -> {
            searchResults.setAgeDays(visibility.cutoff());
            render();
        });
        root.addView(visibility);

        scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        results = Widgets.column(this);
        results.setPadding(pad, Theme.dp(this, 8), pad, pad);
        scroll.addView(results);
        root.addView(scroll);
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchResults.setQuery(s.toString());
                render();
                scroll.scrollTo(0, 0);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        return root;
    }

    private SessionVisibilityControl visibility;
    private DirectorySnapshot existence = DirectorySnapshot.unknown();

    private void load() {
        loader.cancel();
        agentRefresh.cancel();
        if (!catalogs.identity().equals(dataIdentity)) {
            dataIdentity = catalogs.identity();
            searchResults.clear();
            projectsInitialized = sessionsInitialized = false;
            projects = Collections.emptyList();
            sessions = Collections.emptyList();
            existence = DirectorySnapshot.unknown();
            agents = Collections.emptyList();
        }
        loading = !hasPopulatedData();
        error = null;
        render();
        CatalogLoader.Listener updates = new CatalogLoader.Listener() {
            @Override public void cached(CatalogRepository.CachedCatalog cached) {
                if (cached.existence != null) existence = cached.existence;
                if (cached.projects != null) { projects = cached.projects; projectsInitialized = true; }
                if (cached.sessions != null) { sessions = cached.sessions; sessionsInitialized = true; }
                if (cached.projects != null || cached.sessions != null) catalogUpdated();
                else contentUpdated();
            }
            @Override public void projects(List<Project> list) {
                projects = list; projectsInitialized = true;
                catalogUpdated();
            }
            @Override public void sessions(List<Session> list) {
                sessions = list; sessionsInitialized = true;
                catalogUpdated();
            }
            @Override public void onResult(CatalogSnapshot snapshot) {
                existence = snapshot.existence;
                contentUpdated();
            }
            @Override public void onError(String message) {
                loading = false;
                if (!hasPopulatedData()) error = "Unable to search: " + message;
                render();
            }
        };
        if (hasPopulatedData()) loader.refreshProjects(false, updates);
        else loader.restoreProjects(updates);
        agentRefresh.start(false, run -> catalogs.agents(run.finish(new Api.Cb<List<Agent>>() {
            @Override public void onResult(List<Agent> list) {
                agents = list;
                render();
            }
            @Override public void onError(String message) { /* Fall back to raw ids. */ }
        })));
    }

    private boolean hasPopulatedData() { return projectsInitialized && sessionsInitialized; }

    private void catalogUpdated() {
        searchResults.setAgeDays(visibility.cutoff());
        searchResults.updateCatalog(projects, sessions);
        contentUpdated();
    }

    private void contentUpdated() {
        loading = !hasPopulatedData();
        if (!loading) error = null;
        render();
    }

    private void render() {
        visibility.refresh();
        searchResults.setAgeDays(visibility.cutoff());
        List<View> rows = new ArrayList<>();
        if (searchResults.hasQuery()) {
            if (loading || error != null) {
                rows.add(resultNotice(loading ? "Loading…" : error, Theme.MUTED));
            } else {
                rows.addAll(searchResults.rows(expansion));
                if (rows.isEmpty()) rows.add(resultNotice("No matches", Theme.FAINT));
            }
        }
        CardViews.reconcile(results, rows);
    }

    private TextView resultNotice(String text, int color) {
        if (resultNotice == null) resultNotice = Widgets.text(this, "", color, 14, false);
        Widgets.bindText(resultNotice, text);
        if (resultNotice.getCurrentTextColor() != color) resultNotice.setTextColor(color);
        return resultNotice;
    }

    /** Matching groups and their child cards survive ordinary query/data updates.
     * All click handlers consult rebound models, never constructor-time snapshots. */
    private final class SearchGroup {
        final ProjectCards.Card card;
        final LinearLayout children;
        private Project project;

        SearchGroup() {
            card = new ProjectCards.Card(SearchActivity.this, SearchActivity.this::openProject,
                    fresh -> { expansion.toggle(fresh); render(); },
                    R.drawable.ic_collapse, "Collapse project", false);
            children = Widgets.column(SearchActivity.this);
            children.setPadding(Theme.dp(SearchActivity.this, 16), 0, 0, 0);
        }

        void bind(ProjectSearch.Group group, boolean expanded) {
            project = group.project;
            card.bindSummary(project, group.status, existence);
            card.bindActionIcon(expanded ? R.drawable.ic_collapse : R.drawable.ic_expand,
                    (expanded ? "Collapse " : "Expand ") + project.name);
            // The coordinator retains collapsed children and rebinds before showing them.
            int visibility = expanded && !group.sessions.isEmpty() ? View.VISIBLE : View.GONE;
            if (children.getVisibility() != visibility) children.setVisibility(visibility);
        }
    }

    private void openProject(Project p) {
        if (existence.missing(p.path)) {
            confirmDelete(p, true);
            return;
        }
        Intent intent = new Intent(this, SessionListActivity.class);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_ID, p.id);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_DIR, p.path);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_NAME, p.name);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_IS_REPO, p.isGitRepo);
        startActivity(intent);
    }

    private void confirmDelete(Project p, boolean missing) {
        String message = missing
                ? "The directory for \"" + p.name + "\" no longer exists on the server:\n\n"
                    + p.path + "\n\nRemove this project? "
                : "Delete \"" + p.name + "\"? ";
        new AlertDialog.Builder(this)
                .setTitle(missing ? "Directory missing" : "Delete project")
                .setMessage(message + ProjectListActivity.sessionsPhrase(p)
                        + " The directory and its files are left on disk.")
                .setNegativeButton(missing ? "Keep" : "Cancel", null)
                .setPositiveButton(missing ? "Remove project" : "Delete", (d, w) ->
                        api.deleteProject(p.path, new Api.Cb<Api.ProjectDeletion>() {
                            @Override public void onResult(Api.ProjectDeletion deletion) {
                                if (isFinishing() || isDestroyed()) return;
                                load();
                                if (!deletion.worktreeErrors.isEmpty()) {
                                    new AlertDialog.Builder(SearchActivity.this)
                                            .setTitle("Worktrees left in place")
                                            .setMessage("The project is gone, but these worktrees could not be removed:\n\n"
                                                    + String.join("\n", deletion.worktreeErrors))
                                            .setPositiveButton("OK", null).show();
                                }
                            }
                            @Override public void onError(String message) {
                                Toast.makeText(SearchActivity.this, "Unable to delete: " + message,
                                        Toast.LENGTH_LONG).show();
                            }
                        }))
                .show();
    }
}
