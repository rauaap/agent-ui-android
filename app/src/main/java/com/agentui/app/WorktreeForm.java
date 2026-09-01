package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * The create-worktree dialog, shared by the new-session picker and the worktree
 * list — both of which end up wanting the same thing: a worktree to hand back.
 *
 * <p>Two fields, both pre-filled and both editable. The branch is seeded from
 * whatever the user was naming; the directory is seeded by expanding the path
 * template from Settings and then <em>follows</em> the branch — until the user
 * edits the directory by hand, which breaks that link for good. The two leashes
 * are independent: hand-editing the branch stops it tracking the session name
 * but the directory keeps tracking the branch. Reset re-ties it, since there is
 * otherwise no way back without reopening the dialog.
 */
final class WorktreeForm {
    private WorktreeForm() {}

    /** Handed the worktree to use — freshly created, or the one already there. */
    interface OnPicked {
        void picked(Worktree worktree);
    }

    /**
     * @param branchSeed what the user is naming (a session name, say); the
     *                   branch field starts as a slug of it
     */
    static void show(Activity a, Api api, String projectPath, String branchSeed, OnPicked cb) {
        final String template = api.prefs().worktreeTemplate();
        // The project's worktrees, for flagging a colliding path as the branch
        // is typed rather than after a round trip. Loaded in the background: an
        // empty list only costs the pre-emption, and the 409 still catches it.
        final List<Worktree> existing = new ArrayList<>();

        LinearLayout content = Widgets.column(a);
        int pad = Theme.dp(a, 20);
        content.setPadding(pad, pad, pad, pad);

        content.addView(Widgets.fieldLabel(a, "Branch"));
        EditText branchField = Widgets.field(a, "fix-login", InputType.TYPE_CLASS_TEXT, false);
        branchField.setText(WorktreePath.slug(branchSeed));
        content.addView(branchField);
        // Not "off main": nothing exposes what the project's HEAD is, and
        // naming a branch we cannot see would be a guess.
        TextView branchHint = Widgets.text(a,
                "A new branch, cut from the project's current HEAD.",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(branchHint, 0, Theme.dp(a, 7), 0, 0);
        content.addView(branchHint);

        content.addView(Widgets.spacer(a, 14));

        LinearLayout pathHead = Widgets.row(a);
        TextView pathLabel = Widgets.fieldLabel(a, "Directory");
        pathLabel.setLayoutParams(lp(0, WRAP, 1f));
        pathHead.addView(pathLabel);
        TextView reset = Widgets.text(a, "Reset", Theme.ACCENT_STRONG, 12.5f, true);
        reset.setVisibility(View.INVISIBLE);
        reset.setClickable(true);
        int resetPad = Theme.dp(a, 6);
        reset.setPadding(resetPad, resetPad, resetPad, resetPad);
        pathHead.addView(reset);
        content.addView(pathHead);

        EditText pathField = Widgets.pathField(a, "/projects/app-fix-login");
        content.addView(pathField);

        TextView pathHint = Widgets.text(a, "", Theme.MUTED, 12.5f, false);
        Widgets.margins(pathHint, 0, Theme.dp(a, 7), 0, 0);
        content.addView(pathHint);

        TextView error = Widgets.text(a, "", Theme.DANGER, 12.5f, false);
        Widgets.margins(error, 0, Theme.dp(a, 10), 0, 0);
        error.setVisibility(View.GONE);
        content.addView(error);

        final boolean[] pathEdited = {false};
        final boolean[] programmatic = {false};
        // The worktree already sitting at the typed path, if any. Kept fresh by
        // the watchers so both the hint and the submit path can use it.
        final Worktree[] collision = {null};

        Runnable refreshHint = () -> {
            String path = WorktreePath.resolve(pathField.getText().toString(), projectPath);
            collision[0] = findByPath(existing, path);
            if (collision[0] != null) {
                pathHint.setTextColor(Theme.AWAITING);
                pathHint.setText("A worktree already exists here — you can use that one instead.");
            } else {
                pathHint.setTextColor(Theme.MUTED);
                // The container deployment only mounts /projects, so a
                // directory outside it exists on the host and nowhere the agent
                // can see. A hint, not a rule: the path may be fine elsewhere.
                pathHint.setText("Created on the server. Keeping it beside the project "
                        + "keeps it somewhere the agent can see.");
            }
        };

        Runnable reseedPath = () -> {
            programmatic[0] = true;
            String p = WorktreePath.expand(template, projectPath, branchField.getText().toString());
            pathField.setText(p);
            pathField.setSelection(p.length());
            programmatic[0] = false;
            refreshHint.run();
        };

        pathField.addTextChangedListener(watch(() -> {
            if (!programmatic[0]) {
                pathEdited[0] = true;
                reset.setVisibility(View.VISIBLE);
            }
            refreshHint.run();
        }));
        branchField.addTextChangedListener(watch(() -> {
            if (!pathEdited[0]) reseedPath.run();
        }));
        reset.setOnClickListener(v -> {
            pathEdited[0] = false;
            reset.setVisibility(View.INVISIBLE);
            reseedPath.run();
        });
        reseedPath.run();

        ScrollView scroll = new ScrollView(a);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("New worktree")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null) // overridden below to keep the dialog open
                .create();

        api.listWorktrees(projectPath, new Api.Cb<List<Worktree>>() {
            @Override public void onResult(List<Worktree> worktrees) {
                existing.addAll(worktrees);
                refreshHint.run();
            }
            // Nothing to say: this only pre-empts a 409 the submit still handles.
            @Override public void onError(String message) {}
        });

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String branch = branchField.getText().toString().trim();
                    // A relative path is a 400 server-side, which has no notion
                    // of "relative to the project" — so it is resolved here.
                    String path = WorktreePath.resolve(pathField.getText().toString(), projectPath);
                    if (branch.isEmpty()) {
                        showError(error, "A branch name is required.");
                        return;
                    }
                    if (path.isEmpty() || path.equals("/")) {
                        showError(error, "A directory is required.");
                        return;
                    }
                    if (collision[0] != null) {
                        offerExisting(a, collision[0], dialog, cb);
                        return;
                    }
                    v.setEnabled(false);
                    error.setVisibility(View.GONE);
                    api.createWorktree(projectPath, path, branch, new Api.StatusCb<Worktree>() {
                        @Override public void onResult(Worktree worktree) {
                            dialog.dismiss();
                            cb.picked(worktree);
                        }

                        @Override public void onError(String message) {
                            v.setEnabled(true);
                            showError(error, message);
                        }

                        @Override public void onHttpError(int code, String message) {
                            v.setEnabled(true);
                            // Everything else is git's own complaint about the
                            // branch or the path — more specific than anything
                            // we would write, so it is passed through as-is.
                            if (code != 409) {
                                showError(error, message);
                                return;
                            }
                            // The same branch maps to the same path every time,
                            // so this is routine rather than an edge case. The
                            // body carries no id, so the worktree is looked up.
                            api.listWorktrees(projectPath, new Api.Cb<List<Worktree>>() {
                                @Override public void onResult(List<Worktree> worktrees) {
                                    Worktree there = findByPath(worktrees, path);
                                    if (there != null) offerExisting(a, there, dialog, cb);
                                    else showError(error, message);
                                }
                                @Override public void onError(String m) {
                                    showError(error, message);
                                }
                            });
                        }
                    });
                }));
        dialog.show();
    }

    /**
     * The worktree is already there, which is almost always what the user
     * meant — attaching to it beats a second one beside it.
     */
    private static void offerExisting(Activity a, Worktree worktree,
                                      AlertDialog form, OnPicked cb) {
        String detail = worktree.branch.isEmpty()
                ? "" : "\n\nIt was created on " + worktree.branch + ".";
        new AlertDialog.Builder(a)
                .setTitle("Already there")
                .setMessage("A worktree already exists at\n\n" + worktree.path + detail)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use it", (d, w) -> {
                    form.dismiss();
                    cb.picked(worktree);
                })
                .show();
    }

    /** The worktree at {@code path}, comparing the way the server stores them. */
    private static Worktree findByPath(List<Worktree> worktrees, String path) {
        String wanted = WorktreePath.normalize(path);
        for (Worktree w : worktrees) {
            if (WorktreePath.normalize(w.path).equals(wanted)) return w;
        }
        return null;
    }

    private static void showError(TextView view, String message) {
        view.setText(message);
        view.setVisibility(View.VISIBLE);
    }

    private static TextWatcher watch(Runnable onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                onChange.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
    }
}
