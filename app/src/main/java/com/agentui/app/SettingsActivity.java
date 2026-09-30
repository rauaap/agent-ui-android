package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Server settings. The address is written to SharedPreferences (via {@link Prefs}),
 * so it persists across app restarts and device reboots.
 */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private EditText hostField;
    private EditText tokenField;
    private boolean validating;
    private EditText portField;
    private Switch tlsSwitch;
    private EditText defaultDirField;
    private EditText templateField;
    private TextView preview;
    private TextView templatePreview;
    private TextView defaultAgentField;
    private String defaultAgentId;
    private final List<Agent> agents = new ArrayList<>();

    /**
     * Settings has no project in hand, so the template example is expanded
     * against a stand-in and labelled as one.
     */
    private static final String EXAMPLE_PROJECT = "/projects/app";
    private static final String EXAMPLE_BRANCH = "fix-login";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        defaultAgentId = prefs.defaultAgent();
        setContentView(buildRoot());
        updatePreview();
        updateTemplatePreview();
        loadAgents();
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
        header.addView(Widgets.text(this, "Settings", Theme.INK, 18, true));
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

        // ---- server connection ----
        addSection(form, "Server");

        // Host and port side by side: they are one address.
        LinearLayout addressRow = Widgets.row(this);
        addressRow.setLayoutParams(lp(MATCH, WRAP));
        LinearLayout hostCol = Widgets.column(this);
        hostCol.setLayoutParams(lp(0, WRAP, 1f));
        hostCol.addView(label("Host / IP address"));
        hostField = field(prefs.host(), "192.168.1.50", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI, true);
        hostCol.addView(hostField);
        addressRow.addView(hostCol);
        LinearLayout portCol = Widgets.column(this);
        portCol.setLayoutParams(lp(Theme.dp(this, 96), WRAP));
        Widgets.margins(portCol, Theme.dp(this, 8), 0, 0, 0);
        portCol.addView(label("Port"));
        portField = field(String.valueOf(prefs.port()), "8080",
                InputType.TYPE_CLASS_NUMBER, true);
        portCol.addView(portField);
        addressRow.addView(portCol);
        form.addView(addressRow);

        form.addView(spacer(12));
        LinearLayout tlsRow = Widgets.row(this);
        TextView tlsLabel = Widgets.text(this, "Use HTTPS / WSS (TLS)", Theme.INK, 15, false);
        tlsLabel.setLayoutParams(lp(0, WRAP, 1f));
        tlsRow.addView(tlsLabel);
        tlsSwitch = new Switch(this);
        tlsSwitch.setChecked(prefs.tls());
        tlsRow.addView(tlsSwitch);
        form.addView(tlsRow);

        form.addView(spacer(16));
        form.addView(label("Server token"));
        LinearLayout tokenRow = Widgets.row(this);
        tokenRow.setLayoutParams(lp(MATCH, WRAP));
        tokenField = field(prefs.token(), "Paste server token",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        // field() calls setSingleLine after setInputType, which replaces the password mask.
        tokenField.setTransformationMethod(
                android.text.method.PasswordTransformationMethod.getInstance());
        tokenField.setSaveEnabled(false);
        tokenField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        tokenField.setLayoutParams(lp(0, WRAP, 1f));
        tokenRow.addView(tokenField);
        TextView showToken = Widgets.ghostButton(this, "Show");
        showToken.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        showToken.setLayoutParams(lp(Theme.dp(this, 72), Theme.dp(this, 44)));
        Widgets.margins(showToken, Theme.dp(this, 8), 0, 0, 0);
        showToken.setOnClickListener(v -> {
            boolean hidden = tokenField.getTransformationMethod() != null;
            tokenField.setTransformationMethod(hidden ? null
                    : android.text.method.PasswordTransformationMethod.getInstance());
            tokenField.setSelection(tokenField.length());
            showToken.setText(hidden ? "Hide" : "Show");
        });
        tokenRow.addView(showToken);
        form.addView(tokenRow);
        form.addView(hint(Auth.rejected()
                ? "The server rejected the token. It may have been changed on the server."
                : "The shared token from your server.", Auth.rejected() ? Theme.DANGER : Theme.MUTED));

        form.addView(spacer(16));
        preview = Widgets.mono(this, "", Theme.MUTED, 13);
        preview.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        int pp = Theme.dp(this, 12);
        preview.setPadding(pp, pp, pp, pp);
        preview.setLayoutParams(lp(MATCH, WRAP));
        form.addView(preview);

        // ---- defaults for new work ----
        addSection(form, "Defaults");

        form.addView(label("Agent"));
        defaultAgentField = selector(defaultAgentLabel());
        defaultAgentField.setOnClickListener(v -> chooseDefaultAgent());
        form.addView(defaultAgentField);
        form.addView(hint("Preselected whenever you start a session. Server default follows "
                + "the backend's choice.", Theme.MUTED));

        form.addView(spacer(20));
        form.addView(label("Projects directory"));
        defaultDirField = field(prefs.defaultDir(), "/projects/", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI, true);
        form.addView(defaultDirField);
        form.addView(hint("Pre-filled when you start a new project. Sessions take their "
                + "directory from the project they are in.", Theme.MUTED));

        form.addView(spacer(20));
        form.addView(label("Worktree path template"));
        templateField = field(prefs.worktreeTemplate(), WorktreePath.DEFAULT_TEMPLATE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        form.addView(templateField);
        form.addView(hint("Fills in the directory when you create a worktree; you can still "
                + "edit it there.", Theme.MUTED));
        TextView legend = Widgets.mono(this,
                "%P  the project's parent directory\n"
                        + "%N  the project directory's own name\n"
                        + "%B  the branch, slashes turned into dashes\n"
                        + "%b  the branch exactly as typed",
                Theme.MUTED, 12);
        Widgets.margins(legend, 0, Theme.dp(this, 7), 0, 0);
        form.addView(legend);

        form.addView(spacer(12));
        templatePreview = Widgets.mono(this, "", Theme.MUTED, 13);
        templatePreview.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        templatePreview.setPadding(pp, pp, pp, pp);
        templatePreview.setLayoutParams(lp(MATCH, WRAP));
        form.addView(templatePreview);

        // ---- other screens ----
        // These navigate away and save on their own, so they sit apart from
        // the fields the Save footer applies to.
        addSection(form, "More");

        form.addView(navRow("Server sandbox paths",
                "Shared defaults on the saved server, saved separately from this device.",
                v -> {
                    // The remote editor always uses the saved connection, never a partially edited address.
                    if (!prefs.isConfigured()
                            || !hostField.getText().toString().trim().equals(prefs.host())
                            || !portField.getText().toString().trim().equals(String.valueOf(prefs.port()))
                            || tlsSwitch.isChecked() != prefs.tls()) {
                        toast("Save the server address first, then reopen Settings.");
                        return;
                    }
                    startActivity(new android.content.Intent(this, SandboxPathsActivity.class));
                }));
        form.addView(spacer(10));
        // No count here: the archive lives on the server, and fetching both
        // listings to put a number on a menu row would not earn the round trip.
        form.addView(navRow("Archived projects and sessions",
                "Work you've filed away. Still readable, and restorable.",
                v -> startActivity(new android.content.Intent(this, ArchivedActivity.class))));

        scroll.addView(form);
        root.addView(scroll);

        // ---- save footer, always in reach ----
        View footerDiv = new View(this);
        footerDiv.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        footerDiv.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(footerDiv);
        LinearLayout footer = Widgets.column(this);
        footer.setPadding(pad, Theme.dp(this, 12), pad, Theme.dp(this, 12));
        TextView save = Widgets.primaryButton(this, "Save");
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setOnClickListener(v -> save());
        footer.addView(save);
        root.addView(footer);

        // live preview updates
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { updatePreview(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        hostField.addTextChangedListener(watcher);
        portField.addTextChangedListener(watcher);
        tlsSwitch.setOnCheckedChangeListener((CompoundButton b, boolean c) -> updatePreview());
        templateField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateTemplatePreview();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        return root;
    }

    private void updatePreview() {
        String host = hostField.getText().toString().trim();
        String port = portField.getText().toString().trim();
        boolean tls = tlsSwitch.isChecked();
        if (host.isEmpty()) {
            preview.setText("Enter the server address used to reach the agent backend.");
            return;
        }
        String scheme = tls ? "https" : "http";
        String wsScheme = tls ? "wss" : "ws";
        String authority = host + (port.isEmpty() ? "" : ":" + port);
        preview.setText(scheme + "://" + authority + "/sessions\n"
                + wsScheme + "://" + authority + "/ws/sessions/{id}");
    }

    private void updateTemplatePreview() {
        String template = templateField.getText().toString();
        templatePreview.setText("Example — project " + EXAMPLE_PROJECT
                + ", branch " + EXAMPLE_BRANCH + ":\n"
                + WorktreePath.expand(template, EXAMPLE_PROJECT, EXAMPLE_BRANCH));
    }

    private void save() {
        String host = hostField.getText().toString().trim();
        String portStr = portField.getText().toString().trim();
        if (host.isEmpty()) {
            toast("Host is required");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            toast("Enter a valid port (1–65535)");
            return;
        }
        if (validating) return;
        String token = tokenField.getText().toString().trim();
        if (token.isEmpty()) { toast("Server token is required"); return; }
        boolean tls = tlsSwitch.isChecked();
        String base = (tls ? "https://" : "http://") + host + ":" + port;
        String directory = defaultDirField.getText().toString();
        String agent = defaultAgentId;
        String template = templateField.getText().toString();
        Runnable persist = () -> {
            prefs.save(host, port, tls, directory, agent, template);
            prefs.saveToken(token);
            Auth.saved();
            toast("Saved");
            finish();
        };
        validating = true;
        toast("Checking token…");
        new Thread(() -> {
            int result;
            try { result = Auth.check(base, token); }
            catch (java.io.IOException e) { result = -1; }
            catch (IllegalArgumentException e) { result = -2; }
            final int code = result;
            runOnUiThread(() -> {
                validating = false;
                if (isFinishing() || isDestroyed()) return;
                if (code == 200) persist.run();
                else if (code == 401) toast("Token rejected");
                else if (code == -1) new AlertDialog.Builder(this)
                        .setTitle("Unable to reach server")
                        .setMessage("Check the server address and network connection.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save anyway", (dialog, which) -> persist.run()).show();
                else toast(code == -2 ? "Invalid server address or token format"
                        : "Server error " + code);
            });
        }, "token-validation").start();
    }

    /** Fill the chooser from the configured server; "Server default" needs no answer. */
    private void loadAgents() {
        if (!prefs.isConfigured()) return;
        new Api(this).listAgents(new Api.Cb<List<Agent>>() {
            @Override public void onResult(List<Agent> list) {
                agents.clear();
                agents.addAll(list);
                defaultAgentField.setText(defaultAgentLabel());
            }

            @Override public void onError(String message) {}
        });
    }

    private void chooseDefaultAgent() {
        // Keep indices stable if the server's answer lands while this is open.
        final List<Agent> choices = new ArrayList<>(agents);
        CharSequence[] labels = new CharSequence[choices.size() + 1];
        labels[0] = "Server default";
        int checked = 0;
        for (int i = 0; i < choices.size(); i++) {
            labels[i + 1] = choices.get(i).name;
            if (choices.get(i).id.equals(defaultAgentId)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("Default agent")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    defaultAgentId = which == 0 ? "" : choices.get(which - 1).id;
                    defaultAgentField.setText(defaultAgentLabel());
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String defaultAgentLabel() {
        return defaultAgentId == null || defaultAgentId.isEmpty()
                ? "Server default" : Agent.label(agents, defaultAgentId);
    }

    /** A field-shaped tap target that opens a chooser. */
    private TextView selector(String value) {
        TextView t = Widgets.text(this, value, Theme.INK, 15, false);
        t.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        t.setPadding(p, 0, p, 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinimumHeight(Theme.dp(this, 44));
        t.setLayoutParams(lp(MATCH, WRAP));
        t.setClickable(true);
        return t;
    }

    /**
     * A tappable card that opens another screen: title, one-line hint, chevron.
     */
    private View navRow(String title, String hint, View.OnClickListener onClick) {
        LinearLayout row = Widgets.row(this);
        row.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        int p = Theme.dp(this, 14);
        row.setPadding(p, p, p, p);
        row.setLayoutParams(lp(MATCH, WRAP));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        LinearLayout text = Widgets.column(this);
        text.setLayoutParams(lp(0, WRAP, 1f));
        text.addView(Widgets.text(this, title, Theme.INK, 15, false));
        TextView h = Widgets.text(this, hint, Theme.MUTED, 12.5f, false);
        Widgets.margins(h, 0, Theme.dp(this, 3), 0, 0);
        text.addView(h);
        row.addView(text);
        row.addView(Widgets.text(this, "›", Theme.FAINT, 20, false));
        return row;
    }

    /* ---------------------------------------------------------------- */

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
        TextView t = Widgets.text(this, title, Theme.ACCENT_STRONG, 12, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        form.addView(t);
        form.addView(spacer(12));
    }

    /** Explanatory text under a control. */
    private TextView hint(String s, int color) {
        TextView t = Widgets.text(this, s, color, 12.5f, false);
        Widgets.margins(t, 0, Theme.dp(this, 7), 0, 0);
        return t;
    }

    private TextView label(String s) {
        TextView t = Widgets.text(this, s, Theme.MUTED, 13, true);
        Widgets.margins(t, 0, 0, 0, Theme.dp(this, 7));
        return t;
    }

    private EditText field(String value, String hint, int inputType, boolean mono) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextColor(Theme.INK);
        e.setHintTextColor(Theme.FAINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        e.setPadding(p, 0, p, 0);
        e.setMinHeight(Theme.dp(this, 44));
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setLayoutParams(lp(MATCH, WRAP));
        return e;
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
