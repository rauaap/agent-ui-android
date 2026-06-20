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
 * notification opt-in (formerly the bell toggle) and renaming the session via
 * {@code PATCH /sessions/{id}}.
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

    private Api api;
    private String sessionId;
    private String sessionName;
    private String status;
    private boolean autoApproveWrite;
    private boolean autoApproveCommand;

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
            if ("running".equals(status) || "awaiting_approval".equals(status)) {
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

    /** Hand the current name and toggles back so the caller can stay in sync. */
    private void publishResult() {
        Intent data = new Intent();
        data.putExtra(EXTRA_NAME, sessionName);
        data.putExtra(EXTRA_AUTO_WRITE, autoApproveWrite);
        data.putExtra(EXTRA_AUTO_COMMAND, autoApproveCommand);
        setResult(RESULT_OK, data);
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
