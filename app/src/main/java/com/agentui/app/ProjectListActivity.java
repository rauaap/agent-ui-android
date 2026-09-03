package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Project list — the launcher screen. A project is a working directory; tapping
 * one opens the sessions inside it, where starting a fresh session costs one
 * tap instead of a name and a hand-typed path.
 */
public class ProjectListActivity extends Activity {

    private Api api;
    private Prefs prefs;
    private TextView projectCount;
    private LinearLayout listContainer;

    /** Set once a server 404s on /projects, so we stop probing it every resume. */
    private boolean legacyServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        prefs = api.prefs();
        setContentView(buildRoot());
        maybeRequestNotificationPermission();
    }

    /** Notifications need a runtime grant on Android 13+. */
    private void maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConfigured()) {
            renderNeedsServer();
        } else {
            loadProjects();
        }
    }

    /* ---------------------------------------------------------------- */
    /* layout                                                           */
    /* ---------------------------------------------------------------- */

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);

        // ---- topbar ----
        LinearLayout topbar = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        topbar.setPadding(pad, pad, pad, pad);
        topbar.setBackgroundColor(Theme.BG);

        LinearLayout brand = Widgets.column(this);
        LinearLayout brandHead = Widgets.row(this);
        View dot = new View(this);
        dot.setBackground(Theme.pill(this, Theme.ACCENT, Theme.ACCENT_SOFT, 4));
        LinearLayout.LayoutParams dotLp = lp(Theme.dp(this, 10), Theme.dp(this, 10));
        dotLp.rightMargin = Theme.dp(this, 10);
        dot.setLayoutParams(dotLp);
        TextView title = Widgets.text(this, "Agent UI", Theme.INK, 18, true);
        brandHead.addView(dot);
        brandHead.addView(title);
        projectCount = Widgets.text(this, "", Theme.MUTED, 13, false);
        Widgets.margins(projectCount, 0, Theme.dp(this, 2), 0, 0);
        brand.addView(brandHead);
        brand.addView(projectCount);
        brand.setLayoutParams(lp(0, WRAP, 1f));
        topbar.addView(brand);

        TextView gear = Widgets.ghostButton(this, "⚙");
        gear.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        Widgets.margins(gear, 0, 0, Theme.dp(this, 8), 0);
        topbar.addView(gear);

        TextView newBtn = Widgets.primaryButton(this, "+ New");
        newBtn.setOnClickListener(v -> showNewProjectDialog());
        topbar.addView(newBtn);

        root.addView(topbar);
        root.addView(divider());

        // ---- scrolling list ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        scroll.setFillViewport(true);
        listContainer = Widgets.column(this);
        listContainer.setPadding(pad, pad, pad, pad);
        scroll.addView(listContainer);
        root.addView(scroll);

        return root;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        v.setBackgroundColor(Theme.LINE_SOFT);
        return v;
    }

    /* ---------------------------------------------------------------- */
    /* data                                                             */
    /* ---------------------------------------------------------------- */

    private void loadProjects() {
        if (legacyServer) {
            openUnscopedSessions();
            return;
        }
        projectCount.setText("…");
        api.listProjects(new Api.StatusCb<List<Project>>() {
            @Override public void onResult(List<Project> projects) {
                // Project rows do not carry session state, so fetch the flat
                // session list and aggregate its statuses onto each card.
                api.listSessions(new Api.Cb<List<Session>>() {
                    @Override public void onResult(List<Session> sessions) {
                        renderList(projects, sessions, null);
                    }

                    @Override public void onError(String message) {
                        // Projects are still useful if this secondary request
                        // fails; simply omit their live status indicators.
                        renderList(projects, null, null);
                    }
                });
            }
            @Override public void onError(String message) { renderList(null, null, message); }

            @Override public void onHttpError(int code, String message) {
                // An older server has no /projects. Fall through to the flat
                // session list — exactly the behaviour before this screen existed.
                if (code == 404) {
                    legacyServer = true;
                    openUnscopedSessions();
                } else {
                    renderList(null, null, message);
                }
            }
        });
    }

    /** Back-compat path: hand over to the unscoped session list and step aside. */
    private void openUnscopedSessions() {
        startActivity(new Intent(this, SessionListActivity.class));
        finish();
    }

    private void renderNeedsServer() {
        projectCount.setText("not configured");
        listContainer.removeAllViews();
        LinearLayout box = emptyBox("No server set.\nTap to configure the server address.");
        box.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        listContainer.addView(box);
    }

    private void renderList(List<Project> projects, List<Session> sessions, String error) {
        listContainer.removeAllViews();

        if (error != null) {
            projectCount.setText("unreachable");
            listContainer.addView(emptyBox(error + "\n\nCheck the server address in Settings."));
            return;
        }

        int n = projects.size();
        projectCount.setText(n + (n == 1 ? " project" : " projects"));

        if (n == 0) {
            listContainer.addView(emptyBox("No projects yet.\nTap + New to start one."));
            return;
        }

        for (Project p : projects) listContainer.addView(projectCard(p, sessions));
    }

    private LinearLayout emptyBox(String message) {
        LinearLayout box = Widgets.column(this);
        box.setGravity(Gravity.CENTER);
        TextView t = Widgets.text(this, message, Theme.FAINT, 14, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0x05FFFFFF);
        d.setCornerRadius(Theme.dp(this, 14));
        d.setStroke(Theme.dp(this, 1), Theme.LINE, Theme.dp(this, 6), Theme.dp(this, 5));
        box.setBackground(d);
        LinearLayout.LayoutParams p = lp(MATCH, Theme.dp(this, 180));
        box.setLayoutParams(p);
        int pad = Theme.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private View projectCard(Project p, List<Session> allSessions) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(this, 12);
        card.setLayoutParams(cardLp);
        card.setClickable(true);
        card.setOnClickListener(v -> openProject(p));

        // head: name + (missing badge, delete)
        LinearLayout head = Widgets.row(this);
        TextView name = Widgets.text(this, p.name, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);

        String status = aggregateStatus(p, allSessions);
        if (status != null) {
            TextView badge = Widgets.statusBadge(this, status);
            Widgets.margins(badge, Theme.dp(this, 8), 0, 0, 0);
            head.addView(badge);
        }

        if (!p.exists) {
            TextView missing = Widgets.tag(this, "missing", Theme.DANGER);
            Widgets.margins(missing, Theme.dp(this, 8), 0, 0, 0);
            head.addView(missing);
        }

        TextView del = Widgets.text(this, "🗑", Theme.MUTED, 15, false);
        int dp32 = Theme.dp(this, 32);
        del.setGravity(Gravity.CENTER);
        del.setLayoutParams(lp(dp32, dp32));
        Widgets.margins(del, Theme.dp(this, 8), 0, 0, 0);
        del.setClickable(true);
        del.setOnClickListener(v -> confirmDelete(p));
        head.addView(del);
        card.addView(head);

        TextView path = Widgets.mono(this, p.path, p.exists ? Theme.FAINT : Theme.DANGER, 12);
        Widgets.margins(path, 0, Theme.dp(this, 10), 0, 0);
        card.addView(path);

        String sessions = p.sessionCount + (p.sessionCount == 1 ? " session" : " sessions");
        String when = SessionListActivity.formatTime(p.lastActiveAt);
        TextView metaView = Widgets.text(this,
                when.isEmpty() ? sessions : sessions + "  ·  " + when,
                Theme.MUTED, 12.5f, false);
        Widgets.margins(metaView, 0, Theme.dp(this, 8), 0, 0);
        card.addView(metaView);

        return card;
    }

    /**
     * Most urgent live status among this project's sessions. Waiting for user
     * input wins over running, and idle projects get no indicator.
     */
    private String aggregateStatus(Project project, List<Session> sessions) {
        if (sessions == null) return null;
        boolean running = false;
        for (Session session : sessions) {
            boolean belongsToProject = !project.id.isEmpty() && !session.projectId.isEmpty()
                    ? project.id.equals(session.projectId)
                    : project.path.equals(session.workingDir);
            if (!belongsToProject) continue;
            if ("awaiting_approval".equals(session.status)) return "awaiting_approval";
            if ("running".equals(session.status)) running = true;
        }
        return running ? "running" : null;
    }

    private void openProject(Project p) {
        // Opening a project whose directory is gone would just produce agent
        // errors on the first turn, so explain it up front instead.
        if (!p.exists) {
            showMissingDirectoryDialog(p);
            return;
        }
        Intent i = new Intent(this, SessionListActivity.class);
        i.putExtra(SessionListActivity.EXTRA_PROJECT_ID, p.id);
        i.putExtra(SessionListActivity.EXTRA_PROJECT_DIR, p.path);
        i.putExtra(SessionListActivity.EXTRA_PROJECT_NAME, p.name);
        i.putExtra(SessionListActivity.EXTRA_PROJECT_IS_REPO, p.isGitRepo);
        startActivity(i);
    }

    /**
     * Back-compat path: a server with no {@code POST /projects} has no project
     * row to identify either, so the session list falls back to matching on the
     * directory.
     */
    private void openProjectDir(String path, String name) {
        Intent i = new Intent(this, SessionListActivity.class);
        i.putExtra(SessionListActivity.EXTRA_PROJECT_DIR, path);
        i.putExtra(SessionListActivity.EXTRA_PROJECT_NAME, name);
        startActivity(i);
    }

    /* ---------------------------------------------------------------- */
    /* new project dialog                                               */
    /* ---------------------------------------------------------------- */

    /**
     * Pick a path and have the server create the directory, so the project
     * lists straight away instead of only once its first session exists.
     */
    private void showNewProjectDialog() {
        if (!prefs.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        LinearLayout content = Widgets.column(this);
        int pad = Theme.dp(this, 20);
        content.setPadding(pad, pad, pad, pad);

        content.addView(fieldLabel("Name"));
        EditText nameField = field("my-project", false);
        content.addView(nameField);

        View gap = new View(this);
        gap.setLayoutParams(lp(MATCH, Theme.dp(this, 14)));
        content.addView(gap);

        content.addView(fieldLabel("Working directory"));
        EditText dirField = field("/projects/my-project", true);
        dirField.setText(prefs.defaultDir());
        dirField.setSelection(dirField.getText().length());
        content.addView(dirField);

        // Live-append the name onto the projects directory as it's typed, until
        // the user takes manual control of the directory field.
        final boolean[] dirEdited = {false};
        final boolean[] programmatic = {false};
        dirField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!programmatic[0]) dirEdited[0] = true;
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        nameField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (dirEdited[0]) return;
                programmatic[0] = true;
                String dir = underProjectsDir(s.toString());
                dirField.setText(dir);
                dirField.setSelection(dir.length());
                programmatic[0] = false;
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        TextView hint = Widgets.text(this,
                "The directory is created on the server if it does not exist.",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(hint, 0, Theme.dp(this, 10), 0, 0);
        content.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New project")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null) // overridden below to keep dialog open on error
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = nameField.getText().toString().trim();
                    String dir = dirField.getText().toString().trim();
                    if (name.isEmpty() || dir.isEmpty()) {
                        toast("Name and working directory are required");
                        return;
                    }
                    if (!dir.startsWith("/")) {
                        toast("Working directory must be an absolute path");
                        return;
                    }
                    // Trailing slashes would make the same directory look like a
                    // different project than the one the server reports back.
                    while (dir.length() > 1 && dir.endsWith("/")) {
                        dir = dir.substring(0, dir.length() - 1);
                    }
                    final String path = dir;
                    v.setEnabled(false);
                    api.createProject(path, name, new Api.StatusCb<Project>() {
                        @Override public void onResult(Project project) {
                            dialog.dismiss();
                            openProject(project);
                        }

                        @Override public void onError(String message) {
                            v.setEnabled(true);
                            toast("Unable to create: " + message);
                        }

                        @Override public void onHttpError(int code, String message) {
                            if (code == 404) {
                                // Older server: no endpoint to create it, but
                                // the first session's mkdir still will.
                                dialog.dismiss();
                                openProjectDir(path, name);
                            } else {
                                v.setEnabled(true);
                                toast("Unable to create: " + message);
                            }
                        }
                    });
                }));
        dialog.show();
    }

    /** Last path segment, matching how the server derives a project name. */
    static String basename(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? path : name;
    }

    /** The configured projects directory with {@code name} appended. */
    private String underProjectsDir(String name) {
        String base = prefs.defaultDir().trim();
        if (base.isEmpty()) base = "/projects/";
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private TextView fieldLabel(String s) {
        TextView t = Widgets.text(this, s, Theme.MUTED, 13, true);
        Widgets.margins(t, 0, 0, 0, Theme.dp(this, 7));
        return t;
    }

    private EditText field(String hint, boolean mono) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(mono
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT);
        e.setTextColor(Theme.INK);
        e.setHintTextColor(Theme.FAINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        e.setPadding(p, 0, p, 0);
        e.setMinHeight(Theme.dp(this, 44));
        e.setLayoutParams(lp(MATCH, WRAP));
        return e;
    }

    /* ---------------------------------------------------------------- */
    /* delete + missing directory                                       */
    /* ---------------------------------------------------------------- */

    /**
     * The directory went away while the project row stayed. Explain it and
     * offer the only two useful moves: forget the project, or leave it alone
     * (e.g. the disk is not mounted yet).
     */
    private void showMissingDirectoryDialog(Project p) {
        new AlertDialog.Builder(this)
                .setTitle("Directory missing")
                .setMessage("The directory for \"" + p.name + "\" no longer exists "
                        + "on the server:\n\n" + p.path + "\n\n"
                        + "Remove this project? " + sessionsPhrase(p.sessionCount)
                        + " Nothing on disk is touched.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove project", (d, w) -> deleteProject(p))
                .show();
    }

    private void confirmDelete(Project p) {
        new AlertDialog.Builder(this)
                .setTitle("Delete project")
                .setMessage("Delete \"" + p.name + "\"? " + sessionsPhrase(p.sessionCount)
                        + " The directory and its files are left on disk.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleteProject(p))
                .show();
    }

    /** Spells out what deleting costs, since scrollback is not recoverable. */
    private static String sessionsPhrase(int count) {
        if (count == 0) return "It has no sessions.";
        return "This removes " + count + (count == 1 ? " session" : " sessions")
                + " and their history.";
    }

    private void deleteProject(Project p) {
        api.deleteProject(p.path, new Api.Cb<Api.ProjectDeletion>() {
            @Override public void onResult(Api.ProjectDeletion deletion) {
                loadProjects();
                if (!deletion.worktreeErrors.isEmpty()) showWorktreesLeftDialog(deletion);
            }
            @Override public void onError(String message) {
                toast("Unable to delete: " + message);
            }
        });
    }

    /**
     * The project is gone; some of its worktrees are not. Removal is never
     * forced, and git counts untracked files as dirty, so any worktree an agent
     * did real work in stays — a notice, in the same category as the project's
     * own directory being left alone, not an error.
     */
    private void showWorktreesLeftDialog(Api.ProjectDeletion deletion) {
        int n = deletion.worktreeErrors.size();
        StringBuilder message = new StringBuilder(n == 1
                ? "One worktree had uncommitted work and was left in place:\n"
                : n + " worktrees had uncommitted work and were left in place:\n");
        for (String error : deletion.worktreeErrors) message.append("\n").append(error);
        new AlertDialog.Builder(this)
                .setTitle("Worktrees left in place")
                .setMessage(message.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
