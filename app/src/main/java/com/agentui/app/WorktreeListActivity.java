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
    private String projectDir;
    private String projectName;

    private TextView worktreeCount;
    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
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
        worktreeCount.setText("…");
        api.listWorktrees(projectDir, new Api.Cb<List<Worktree>>() {
            @Override public void onResult(List<Worktree> worktrees) { render(worktrees, null); }
            @Override public void onError(String message) { render(null, message); }
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
        TextView path = Widgets.mono(this, w.path, w.exists ? Theme.INK : Theme.DANGER, 13);
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(path);

        if (!w.exists) {
            TextView tag = Widgets.tag(this, "missing", Theme.DANGER);
            Widgets.margins(tag, Theme.dp(this, 8), 0, 0, 0);
            head.addView(tag);
        }

        TextView del = Widgets.text(this, "🗑", Theme.MUTED, 15, false);
        int dp32 = Theme.dp(this, 32);
        del.setGravity(Gravity.CENTER);
        del.setLayoutParams(lp(dp32, dp32));
        Widgets.margins(del, Theme.dp(this, 8), 0, 0, 0);
        del.setClickable(true);
        del.setOnClickListener(v -> confirmRemove(w));
        head.addView(del);
        card.addView(head);

        // "created on <branch>", not "on <branch>": the branch is where the
        // worktree started, and an agent working in it can have moved since.
        String subtitle = w.subtitle();
        if (!subtitle.isEmpty()) {
            TextView meta = Widgets.text(this, subtitle, Theme.MUTED, 12.5f, false);
            Widgets.margins(meta, 0, Theme.dp(this, 8), 0, 0);
            card.addView(meta);
        }

        if (!w.exists) {
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

    /**
     * "Clean up" for a worktree whose directory is already gone — git prunes
     * its own admin files and the row goes with it, so there is nothing to
     * delete and nothing to lose.
     */
    private void confirmRemove(Worktree w) {
        if (!w.exists) {
            new AlertDialog.Builder(this)
                    .setTitle("Clean up worktree")
                    .setMessage("The directory is already gone:\n\n" + w.path
                            + "\n\nRemove the entry?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clean up", (d, x) -> remove(w))
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete worktree")
                .setMessage("Delete the worktree at\n\n" + w.path
                        + "\n\nIts directory is removed from disk. Committed work on "
                        + branchPhrase(w) + " stays in the project's repository.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, x) -> remove(w))
                .show();
    }

    private static String branchPhrase(Worktree w) {
        return w.branch.isEmpty() ? "its branch" : "\"" + w.branch + "\"";
    }

    private void remove(Worktree w) {
        api.deleteWorktree(w.id, new Api.StatusCb<Void>() {
            @Override public void onResult(Void ignored) { load(); }

            @Override public void onError(String message) {
                toast("Unable to remove: " + message);
            }

            @Override public void onHttpError(int code, String message) {
                if (code == 404) {
                    // Already gone server-side; the list is simply stale.
                    load();
                    return;
                }
                if (code == 409) {
                    // Nothing was removed and the row is still there, so the
                    // list is re-rendered from unchanged state rather than
                    // dropping the worktree optimistically.
                    load();
                    if (message.contains("still using this worktree")) inUseDialog(message);
                    else dirtyDialog(w);
                    return;
                }
                toast("Unable to remove: " + message);
            }
        });
    }

    /**
     * Sessions are still attached. There is no force for this case — the way
     * out is through those sessions, which the server names.
     */
    private void inUseDialog(String detail) {
        new AlertDialog.Builder(this)
                .setTitle("Worktree still in use")
                .setMessage(detail + "\n\nDelete those sessions first, then try again.")
                .setNegativeButton("OK", null)
                .setPositiveButton("Show sessions", (d, x) -> finish())
                .show();
    }

    /**
     * The common outcome for any worktree an agent did real work in: git counts
     * untracked files as dirty. Information, not an error — and deliberately
     * not an offer to force it, which the server does not accept.
     */
    private void dirtyDialog(Worktree w) {
        new AlertDialog.Builder(this)
                .setTitle("Left in place")
                .setMessage(WorktreePath.basename(w.path) + " has uncommitted work, so it "
                        + "was left in place. Commit or discard the changes there, then "
                        + "try again.")
                .setPositiveButton("OK", null)
                .show();
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
