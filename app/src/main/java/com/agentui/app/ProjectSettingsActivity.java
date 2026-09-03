package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Per-project settings, reached from the gear on a project's session list —
 * the counterpart of {@link SessionSettingsActivity} one level up.
 *
 * <p>Archiving is all it holds, and it is one call: {@code PATCH /projects}
 * cascades to every session in the project, and unarchiving restores exactly
 * the ones that cascade took. There is no client-side bookkeeping to do, and
 * no per-session loop — the one bulk operation the API has is this one.
 */
public class ProjectSettingsActivity extends Activity {

    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_PROJECT_NAME = "project_name";
    /** On the result: whether the project is archived now. Absent if untouched. */
    static final String EXTRA_ARCHIVED = "archived";

    private Api api;

    private String projectDir;
    private String projectName;

    /** Rebuilt on every load; holds the state line and the button. */
    private LinearLayout archiveBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        projectName = getIntent().getStringExtra(EXTRA_PROJECT_NAME);
        if (projectName == null && projectDir != null) {
            projectName = ProjectListActivity.basename(projectDir);
        }
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

        // ---- header with back ----
        LinearLayout header = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        header.setPadding(pad, pad, pad, pad);
        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);
        header.addView(Widgets.text(this, "Project settings", Theme.INK, 18, true));
        root.addView(header);

        View div = new View(this);
        div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        div.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(div);

        // ---- form ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        LinearLayout form = Widgets.column(this);
        form.setPadding(pad, pad, pad, pad);

        form.addView(section("Project"));
        form.addView(spacer(12));
        form.addView(Widgets.text(this, projectName == null ? "(unnamed)" : projectName,
                Theme.INK, 16, true));
        if (projectDir != null) {
            TextView path = Widgets.mono(this, projectDir, Theme.FAINT, 12);
            Widgets.margins(path, 0, Theme.dp(this, 6), 0, 0);
            form.addView(path);
        }

        form.addView(spacer(28));
        form.addView(section("Archive"));
        form.addView(spacer(12));
        form.addView(Widgets.text(this,
                "Archiving a project files it and all its sessions away: it leaves the "
                        + "project list, takes no new sessions or worktrees, and the old "
                        + "sessions stay readable under Settings ▸ Archived. Nothing is "
                        + "deleted, and its worktrees stay on disk and can still be removed "
                        + "from Worktrees — archiving is not a cleanup.",
                Theme.MUTED, 12.5f, false));
        form.addView(spacer(16));

        archiveBox = Widgets.column(this);
        form.addView(archiveBox);

        scroll.addView(form);
        root.addView(scroll);
        return root;
    }

    /* ---------------------------------------------------------------- */
    /* data                                                             */
    /* ---------------------------------------------------------------- */

    /**
     * The archive state and both session counts come off the project row, and
     * this screen can be sitting open while they change, so it is re-read here
     * rather than carried in on the intent.
     */
    private void load() {
        note("…", Theme.FAINT);
        api.listProjects(new Api.StatusCb<List<Project>>() {
            @Override public void onResult(List<Project> projects) {
                Project found = Archive.find(projects, projectDir);
                if (found == null) note("This project is no longer on the server.", Theme.DANGER);
                else renderActions(found);
            }
            @Override public void onError(String message) {
                note("Can't reach the server: " + message, Theme.DANGER);
            }
            @Override public void onHttpError(int code, String message) {
                // No /projects at all: an older server, with no archive either.
                note(code == 404
                        ? "This server is too old to support archiving."
                        : "Can't reach the server: " + message, Theme.DANGER);
            }
        });
    }

    private void note(String text, int color) {
        archiveBox.removeAllViews();
        archiveBox.addView(Widgets.text(this, text, color, 13, false));
    }

    private void renderActions(Project p) {
        archiveBox.removeAllViews();

        // An archived project reports session_count 0 and last_active_at null by
        // construction, so its own count is the archived one — reading the live
        // count there would announce an empty project.
        int total = p.sessionCount + p.archivedSessionCount;
        String state = p.isArchived()
                ? "Archived " + SessionListActivity.formatTime(p.archivedAt)
                        + "  ·  " + count(p.archivedSessionCount)
                : count(p.sessionCount)
                        + (p.archivedSessionCount > 0
                                ? "  ·  " + p.archivedSessionCount + " archived" : "");
        archiveBox.addView(Widgets.text(this, state, Theme.MUTED, 12.5f, false));
        archiveBox.addView(spacer(14));

        TextView button = Widgets.ghostButton(this,
                p.isArchived() ? "Unarchive project" : "Archive project");
        button.setMinimumHeight(Theme.dp(this, 48));
        button.setLayoutParams(lp(MATCH, WRAP));
        button.setOnClickListener(v -> {
            if (p.isArchived()) apply(false, button);
            else confirmArchive(total, button);
        });
        archiveBox.addView(button);

        if (!p.isArchived() && p.archivedSessionCount > 0) {
            TextView hint = Widgets.text(this,
                    "Its " + p.archivedSessionCount + " archived "
                            + (p.archivedSessionCount == 1 ? "session is" : "sessions are")
                            + " restored one at a time, from Settings ▸ Archived.",
                    Theme.FAINT, 12, false);
            Widgets.margins(hint, 0, Theme.dp(this, 10), 0, 0);
            archiveBox.addView(hint);
        }
    }

    /**
     * The cascade is stated up front with the real number — including the
     * sessions that are already archived, since they go too — rather than left
     * to be discovered afterwards.
     */
    private void confirmArchive(int total, View button) {
        String message = "Archive \"" + projectName + "\""
                + (total == 0 ? "?" : " and its " + count(total) + "?")
                + " It leaves the project list and takes no new sessions or worktrees "
                + "until you unarchive it. Nothing is deleted and nothing leaves disk.";
        new AlertDialog.Builder(this)
                .setTitle("Archive project")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Archive", (d, w) -> apply(true, button))
                .show();
    }

    private void apply(boolean archived, View button) {
        if (projectDir == null) return;
        button.setEnabled(false);
        api.setProjectArchived(projectDir, archived, new Api.StatusCb<Api.ProjectArchive>() {
            @Override public void onResult(Api.ProjectArchive result) {
                Intent data = new Intent();
                data.putExtra(EXTRA_ARCHIVED, archived);
                setResult(RESULT_OK, data);
                // sessions_affected is the honest number: unarchiving restores
                // only what this project's own archive swept up, so a session
                // archived by hand beforehand is not in it.
                toast(archived
                        ? "Archived  ·  " + count(result.sessionsAffected)
                        : "Unarchived  ·  " + count(result.sessionsAffected) + " restored");
                finish();
            }

            @Override public void onError(String message) {
                button.setEnabled(true);
                toast("Couldn't " + (archived ? "archive" : "unarchive") + ": " + message);
            }

            @Override public void onHttpError(int code, String message) {
                button.setEnabled(true);
                // 409: a session is busy and nothing was written — not even for
                // the idle ones. The detail names them, so it is worth a dialog
                // rather than a toast that scrolls the names past.
                if (code == 409) showBusyDialog(message);
                else toast("Couldn't " + (archived ? "archive" : "unarchive") + ": " + message);
            }
        });
    }

    private void showBusyDialog(String detail) {
        new AlertDialog.Builder(this)
                .setTitle("Sessions still busy")
                .setMessage(detail + "\n\nNothing was archived. Let them finish, or stop "
                        + "them, and try again.")
                .setPositiveButton("OK", null)
                .show();
    }

    private static String count(int n) {
        return n + (n == 1 ? " session" : " sessions");
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                          */
    /* ---------------------------------------------------------------- */

    private TextView section(String s) {
        TextView t = Widgets.text(this, s, Theme.ACCENT_STRONG, 12, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(lp(MATCH, Theme.dp(this, dp)));
        return v;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
