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

import java.util.Collections;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Full-screen project/session search, using the same cards as the main lists. */
public class SearchActivity extends Activity {
    private Api api;
    private EditText query;
    private LinearLayout results;
    private ScrollView scroll;
    private List<Project> projects = Collections.emptyList();
    private List<Session> sessions = Collections.emptyList();
    private List<Agent> agents = Collections.emptyList();
    private List<Worktree> worktrees = Collections.emptyList();
    private boolean loading;
    private String error;
    private int loadGeneration;
    private final ProjectSearch.Expansion expansion = new ProjectSearch.Expansion();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
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
        loadGeneration++;
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

        scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        results = Widgets.column(this);
        results.setPadding(pad, pad, pad, pad);
        scroll.addView(results);
        root.addView(scroll);
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                render();
                scroll.scrollTo(0, 0);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        return root;
    }

    private boolean current(int generation) {
        return generation == loadGeneration && !isFinishing() && !isDestroyed();
    }

    private void load() {
        int generation = ++loadGeneration;
        loading = true;
        error = null;
        agents = Collections.emptyList();
        worktrees = Collections.emptyList();
        render();
        api.listWorktrees(null, new Api.Cb<List<Worktree>>() {
            @Override public void onResult(List<Worktree> list) {
                if (!current(generation)) return;
                worktrees = list;
                render();
            }
            @Override public void onError(String message) { /* Unknown is not missing. */ }
        });
        api.listProjects(new Api.StatusCb<List<Project>>() {
            @Override public void onResult(List<Project> list) {
                if (!current(generation)) return;
                api.listSessions(new Api.Cb<List<Session>>() {
                    @Override public void onResult(List<Session> listSessions) {
                        if (!current(generation)) return;
                        projects = list;
                        sessions = listSessions;
                        loading = false;
                        render();
                    }
                    @Override public void onError(String message) { fail(message); }
                });
            }
            private void fail(String message) {
                if (!current(generation)) return;
                loading = false;
                error = "Unable to search: " + message;
                render();
            }
            @Override public void onError(String message) { fail(message); }
            @Override public void onHttpError(int code, String message) { fail(message); }
        });
        api.listAgents(new Api.Cb<List<Agent>>() {
            @Override public void onResult(List<Agent> list) {
                if (!current(generation)) return;
                agents = list;
                render();
            }
            @Override public void onError(String message) { /* Fall back to raw ids. */ }
        });
    }

    private void render() {
        results.removeAllViews();
        String value = query.getText().toString();
        if (value.trim().isEmpty()) return;
        if (loading || error != null) {
            results.addView(Widgets.text(this, loading ? "Loading…" : error, Theme.MUTED, 14, false));
            return;
        }
        List<ProjectSearch.Group> groups = ProjectSearch.filter(projects, sessions, value);
        if (groups.isEmpty()) results.addView(Widgets.text(this, "No matches", Theme.FAINT, 14, false));
        for (ProjectSearch.Group group : groups) {
            Project p = group.project;
            boolean expanded = expansion.isExpanded(p);
            results.addView(ProjectCards.build(this, p, sessions,
                    () -> openProject(p), expanded ? R.drawable.ic_collapse : R.drawable.ic_expand,
                    (expanded ? "Collapse " : "Expand ") + p.name,
                    () -> {
                        expansion.toggle(p);
                        render();
                    }));
            if (!expanded || group.sessions.isEmpty()) continue;
            LinearLayout children = Widgets.column(this);
            children.setPadding(Theme.dp(this, 16), 0, 0, 0);
            for (Session session : group.sessions) {
                children.addView(SessionCards.build(this, session, p.path, p.isArchived(), agents, worktrees,
                        () -> startActivity(SessionActivity.intent(this, session, p.path)), null));
            }
            results.addView(children);
        }
    }

    private void openProject(Project p) {
        if (!p.exists) {
            confirmDelete(p, true);
            return;
        }
        Intent intent = new Intent(this, SessionListActivity.class);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_ID, p.id);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_DIR, p.path);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_NAME, p.name);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_IS_REPO, p.isGitRepo);
        intent.putExtra(SessionListActivity.EXTRA_PROJECT_ARCHIVED, p.isArchived());
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
