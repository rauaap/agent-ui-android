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

    private Api api;
    private String sessionId;
    private String sessionName;
    private String status;

    private EditText nameField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        sessionId = getIntent().getStringExtra(EXTRA_ID);
        sessionName = getIntent().getStringExtra(EXTRA_NAME);
        status = getIntent().getStringExtra(EXTRA_STATUS);
        if (status == null) status = "idle";
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

    /** Hand the current name back so the caller can update its header. */
    private void publishResult() {
        Intent data = new Intent();
        data.putExtra(EXTRA_NAME, sessionName);
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
