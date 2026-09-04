package com.agentui.app;

import android.app.Activity;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Per-session settings, reached from the gear in the session header. Hosts the
 * notification opt-in (formerly the bell toggle), renaming the session via
 * {@code PATCH /sessions/{id}}, and archiving it — the same route, one more
 * optional field.
 *
 * <p>The (possibly updated) name is returned to {@link SessionActivity} via
 * {@link #EXTRA_NAME} on the result intent; the notification flag lives in
 * {@link Prefs} and is re-read by the caller on return.
 */
public class SessionSettingsActivity extends Activity {

    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_AUTO_WRITE = "auto_write";
    static final String EXTRA_AUTO_COMMAND = "auto_command";
    static final String EXTRA_WORKING_DIR = "working_dir";
    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_WORKTREE_ID = "worktree_id";
    /** Whether the session is archived, both incoming and on the result. */
    static final String EXTRA_ARCHIVED = "archived";
    /**
     * Set on the result only when the archiving happened <em>here</em>. The
     * state alone cannot say that: the session's socket broadcasts the same
     * change, so by the time the caller reads the result it may already know.
     */
    static final String EXTRA_JUST_ARCHIVED = "just_archived";

    private Api api;
    private String sessionId;
    private String sessionName;
    private String status;
    private boolean autoApproveWrite;
    private boolean autoApproveCommand;
    private boolean archived;
    private String workingDir;
    private String projectDir;
    private String worktreeId;

    private EditText nameField;
    private Switch writeSwitch;
    private Switch commandSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        sessionId = getIntent().getStringExtra(EXTRA_ID);
        sessionName = getIntent().getStringExtra(EXTRA_NAME);
        status = getIntent().getStringExtra(EXTRA_STATUS);
        if (status == null) status = "idle";
        autoApproveWrite = getIntent().getBooleanExtra(EXTRA_AUTO_WRITE, false);
        autoApproveCommand = getIntent().getBooleanExtra(EXTRA_AUTO_COMMAND, false);
        archived = getIntent().getBooleanExtra(EXTRA_ARCHIVED, false);
        workingDir = getIntent().getStringExtra(EXTRA_WORKING_DIR);
        if (workingDir == null) workingDir = "";
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        worktreeId = getIntent().getStringExtra(EXTRA_WORKTREE_ID);
        if (worktreeId == null) worktreeId = "";
        publishResult();
        setContentView(buildRoot());
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
        header.addView(Widgets.text(this, "Session settings", Theme.INK, 18, true));
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

        // ---- notifications ----
        form.addView(section("Notifications"));
        form.addView(spacer(12));

        LinearLayout notifyRow = Widgets.row(this);
        TextView notifyLabel = Widgets.text(this, "Completion notifications", Theme.INK, 15, false);
        notifyLabel.setLayoutParams(lp(0, WRAP, 1f));
        notifyRow.addView(notifyLabel);
        Switch notifySwitch = new Switch(this);
        notifySwitch.setChecked(api.prefs().notifyEnabled(sessionId));
        notifySwitch.setOnCheckedChangeListener((b, checked) -> applyNotify(checked));
        notifyRow.addView(notifySwitch);
        form.addView(notifyRow);

        TextView notifyHint = Widgets.text(this,
                "Notify when this session finishes a task or needs your approval, "
                        + "even when it isn't on screen.", Theme.MUTED, 12.5f, false);
        Widgets.margins(notifyHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(notifyHint);

        // ---- auto-approve ----
        form.addView(spacer(28));
        form.addView(section("Auto-approve"));
        form.addView(spacer(12));

        writeSwitch = new Switch(this);
        form.addView(toggleRow("Writes", writeSwitch, autoApproveWrite,
                checked -> applyAutoApprove("write", checked)));
        form.addView(spacer(10));
        commandSwitch = new Switch(this);
        form.addView(toggleRow("Commands", commandSwitch, autoApproveCommand,
                checked -> applyAutoApprove("command", checked)));

        TextView autoHint = Widgets.text(this,
                "Skip the approval prompt for file writes/edits or shell commands "
                        + "in this session; auto-approved tools are still shown in the "
                        + "transcript. Reads always run.", Theme.MUTED, 12.5f, false);
        Widgets.margins(autoHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(autoHint);

        // ---- session / rename ----
        form.addView(spacer(28));
        form.addView(section("Session"));
        form.addView(spacer(12));

        form.addView(label("Name"));
        nameField = new EditText(this);
        nameField.setText(sessionName == null ? "" : sessionName);
        nameField.setHint("Session name");
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setTextColor(Theme.INK);
        nameField.setHintTextColor(Theme.FAINT);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameField.setSingleLine(true);
        nameField.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int fp = Theme.dp(this, 12);
        nameField.setPadding(fp, 0, fp, 0);
        nameField.setMinHeight(Theme.dp(this, 44));
        nameField.setGravity(Gravity.CENTER_VERTICAL);
        nameField.setLayoutParams(lp(MATCH, WRAP));
        form.addView(nameField);

        form.addView(spacer(24));
        TextView save = Widgets.primaryButton(this, "Rename");
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setOnClickListener(v -> rename(save));
        form.addView(save);

        // ---- archive ----
        form.addView(spacer(28));
        form.addView(section("Archive"));
        form.addView(spacer(12));

        TextView archiveHint = Widgets.text(this, archived
                        ? "This session is archived: it is off the project's session list "
                          + "and cannot be given new work until you unarchive it. The "
                          + "transcript still opens, and renaming and deleting still work."
                        : "File this session away: it leaves the project's session list and "
                          + "stops taking new prompts, but nothing is deleted and the "
                          + "transcript stays readable under Settings ▸ Archived.",
                Theme.MUTED, 12.5f, false);
        form.addView(archiveHint);

        form.addView(spacer(16));
        TextView archiveBtn = Widgets.ghostButton(this,
                archived ? "Unarchive session" : "Archive session");
        archiveBtn.setMinimumHeight(Theme.dp(this, 48));
        archiveBtn.setLayoutParams(lp(MATCH, WRAP));
        archiveBtn.setOnClickListener(v -> toggleArchived(!archived, archiveBtn));
        form.addView(archiveBtn);

        // A busy session is refused server-side; say so instead of offering a
        // button that can only fail. The reverse is always allowed.
        if (!archived && isBusy()) {
            archiveBtn.setEnabled(false);
            archiveBtn.setAlpha(0.5f);
            TextView busy = Widgets.text(this,
                    "Busy right now — a session can only be archived while it is idle.",
                    Theme.FAINT, 12, false);
            Widgets.margins(busy, 0, Theme.dp(this, 10), 0, 0);
            form.addView(busy);
        }

        // Detachment is deliberately later cleanup, never part of archiving.
        // It releases the database reference but preserves this exact cwd.
        if (archived && !worktreeId.isEmpty()) {
            form.addView(spacer(28));
            form.addView(section("Worktree"));
            form.addView(spacer(12));
            form.addView(Widgets.text(this,
                    "This archived session is attached to the worktree at:\n\n" + workingDir
                            + "\n\nDetaching releases its reference without changing or deleting "
                            + "anything on disk.", Theme.MUTED, 12.5f, false));
            form.addView(spacer(16));
            TextView detach = Widgets.ghostButton(this, "Detach from worktree");
            detach.setMinimumHeight(Theme.dp(this, 48));
            detach.setLayoutParams(lp(MATCH, WRAP));
            detach.setOnClickListener(v -> confirmDetach(detach));
            form.addView(detach);
        } else if (isFormerWorktree()) {
            form.addView(spacer(28));
            form.addView(section("Former worktree"));
            form.addView(spacer(12));
            form.addView(Widgets.text(this,
                    "This session keeps using its former worktree directory:\n\n" + workingDir
                            + "\n\nIf that directory is deleted, the session cannot be "
                            + "unarchived until it is recreated at this exact path.",
                    Theme.MUTED, 12.5f, false));
        }

        scroll.addView(form);
        root.addView(scroll);

        return root;
    }

    /* ---------------------------------------------------------------- */
    /* notifications                                                    */
    /* ---------------------------------------------------------------- */

    private void applyNotify(boolean enabled) {
        if (sessionId == null) return;
        api.prefs().setNotify(sessionId, enabled);
        if (enabled) {
            // If a task is already in flight, start watching it right away.
            if (isBusy()) {
                WatchService.watch(this, sessionId, sessionName, status);
            }
        } else {
            WatchService.unwatch(this, sessionId);
        }
    }

    /* ---------------------------------------------------------------- */
    /* auto-approve                                                     */
    /* ---------------------------------------------------------------- */

    private interface BoolSink { void set(boolean checked); }

    /** A label + switch row that reports flips through {@code sink}. */
    private LinearLayout toggleRow(String label, Switch sw, boolean initial, BoolSink sink) {
        LinearLayout row = Widgets.row(this);
        TextView text = Widgets.text(this, label, Theme.INK, 15, false);
        text.setLayoutParams(lp(0, WRAP, 1f));
        row.addView(text);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, checked) -> sink.set(checked));
        row.addView(sw);
        return row;
    }

    /**
     * Push a single toggle change to the backend. The local fields are the
     * last-known-good truth and are only updated on success; on failure we revert
     * just the switch the user touched, so the UI never claims a setting that
     * didn't stick.
     */
    private void applyAutoApprove(String category, boolean checked) {
        if (sessionId == null) return;
        boolean write = "write".equals(category) ? checked : autoApproveWrite;
        boolean command = "command".equals(category) ? checked : autoApproveCommand;
        api.setAutoApprove(sessionId, write, command, new Api.Cb<Session>() {
            @Override public void onResult(Session session) {
                autoApproveWrite = session.autoApproveWrite;
                autoApproveCommand = session.autoApproveCommand;
                publishResult();
            }
            @Override public void onError(String message) {
                Switch sw = "write".equals(category) ? writeSwitch : commandSwitch;
                boolean previous = "write".equals(category)
                        ? autoApproveWrite : autoApproveCommand;
                setSwitchSilently(sw, previous, c -> applyAutoApprove(category, c));
                toast("Couldn't update: " + message);
            }
        });
    }

    /** Set a switch's checked state without firing its change listener. */
    private void setSwitchSilently(Switch sw, boolean checked, BoolSink sink) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, c) -> sink.set(c));
    }

    /* ---------------------------------------------------------------- */
    /* archive                                                          */
    /* ---------------------------------------------------------------- */

    /**
     * Archiving is reversible and asks for no confirmation. It closes this
     * screen and, through {@link #EXTRA_ARCHIVED}, the session behind it: the
     * session has just left the list it was opened from, so the list is where
     * to land. Unarchiving stays put — the session is back in good standing and
     * throwing the user out of it would be perverse.
     */
    private void toggleArchived(boolean archive, View button) {
        if (sessionId == null) return;
        button.setEnabled(false);
        api.setSessionArchived(sessionId, archive, new Api.StatusCb<Session>() {
            @Override public void onResult(Session session) {
                archived = session.isArchived();
                workingDir = session.workingDir;
                worktreeId = session.worktreeId;
                publishResult(archived);
                if (archived) {
                    toast("Archived — it's under Settings ▸ Archived");
                    finish();
                } else {
                    // Unarchiving also unarchives the project, so the screens
                    // behind this one are stale either way; they reload on resume.
                    toast("Unarchived");
                    setContentView(buildRoot());
                }
            }

            @Override public void onError(String message) {
                button.setEnabled(true);
                toast("Couldn't " + (archive ? "archive" : "unarchive") + ": " + message);
            }

            @Override public void onHttpError(int code, String message) {
                button.setEnabled(true);
                // An archive 409 can be an invisible bash command. An unarchive
                // 409 means the effective cwd is absent or not a directory.
                if (code == 409 && !archive) showMissingWorkingDirectory(message);
                else toast(code == 409 ? message
                        : "Couldn't " + (archive ? "archive" : "unarchive") + ": " + message);
            }
        });
    }

    private void confirmDetach(View button) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Detach from worktree")
                .setMessage("Release this archived session's reference to the worktree? "
                        + "Nothing on disk is changed.\n\nThe session will keep " + workingDir
                        + " as its former worktree directory. If the worktree is later deleted, "
                        + "this session cannot be unarchived until that exact directory is recreated.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Detach", (d, w) -> detach(button))
                .show();
    }

    private void detach(View button) {
        button.setEnabled(false);
        api.detachSessionWorktree(sessionId, new Api.StatusCb<Session>() {
            @Override public void onResult(Session session) {
                workingDir = session.workingDir;
                worktreeId = session.worktreeId;
                archived = session.isArchived();
                publishResult();
                toast("Detached — nothing was deleted");
                setContentView(buildRoot());
            }
            @Override public void onError(String message) {
                button.setEnabled(true);
                toast("Couldn't detach: " + message);
            }
            @Override public void onHttpError(int code, String message) {
                button.setEnabled(true);
                toast(message);
            }
        });
    }

    private boolean isFormerWorktree() {
        return worktreeId.isEmpty() && projectDir != null && !workingDir.equals(projectDir);
    }

    private void showMissingWorkingDirectory(String detail) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Working directory unavailable")
                .setMessage(detail + "\n\nRecreate a directory at the same absolute path, then "
                        + "try unarchiving again:\n\n" + workingDir)
                .setPositiveButton("OK", null)
                .show();
    }

    /* ---------------------------------------------------------------- */
    /* rename                                                           */
    /* ---------------------------------------------------------------- */

    private void rename(View button) {
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            toast("Name is required");
            return;
        }
        if (name.equals(sessionName)) {
            finish();
            return;
        }
        if (sessionId == null) return;
        button.setEnabled(false);
        api.renameSession(sessionId, name, new Api.Cb<Session>() {
            @Override public void onResult(Session session) {
                sessionName = session.name;
                publishResult();
                toast("Renamed");
                finish();
            }
            @Override public void onError(String message) {
                button.setEnabled(true);
                toast("Unable to rename: " + message);
            }
        });
    }

    private void publishResult() {
        publishResult(false);
    }

    /** Hand the current name, toggles and archive state back to the caller. */
    private void publishResult(boolean justArchived) {
        Intent data = new Intent();
        data.putExtra(EXTRA_NAME, sessionName);
        data.putExtra(EXTRA_AUTO_WRITE, autoApproveWrite);
        data.putExtra(EXTRA_AUTO_COMMAND, autoApproveCommand);
        data.putExtra(EXTRA_ARCHIVED, archived);
        data.putExtra(EXTRA_WORKING_DIR, workingDir);
        data.putExtra(EXTRA_WORKTREE_ID, worktreeId);
        data.putExtra(EXTRA_JUST_ARCHIVED, justArchived);
        setResult(RESULT_OK, data);
    }

    /** Busy as far as {@code status} can tell; bash mode is invisible to it. */
    private boolean isBusy() {
        return !"idle".equals(status);
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

    private TextView label(String s) {
        TextView t = Widgets.text(this, s, Theme.MUTED, 13, true);
        Widgets.margins(t, 0, 0, 0, Theme.dp(this, 7));
        return t;
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
