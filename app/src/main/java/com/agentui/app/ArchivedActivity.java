package com.agentui.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Everything archived, in one place, reached from Settings. Two sections: the
 * archived projects, and the archived sessions that sit under a project which
 * is still live — a session inside an archived project is already covered by
 * that project's row and is not listed twice.
 *
 * <p>Both are sorted by {@code archived_at} descending, most recently filed
 * first, and explicitly not by the order {@code GET /projects} returns:
 * archived projects have a null {@code last_active_at} and so always arrive at
 * the end of that listing, in no order worth showing.
 *
 * <p>Rows open what they point at — keeping old work readable is the point of
 * the feature — and Restore puts it back on the lists it came from.
 */
public class ArchivedActivity extends Activity {

    static final String EXTRA_PROJECT_ID = "project_id";
    static final String EXTRA_PROJECT_NAME = "project_name";

    private Api api;
    private Prefs prefs;
    private String filterProjectId;
    private String filterProjectName;
    private TextView subtitle;
    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        prefs = api.prefs();
        filterProjectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        filterProjectName = getIntent().getStringExtra(EXTRA_PROJECT_NAME);
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);

        LinearLayout header = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        header.setPadding(pad, pad, pad, pad);
        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);
        LinearLayout titles = Widgets.column(this);
        titles.addView(Widgets.text(this, "Archived", Theme.INK, 18, true));
        subtitle = Widgets.text(this, "", Theme.MUTED, 13, false);
        Widgets.margins(subtitle, 0, Theme.dp(this, 2), 0, 0);
        titles.addView(subtitle);
        header.addView(titles);
        root.addView(header);

        View div = new View(this);
        div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        div.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(div);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        scroll.setFillViewport(true);
        listContainer = Widgets.column(this);
        listContainer.setPadding(pad, pad, pad, pad);
        scroll.addView(listContainer);
        root.addView(scroll);

        return root;
    }

    /* ---------------------------------------------------------------- */
    /* data                                                             */
    /* ---------------------------------------------------------------- */

    /**
     * Both listings are needed and both are enough: neither is filtered by the
     * server, so the archive is already in them and there is no third request
     * to make.
     */
    private void load() {
        if (!prefs.isConfigured()) {
            render(null, null, "No server set.");
            return;
        }
        subtitle.setText("…");
        api.listSessions(new Api.Cb<List<Session>>() {
            @Override public void onResult(List<Session> sessions) { loadProjects(sessions); }
            @Override public void onError(String message) { render(null, null, message); }
        });
    }

    private void loadProjects(List<Session> sessions) {
        api.listProjects(new Api.StatusCb<List<Project>>() {
            @Override public void onResult(List<Project> projects) { render(projects, sessions, null); }
            @Override public void onError(String message) { render(null, null, message); }

            @Override public void onHttpError(int code, String message) {
                // An older server has no projects to group by — and no archive
                // either, so the sessions section will come out empty too.
                if (code == 404) render(new ArrayList<>(), sessions, null);
                else render(null, null, message);
            }
        });
    }

    private void render(List<Project> projects, List<Session> sessions, String error) {
        listContainer.removeAllViews();

        if (error != null) {
            subtitle.setText("unavailable");
            listContainer.addView(emptyBox(error));
            return;
        }

        List<Project> archivedProjects = filterProjectId == null
                ? Archive.archivedProjects(projects) : new ArrayList<>();
        List<Session> looseSessions = Archive.looseArchivedSessions(projects, sessions);
        if (filterProjectId != null) {
            List<Session> filtered = new ArrayList<>();
            for (Session session : looseSessions) {
                if (filterProjectId.equals(session.projectId)) filtered.add(session);
            }
            looseSessions = filtered;
        }

        String counts = summary(archivedProjects.size(), looseSessions.size());
        subtitle.setText(filterProjectId == null ? counts
                : (filterProjectName == null ? counts : filterProjectName + "  ·  " + counts));

        if (archivedProjects.isEmpty() && looseSessions.isEmpty()) {
            listContainer.addView(emptyBox(filterProjectId == null
                    ? "Nothing archived.\n\nArchive a session from its ⚙ settings, or a whole "
                            + "project from the project's ⚙ settings."
                    : "No archived sessions in this project."));
            return;
        }

        if (!archivedProjects.isEmpty()) {
            listContainer.addView(section("Projects"));
            for (Project p : archivedProjects) listContainer.addView(projectCard(p));
        }

        if (!looseSessions.isEmpty()) {
            if (!archivedProjects.isEmpty()) listContainer.addView(spacer(12));
            listContainer.addView(section("Sessions"));
            for (Session s : looseSessions) listContainer.addView(sessionCard(s, projects));
        }
    }

    private static String summary(int projects, int sessions) {
        if (projects == 0 && sessions == 0) return "nothing archived";
        List<String> parts = new ArrayList<>();
        if (projects > 0) parts.add(projects + (projects == 1 ? " project" : " projects"));
        if (sessions > 0) parts.add(sessions + (sessions == 1 ? " session" : " sessions"));
        return String.join("  ·  ", parts);
    }

    /* ---------------------------------------------------------------- */
    /* cards                                                            */
    /* ---------------------------------------------------------------- */

    private View projectCard(Project p) {
        LinearLayout card = card();
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, SessionListActivity.class);
            i.putExtra(SessionListActivity.EXTRA_PROJECT_ID, p.id);
            i.putExtra(SessionListActivity.EXTRA_PROJECT_DIR, p.path);
            i.putExtra(SessionListActivity.EXTRA_PROJECT_NAME, p.name);
            i.putExtra(SessionListActivity.EXTRA_PROJECT_IS_REPO, p.isGitRepo);
            i.putExtra(SessionListActivity.EXTRA_PROJECT_ARCHIVED, true);
            startActivity(i);
        });

        LinearLayout head = Widgets.row(this);
        TextView name = Widgets.text(this, p.name, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);
        head.addView(deleteButton(v -> confirmDeleteProject(p)));
        head.addView(restoreButton(v -> restoreProject(p, v)));
        card.addView(head);

        TextView path = Widgets.mono(this, p.path, Theme.FAINT, 12);
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Widgets.margins(path, 0, Theme.dp(this, 10), 0, 0);
        card.addView(path);

        // An archived project reports session_count 0 and last_active_at null by
        // construction; showing those would read as "empty, never used", which
        // is the opposite of true. Its archived count and filing date are real.
        int n = p.archivedSessionCount;
        String meta = n + (n == 1 ? " session" : " sessions")
                + "  ·  archived " + SessionListActivity.formatTime(p.archivedAt);
        TextView metaView = Widgets.text(this, meta, Theme.MUTED, 12.5f, false);
        Widgets.margins(metaView, 0, Theme.dp(this, 8), 0, 0);
        card.addView(metaView);

        return card;
    }

    private View sessionCard(Session s, List<Project> projects) {
        LinearLayout card = card();
        Project project = projectFor(s, projects);
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, SessionActivity.class);
            i.putExtra(SessionActivity.EXTRA_ID, s.id);
            i.putExtra(SessionActivity.EXTRA_NAME, s.name);
            i.putExtra(SessionActivity.EXTRA_DIR, s.workingDir);
            i.putExtra(SessionActivity.EXTRA_PROJECT_DIR, project == null ? null : project.path);
            i.putExtra(SessionActivity.EXTRA_WORKTREE_ID, s.worktreeId);
            i.putExtra(SessionActivity.EXTRA_STATUS, s.status);
            i.putExtra(SessionActivity.EXTRA_AUTO_WRITE, s.autoApproveWrite);
            i.putExtra(SessionActivity.EXTRA_AUTO_COMMAND, s.autoApproveCommand);
            i.putExtra(SessionActivity.EXTRA_ARCHIVED, true);
            startActivity(i);
        });

        LinearLayout head = Widgets.row(this);
        TextView name = Widgets.text(this, s.name, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);
        head.addView(deleteButton(v -> confirmDeleteSession(s)));
        head.addView(restoreButton(v -> restoreSession(s, v)));
        card.addView(head);

        // Which project it goes back to, since this list crosses all of them.
        TextView origin = Widgets.text(this, projectOf(s, projects), Theme.MUTED, 12.5f, false);
        origin.setSingleLine(true);
        origin.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Widgets.margins(origin, 0, Theme.dp(this, 10), 0, 0);
        card.addView(origin);

        if (!s.worktreeId.isEmpty() || (project != null && s.isFormerWorktree(project.path))) {
            LinearLayout where = Widgets.row(this);
            TextView tag = Widgets.tag(this,
                    s.worktreeId.isEmpty() ? "former worktree" : "worktree",
                    s.worktreeId.isEmpty() ? Theme.MUTED : Theme.INFO);
            Widgets.margins(tag, 0, 0, Theme.dp(this, 8), 0);
            where.addView(tag);
            TextView path = Widgets.mono(this, s.workingDir, Theme.FAINT, 12);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            path.setLayoutParams(lp(0, WRAP, 1f));
            where.addView(path);
            Widgets.margins(where, 0, Theme.dp(this, 8), 0, 0);
            card.addView(where);
        }

        TextView metaView = Widgets.text(this,
                Agent.label(Agent.FALLBACK, s.agent)
                        + "  ·  archived " + SessionListActivity.formatTime(s.archivedAt),
                Theme.FAINT, 12, false);
        Widgets.margins(metaView, 0, Theme.dp(this, 6), 0, 0);
        card.addView(metaView);

        return card;
    }

    private Project projectFor(Session s, List<Project> projects) {
        for (Project p : projects) {
            if (p.owns(s)) return p;
        }
        return null;
    }

    /** The project's name, falling back to the session's directory. */
    private String projectOf(Session s, List<Project> projects) {
        Project project = projectFor(s, projects);
        return project == null ? s.workingDir : project.name;
    }

    /* ---------------------------------------------------------------- */
    /* restoring                                                        */
    /* ---------------------------------------------------------------- */

    private void restoreProject(Project p, View button) {
        button.setEnabled(false);
        api.setProjectArchived(p.path, false, new Api.StatusCb<Api.ProjectArchive>() {
            @Override public void onResult(Api.ProjectArchive result) {
                // Only what this project's own archive swept up comes back, so
                // the server's count is the one to report — not the row's.
                toast("Restored  ·  " + result.sessionsAffected
                        + (result.sessionsAffected == 1 ? " session" : " sessions"));
                load();
            }
            @Override public void onError(String message) {
                button.setEnabled(true);
                toast("Couldn't restore: " + message);
            }
            @Override public void onHttpError(int code, String message) {
                button.setEnabled(true);
                if (code == 409) showRestoreBlocked(message, true, null);
                else onError(message);
            }
        });
    }

    private void restoreSession(Session s, View button) {
        button.setEnabled(false);
        api.setSessionArchived(s.id, false, new Api.StatusCb<Session>() {
            @Override public void onResult(Session session) {
                // This also unarchives the session's project if that was
                // archived — the reload picks the change up either way.
                toast("Restored");
                load();
            }
            @Override public void onError(String message) {
                button.setEnabled(true);
                toast("Couldn't restore: " + message);
            }
            @Override public void onHttpError(int code, String message) {
                button.setEnabled(true);
                if (code == 409) showRestoreBlocked(message, false, s.workingDir);
                else onError(message);
            }
        });
    }

    private void showRestoreBlocked(String detail, boolean project, String path) {
        String message = detail + "\n\n" + (project
                ? "Nothing was restored. Recreate the missing directories at the same absolute "
                        + "paths, then try again."
                : "Recreate a directory at the same absolute path, then try again:\n\n" + path);
        new android.app.AlertDialog.Builder(this)
                .setTitle(project ? "Working directories unavailable" : "Working directory unavailable")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmDeleteProject(Project p) {
        int sessions = p.archivedSessionCount;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete archived project")
                .setMessage("Delete \"" + p.name + "\" and its " + sessions
                        + (sessions == 1 ? " session" : " sessions")
                        + " and history? The project directory is left on disk. Worktrees "
                        + "are removed only when git says they are safe.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> api.deleteProject(p.path,
                        new Api.Cb<Api.ProjectDeletion>() {
                            @Override public void onResult(Api.ProjectDeletion deletion) {
                                if (deletion.worktreeErrors.isEmpty()) toast("Deleted");
                                else toast("Deleted  ·  " + deletion.worktreeErrors.size()
                                        + " worktree(s) left on disk");
                                load();
                            }
                            @Override public void onError(String message) {
                                toast("Couldn't delete: " + message);
                            }
                        }))
                .show();
    }

    private void confirmDeleteSession(Session s) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete archived session")
                .setMessage("Delete \"" + s.name + "\" and its history? Nothing on disk "
                        + "is touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> api.deleteSession(s.id,
                        new Api.Cb<Void>() {
                            @Override public void onResult(Void ignored) {
                                toast("Deleted");
                                load();
                            }
                            @Override public void onError(String message) {
                                toast("Couldn't delete: " + message);
                            }
                        }))
                .show();
    }

    private TextView deleteButton(View.OnClickListener action) {
        TextView b = Widgets.ghostButton(this, "🗑");
        b.setLayoutParams(lp(Theme.dp(this, 40), Theme.dp(this, 34)));
        Widgets.margins(b, Theme.dp(this, 8), 0, 0, 0);
        b.setOnClickListener(action);
        return b;
    }

    private TextView restoreButton(View.OnClickListener action) {
        TextView b = Widgets.ghostButton(this, "Restore");
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        b.setMinimumHeight(Theme.dp(this, 34));
        int padH = Theme.dp(this, 12);
        b.setPadding(padH, 0, padH, 0);
        b.setLayoutParams(lp(WRAP, Theme.dp(this, 34)));
        Widgets.margins(b, Theme.dp(this, 8), 0, 0, 0);
        b.setOnClickListener(action);
        return b;
    }

    private LinearLayout card() {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(this, 12);
        card.setLayoutParams(cardLp);
        card.setClickable(true);
        return card;
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                          */
    /* ---------------------------------------------------------------- */

    private TextView section(String s) {
        TextView t = Widgets.text(this, s, Theme.ACCENT_STRONG, 12, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        Widgets.margins(t, 0, 0, 0, Theme.dp(this, 12));
        return t;
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
        box.setLayoutParams(lp(MATCH, Theme.dp(this, 180)));
        int pad = Theme.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(lp(MATCH, Theme.dp(this, dp)));
        return v;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
