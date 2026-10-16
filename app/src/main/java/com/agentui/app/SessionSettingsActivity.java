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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Per-session settings, reached from the gear in the session header. Hosts the
 * notification opt-in (formerly the bell toggle), the reasoning level, renaming
 * the session via
 * {@code PATCH /sessions/{id}}.
 *
 * <p>The (possibly updated) name is returned to {@link SessionActivity} via
 * {@link #EXTRA_NAME} on the result intent; the notification flag lives in
 * {@link Prefs} and is re-read by the caller on return.
 */
public class SessionSettingsActivity extends Activity {

    static final String EXTRA_DELETED = "deleted";
    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_AUTO_WRITE = "auto_write";
    static final String EXTRA_AUTO_COMMAND = "auto_command";
    static final String EXTRA_AUTO_INTER_AGENT = "auto_inter_agent";
    static final String EXTRA_WORKING_DIR = "working_dir";
    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_WORKTREE_ID = "worktree_id";

    private Api api;
    private CatalogRepository catalogs;
    private String sessionId;
    private String sessionName;
    private String status;
    private boolean autoApproveWrite;
    private boolean autoApproveCommand;
    private boolean autoApproveInterAgent;
    private String workingDir;
    private String projectDir;
    private String worktreeId;

    private EditText nameField;
    private Switch writeSwitch;
    private Switch commandSwitch;
    private Switch interAgentSwitch;
    private Switch sandboxSwitch;
    private LinearLayout sandboxRow;
    private TextView sandboxHint;
    private LinearLayout modelRow;
    private TextView modelLabel;
    private TextView reasoningPicker;
    private TextView reasoningHint;
    private boolean reasoningSaving;
    /** The server's catalog, for the session model's reasoning levels; empty until it lands. */
    private final java.util.List<Agent> agents = new java.util.ArrayList<>();
    private SessionState state;
    private final Runnable stateListener = this::renderState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        catalogs = new CatalogRepository(api);
        sessionId = getIntent().getStringExtra(EXTRA_ID);
        state = SessionState.get(api, sessionId);
        sessionName = getIntent().getStringExtra(EXTRA_NAME);
        status = getIntent().getStringExtra(EXTRA_STATUS);
        if (status == null) status = "idle";
        autoApproveWrite = getIntent().getBooleanExtra(EXTRA_AUTO_WRITE, false);
        autoApproveCommand = getIntent().getBooleanExtra(EXTRA_AUTO_COMMAND, false);
        autoApproveInterAgent = getIntent().getBooleanExtra(EXTRA_AUTO_INTER_AGENT, false);
        workingDir = getIntent().getStringExtra(EXTRA_WORKING_DIR);
        if (workingDir == null) workingDir = "";
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        worktreeId = getIntent().getStringExtra(EXTRA_WORKTREE_ID);
        if (worktreeId == null) worktreeId = "";
        publishResult();
        setContentView(buildRoot());
    }

    @Override protected void onStart() {
        super.onStart();
        state.listen(stateListener);
        state.loaded = false;
        renderState();
        api.listSessions(new Api.Cb<java.util.List<Session>>() {
            @Override public void onResult(java.util.List<Session> sessions) {
                for (Session s : sessions) if (s.id.equals(sessionId)) { renderState(); return; }
                toast("Session not found");
                finish();
            }
            @Override public void onError(String message) { toast(message); renderState(); }
        });
        catalogs.agents(new Api.Cb<java.util.List<Agent>>() {
            @Override public void onResult(java.util.List<Agent> list) {
                agents.clear();
                agents.addAll(list);
                renderState();
            }
            @Override public void onError(String message) { toast(message); }
        });
    }

    @Override protected void onStop() {
        state.unlisten(stateListener);
        super.onStop();
    }

    private void renderState() {
        if (sandboxSwitch == null) return;
        Session s = state.session;
        sandboxRow.setVisibility(View.VISIBLE);
        sandboxHint.setText(s == null ? "Loading session settings…"
                : s.sandbox == null ? "Sandbox unavailable on this server"
                : "Restricts agent file access, not direct shell commands.\nSandbox can only be changed between turns.");
        setSwitchSilently(sandboxSwitch, s != null && Boolean.TRUE.equals(s.sandbox), this::applySandbox);
        sandboxSwitch.setEnabled(state.canSaveSandbox());
        writeSwitch.setEnabled(!state.saving && !state.approvalSaving);
        commandSwitch.setEnabled(!state.saving && !state.approvalSaving);
        interAgentSwitch.setEnabled(!state.saving && !state.approvalSaving);
        String model = s == null ? null : Agent.modelLabel(agents, s.agent, s.model);
        modelLabel.setText(s == null ? "Loading…" : model);
        modelRow.setVisibility(s == null || model != null ? View.VISIBLE : View.GONE);
        renderReasoning(s);
        if (s != null) {
            status = s.status;
            autoApproveWrite = s.autoApproveWrite;
            autoApproveCommand = s.autoApproveCommand;
            autoApproveInterAgent = s.autoApproveInterAgent;
            setSwitchSilently(writeSwitch, autoApproveWrite, c -> applyAutoApprove("write", c));
            setSwitchSilently(commandSwitch, autoApproveCommand, c -> applyAutoApprove("command", c));
            setSwitchSilently(interAgentSwitch, autoApproveInterAgent,
                    c -> applyAutoApprove("inter_agent", c));
            publishResult();
        }
    }

    private void applySandbox(boolean checked) {
        boolean allowed = state.canSaveSandbox();
        renderState(); // Keep the server-confirmed value until success.
        if (!allowed) return;
        state.saving = true;
        state.changed();
        api.setSandbox(sessionId, checked, new Api.StatusCb<Session>() {
            @Override public void onResult(Session session) {
                state.saving = false;
                state.changed();
            }
            @Override public void onError(String message) {
                state.saving = false;
                state.changed();
                toast(message);
            }
            @Override public void onHttpError(int code, String message) {
                onError(message);
                if (code == 404) {
                    state.session = null;
                    state.loaded = false;
                    state.changed();
                    finish();
                }
            }
        });
    }

    /* ---------------------------------------------------------------- */
    /* reasoning                                                        */
    /* ---------------------------------------------------------------- */

    /** The session model's catalog entry, or null until both are known or when unlisted. */
    private Model sessionModel(Session s) {
        if (s == null || s.model == null) return null;
        Agent a = Agent.find(agents, s.agent);
        return a == null ? null : a.model(s.model);
    }

    private void renderReasoning(Session s) {
        reasoningPicker.setText(s == null ? "…" : Model.reasoningLabel(s.reasoningLevel));
        Model m = sessionModel(s);
        boolean choosable = m != null && !m.reasoningLevels.isEmpty();
        reasoningPicker.setEnabled(choosable && !reasoningSaving);
        reasoningPicker.setTextColor(choosable ? Theme.INK : Theme.MUTED);
        reasoningHint.setText(s == null || (m == null && s.model != null && agents.isEmpty())
                ? "Loading session settings…"
                : choosable ? "Applies from the next turn. Default leaves it to the harness, "
                        + "and a level once set cannot go back to it."
                : m != null ? "This session's model offers no reasoning levels."
                : "This session's model is not in the server's catalog.");
    }

    private void chooseReasoning() {
        Session s = state.session;
        Model m = sessionModel(s);
        if (m == null || m.reasoningLevels.isEmpty() || reasoningSaving) return;
        // Only real levels: a set level cannot be cleared back to Default.
        final java.util.List<String> levels = m.reasoningLevels;
        CharSequence[] labels = levels.toArray(new CharSequence[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Reasoning")
                .setSingleChoiceItems(labels, levels.indexOf(s.reasoningLevel), (d, which) -> {
                    d.dismiss();
                    if (!levels.get(which).equals(s.reasoningLevel)) applyReasoning(levels.get(which));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyReasoning(String level) {
        reasoningSaving = true;
        renderState();
        api.setReasoningLevel(sessionId, level, new Api.Cb<Session>() {
            @Override public void onResult(Session session) {
                reasoningSaving = false;
                renderState();
            }
            @Override public void onError(String message) {
                reasoningSaving = false;
                renderState();
                toast("Couldn't update: " + message);
            }
        });
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

        // ---- session / rename ----
        addSection(form, "Session");

        form.addView(label("Name"));
        LinearLayout nameRow = Widgets.row(this);
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
        nameField.setLayoutParams(lp(0, WRAP, 1f));
        nameRow.addView(nameField);
        TextView save = Widgets.primaryButton(this, "Rename");
        save.setLayoutParams(lp(WRAP, Theme.dp(this, 44)));
        Widgets.margins(save, Theme.dp(this, 8), 0, 0, 0);
        save.setOnClickListener(v -> rename(save));
        nameRow.addView(save);
        form.addView(nameRow);

        // A session keeps the model it was created with; older sessions have none.
        form.addView(spacer(12));
        modelLabel = Widgets.text(this, "Loading…", Theme.INK, 14, false);
        modelLabel.setTextIsSelectable(true);
        modelRow = infoRow("Model", modelLabel, null);
        form.addView(modelRow);

        // Read-only ids, for handing to another agent's session tools.
        form.addView(idRow("Session ID", sessionId, "Session"));
        if (!worktreeId.isEmpty() && !"legacy".equals(worktreeId)) {
            form.addView(idRow("Worktree ID", worktreeId, "Worktree"));
        }

        // ---- reasoning ----
        addSection(form, "Reasoning");
        reasoningPicker = Widgets.text(this, "…", Theme.INK, 15, false);
        reasoningPicker.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int rp = Theme.dp(this, 12);
        reasoningPicker.setPadding(rp, 0, rp, 0);
        reasoningPicker.setGravity(Gravity.CENTER_VERTICAL);
        reasoningPicker.setMinimumHeight(Theme.dp(this, 44));
        reasoningPicker.setLayoutParams(lp(MATCH, WRAP));
        reasoningPicker.setOnClickListener(v -> chooseReasoning());
        form.addView(reasoningPicker);
        reasoningHint = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        Widgets.margins(reasoningHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(reasoningHint);

        // ---- sandbox ----
        addSection(form, "Sandbox");
        sandboxSwitch = new Switch(this);
        sandboxRow = toggleRow("Sandbox this session", sandboxSwitch, false, this::applySandbox);
        sandboxRow.setVisibility(View.GONE);
        sandboxSwitch.setEnabled(false);
        form.addView(sandboxRow);
        sandboxHint = Widgets.text(this, "Loading session settings…", Theme.MUTED, 12.5f, false);
        Widgets.margins(sandboxHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(sandboxHint);

        // ---- auto-approve ----
        addSection(form, "Auto-approve");

        writeSwitch = new Switch(this);
        form.addView(toggleRow("Writes", writeSwitch, autoApproveWrite,
                checked -> applyAutoApprove("write", checked)));
        form.addView(spacer(10));
        commandSwitch = new Switch(this);
        form.addView(toggleRow("Commands", commandSwitch, autoApproveCommand,
                checked -> applyAutoApprove("command", checked)));
        form.addView(spacer(10));
        interAgentSwitch = new Switch(this);
        form.addView(toggleRow("Inter-agent communication", interAgentSwitch, autoApproveInterAgent,
                checked -> applyAutoApprove("inter_agent", checked)));

        TextView autoHint = Widgets.text(this,
                "Skip the approval prompt for file writes/edits, shell commands, or this "
                        + "session's own calls to message, start or read other sessions; "
                        + "auto-approved tools are still shown in the transcript. Reads always "
                        + "run. Messaging an unsandboxed session always asks.",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(autoHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(autoHint);

        // ---- notifications (device-local) ----
        addSection(form, "Notifications");

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

        if (Session.isFormerWorktree(worktreeId, workingDir, projectDir)) {
            addSection(form, "Former worktree");
            form.addView(Widgets.text(this,
                    "This session keeps using its former worktree directory:\n\n" + workingDir
                            + "\n\nIf that directory is missing, attempts to run there fail. "
                            + "Its history and settings remain available.",
                    Theme.MUTED, 12.5f, false));
        }

        addSection(form, "Delete session");
        TextView deleteButton = Widgets.dangerButton(this, "Delete session");
        deleteButton.setOnClickListener(v -> confirmDelete(deleteButton));
        form.addView(deleteButton);

        scroll.addView(form);
        root.addView(scroll);

        renderState();
        return root;
    }

    private void confirmDelete(View button) {
        String message = "Delete session \"" + sessionName + "\"? This removes its history.";
        if (!worktreeId.isEmpty()) {
            message += "\n\nThe worktree at " + workingDir + " stays where it is.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete session")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    button.setEnabled(false);
                    api.deleteSession(sessionId, new Api.Cb<Void>() {
                        @Override public void onResult(Void ignored) {
                            WatchService.unwatch(SessionSettingsActivity.this, sessionId);
                            Intent data = new Intent();
                            data.putExtra(EXTRA_DELETED, true);
                            setResult(RESULT_OK, data);
                            finish();
                        }
                        @Override public void onError(String error) {
                            button.setEnabled(true);
                            toast("Unable to delete: " + error);
                        }
                    });
                })
                .show();
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
        if (sessionId == null || state.saving || state.approvalSaving) return;
        state.approvalSaving = true;
        state.changed();
        api.setAutoApprove(sessionId, category, checked, new Api.Cb<Session>() {
            @Override public void onResult(Session session) {
                state.approvalSaving = false;
                state.changed();
                autoApproveWrite = session.autoApproveWrite;
                autoApproveCommand = session.autoApproveCommand;
                autoApproveInterAgent = session.autoApproveInterAgent;
                publishResult();
            }
            @Override public void onError(String message) {
                state.approvalSaving = false;
                state.changed();
                Switch sw = "write".equals(category) ? writeSwitch
                        : "inter_agent".equals(category) ? interAgentSwitch : commandSwitch;
                boolean previous = "write".equals(category) ? autoApproveWrite
                        : "inter_agent".equals(category) ? autoApproveInterAgent : autoApproveCommand;
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

    /** Hand the current name, toggles and location back to the caller. */
    private void publishResult() {
        Intent data = new Intent();
        data.putExtra(EXTRA_NAME, sessionName);
        data.putExtra(EXTRA_AUTO_WRITE, autoApproveWrite);
        data.putExtra(EXTRA_AUTO_COMMAND, autoApproveCommand);
        data.putExtra(EXTRA_AUTO_INTER_AGENT, autoApproveInterAgent);
        data.putExtra(EXTRA_WORKING_DIR, workingDir);
        data.putExtra(EXTRA_WORKTREE_ID, worktreeId);
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

    /**
     * A section header, preceded by a divider unless it opens the form. Leaves
     * the gap before the section's first control.
     */
    private void addSection(LinearLayout form, String title) {
        if (form.getChildCount() > 0) {
            form.addView(spacer(24));
            View div = new View(this);
            div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
            div.setBackgroundColor(Theme.LINE_SOFT);
            form.addView(div);
            form.addView(spacer(20));
        }
        form.addView(section(title));
        form.addView(spacer(12));
    }

    /**
     * {@code Model   Claude Opus   [action]}: a fixed-width label and a value
     * that takes the remaining width and wraps. Rows share a minimum height so
     * those with and without an action line up.
     */
    private LinearLayout infoRow(String label, TextView value, View action) {
        LinearLayout row = Widgets.row(this);
        row.setMinimumHeight(Theme.dp(this, 44));
        row.setLayoutParams(lp(MATCH, WRAP));
        TextView name = Widgets.text(this, label, Theme.MUTED, 13, true);
        name.setLayoutParams(lp(Theme.dp(this, 96), WRAP));
        row.addView(name);
        value.setLayoutParams(lp(0, WRAP, 1f));
        row.addView(value);
        if (action != null) row.addView(action);
        return row;
    }

    /** {@code Session ID   42   Copy}: a label, the bare id, and a copy action. */
    private View idRow(String label, String id, String what) {
        TextView value = Widgets.mono(this, id == null ? "" : id, Theme.INK, 14);
        value.setTextIsSelectable(true);
        TextView copy = Widgets.ghostButton(this, "Copy");
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        copy.setOnClickListener(v -> Widgets.copyId(this, id, what));
        return infoRow(label, value, copy);
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
