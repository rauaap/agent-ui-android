package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
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
 * One project's git worktrees. A worktree is its own resource — sessions attach
 * to it, several can share it, and it outlives all of them — so it needs a
 * screen of its own to be created and cleaned up from, rather than appearing
 * and vanishing with a session.
 *
 * <p>Reached from the session list, which is also where a worktree is most
 * often created: the picker in the new-session dialog opens the same form.
 */
public class WorktreeListActivity extends Activity {

    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_PROJECT_NAME = "project_name";

    private Api api;
    private final ForegroundPoller poller = new ForegroundPoller(3000, this::loadWorktrees);
    private final RefreshController refresh = new RefreshController();
    private final RefreshController actions = new RefreshController();
    private CatalogRepository catalogs;
    private String projectDir;
    private String projectName;

    private TextView worktreeCount;
    private LinearLayout listContainer;
    /** Complete, unfiltered listing used to identify detached path dependencies. */
    private List<Session> sessions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        catalogs = new CatalogRepository(api);
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        projectName = getIntent().getStringExtra(EXTRA_PROJECT_NAME);
        if (projectDir == null) {
            finish();
            return;
        }
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        poller.start();
    }

    @Override protected void onPause() {
        poller.stop();
        refresh.cancel();
        actions.cancel();
        super.onPause();
    }

    /* ---------------------------------------------------------------- */
    /* layout                                                           */
    /* ---------------------------------------------------------------- */

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);

        LinearLayout topbar = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        topbar.setPadding(pad, pad, pad, pad);

        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        topbar.addView(back);

        LinearLayout headings = Widgets.column(this);
        headings.setLayoutParams(lp(0, WRAP, 1f));
        TextView title = Widgets.text(this, "Worktrees", Theme.INK, 18, true);
        headings.addView(title);
        worktreeCount = Widgets.text(this, "", Theme.MUTED, 13, false);
        Widgets.margins(worktreeCount, 0, Theme.dp(this, 2), 0, 0);
        headings.addView(worktreeCount);
        TextView project = Widgets.mono(this,
                projectName != null ? projectName : projectDir, Theme.FAINT, 11.5f);
        project.setSingleLine(true);
        project.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Widgets.margins(project, 0, Theme.dp(this, 2), 0, 0);
        headings.addView(project);
        topbar.addView(headings);

        TextView newBtn = Widgets.primaryButton(this, "+ New");
        newBtn.setOnClickListener(v -> createWorktree());
        topbar.addView(newBtn);

        root.addView(topbar);

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

    private void load() {
        existence = catalogs.cachedExistence();
        List<Worktree> cached = catalogs.cachedWorktrees(projectDir);
        if (cached != null) render(cached, null);
        else worktreeCount.setText("…");
        loadWorktrees();
    }

    private DirectorySnapshot existence = DirectorySnapshot.unknown();

    private void loadWorktrees() { refreshWorktrees(false); }

    private void refreshWorktrees(boolean afterRemoval) {
        refresh.start(afterRemoval, run -> {
            Api.Cb<CatalogSnapshot> result = run.finish(new Api.Cb<CatalogSnapshot>() {
                @Override public void onResult(CatalogSnapshot snapshot) {
                    sessions = snapshot.sessions;
                    existence = snapshot.existence;
                    render(snapshot.worktrees, null);
                }
                @Override public void onError(String message) { render(null, message); }
            });
            if (afterRemoval) catalogs.removal(projectDir, run.progress(new CatalogProgress() {}), result);
            else catalogs.worktrees(projectDir, run.progress(new CatalogProgress() {
                @Override public void worktrees(List<Worktree> worktrees) { render(worktrees, null); }
            }), result);
        });
    }

    private void render(List<Worktree> worktrees, String error) {
        listContainer.removeAllViews();
        if (error != null) {
            worktreeCount.setText("unreachable");
            listContainer.addView(emptyBox(error));
            return;
        }
        int n = worktrees.size();
        worktreeCount.setText(n + (n == 1 ? " worktree" : " worktrees"));
        if (n == 0) {
            listContainer.addView(emptyBox("No worktrees yet.\n"
                            + "Tap + New to branch off the project's current HEAD."));
            return;
        }
        for (Worktree w : worktrees) listContainer.addView(card(w));
    }

    private View card(Worktree w) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(this, 12);
        card.setLayoutParams(cardLp);

        LinearLayout head = Widgets.row(this);
        TextView path = Widgets.mono(this, w.path, existence.missing(w.path) ? Theme.DANGER : Theme.INK, 13);
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(path);

        if (existence.missing(w.path)) {
            TextView tag = Widgets.tag(this, "missing", Theme.DANGER);
            Widgets.margins(tag, Theme.dp(this, 8), 0, 0, 0);
            head.addView(tag);
        }

        TextView del = Widgets.text(this, "×", Theme.MUTED, 22, true);
        int dp32 = Theme.dp(this, 32);
        del.setGravity(Gravity.CENTER);
        del.setLayoutParams(lp(dp32, dp32));
        Widgets.margins(del, Theme.dp(this, 8), 0, 0, 0);
        del.setClickable(true);
        del.setOnClickListener(v -> prepareRemove(w));
        head.addView(del);
        card.addView(head);

        // "created on <branch>", not "on <branch>": the branch is where the
        // worktree started, and an agent working in it can have moved since.
        // The id closes the meta line; tapping it copies it for an agent's
        // start_session call.
        String subtitle = w.subtitle();
        LinearLayout metaRow = Widgets.row(this);
        if (!subtitle.isEmpty()) {
            metaRow.addView(Widgets.text(this, subtitle + "  ·  ", Theme.MUTED, 12.5f, false));
        }
        metaRow.addView(Widgets.idLabel(this, w.id, "Worktree", 12.5f));
        Widgets.margins(metaRow, 0, Theme.dp(this, 8), 0, 0);
        card.addView(metaRow);

        if (existence.missing(w.path)) {
            TextView gone = Widgets.text(this,
                    "The directory was removed outside the app. Clean it up to drop "
                            + "the entry — a session started here would fail on its first turn.",
                    Theme.MUTED, 12.5f, false);
            Widgets.margins(gone, 0, Theme.dp(this, 8), 0, 0);
            card.addView(gone);
        }

        return card;
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

    /* ---------------------------------------------------------------- */
    /* create + remove                                                  */
    /* ---------------------------------------------------------------- */

    private void createWorktree() {
        WorktreeForm.show(this, api, projectDir, "", worktree -> load());
    }

    /** Refresh immediately before confirmation so the advisory warning is not stale. */
    private void prepareRemove(Worktree w) {
        actions.start(false, run -> catalogs.removal(projectDir, run.progress(new CatalogProgress() {}), run.finish(new Api.Cb<CatalogSnapshot>() {
            @Override public void onResult(CatalogSnapshot snapshot) {
                sessions = snapshot.sessions;
                existence = snapshot.existence;
                render(snapshot.worktrees, null);
                confirmRemove(w);
            }
            @Override public void onError(String message) {
                toast("Can't verify sessions using this directory: " + message);
            }
        })));
    }

    /**
     * "Clean up" for a worktree whose directory is already gone — git prunes
     * its own admin files and the row goes with it. Session paths stay preserved.
     */
    private void confirmRemove(Worktree w) {
        String dependency = WorktreeRemoval.warning(w, sessions);
        if (existence.missing(w.path)) {
            new AlertDialog.Builder(this)
                    .setTitle("Clean up worktree")
                    .setMessage("The directory is already gone:\n\n" + w.path
                            + dependency + "\n\nRemove the entry?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clean up", (d, x) -> remove(w))
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete worktree")
                .setMessage("Delete the worktree at\n\n" + w.path
                        + "\n\nIts directory is removed from disk. Committed work on "
                        + branchPhrase(w) + " stays in the project's repository." + dependency)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, x) -> remove(w))
                .show();
    }

    private static String branchPhrase(Worktree w) {
        return w.branch.isEmpty() ? "its branch" : "\"" + w.branch + "\"";
    }

    private void removedRefresh() { refreshWorktrees(true); }

    private void remove(Worktree w) {
        actions.start(false, run -> api.deleteWorktree(w.id, run.finish(new Api.StatusCb<Void>() {
            @Override public void onResult(Void ignored) { removedRefresh(); }

            @Override public void onError(String message) {
                removedRefresh(); // Git removal may have succeeded before a database failure.
                toast("Unable to remove: " + message);
            }

            @Override public void onHttpError(int code, String message) {
                if (code == 404) {
                    // Already gone server-side; the list is simply stale.
                    removedRefresh();
                    return;
                }
                if (code == 409) {
                    // Nothing was removed and the row is still there, so the
                    // list is re-rendered from unchanged state rather than
                    // dropping the worktree optimistically.
                    removedRefresh();
                    new AlertDialog.Builder(WorktreeListActivity.this)
                            .setTitle("Left in place")
                            .setMessage(message + "\n\nWait for active agent turns and shell commands "
                                    + "to finish. If Git refused removal, resolve its safety checks, then retry.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                removedRefresh();
                toast("Unable to remove: " + message);
            }
        })));
    }

    /** Opens this screen for a project. */
    static Intent intent(Activity from, String projectDir, String projectName) {
        Intent i = new Intent(from, WorktreeListActivity.class);
        i.putExtra(EXTRA_PROJECT_DIR, projectDir);
        i.putExtra(EXTRA_PROJECT_NAME, projectName);
        return i;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
