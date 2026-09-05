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
        agents.addAll(Agent.FALLBACK);
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

        TextView section = Widgets.text(this, "Server", Theme.ACCENT_STRONG, 12, true);
        section.setAllCaps(true);
        section.setLetterSpacing(0.06f);
        form.addView(section);
        form.addView(spacer(12));

        form.addView(label("Host / IP address"));
        hostField = field(prefs.host(), "192.168.1.50", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI, true);
        form.addView(hostField);

        form.addView(spacer(16));
        form.addView(label("Port"));
        portField = field(String.valueOf(prefs.port()), "8080",
                InputType.TYPE_CLASS_NUMBER, true);
        form.addView(portField);

        form.addView(spacer(16));
        LinearLayout tlsRow = Widgets.row(this);
        TextView tlsLabel = Widgets.text(this, "Use HTTPS / WSS (TLS)", Theme.INK, 15, false);
        tlsLabel.setLayoutParams(lp(0, WRAP, 1f));
        tlsRow.addView(tlsLabel);
        tlsSwitch = new Switch(this);
        tlsSwitch.setChecked(prefs.tls());
        tlsRow.addView(tlsSwitch);
        form.addView(tlsRow);

        form.addView(spacer(20));
        preview = Widgets.mono(this, "", Theme.MUTED, 13);
        preview.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        int pp = Theme.dp(this, 12);
        preview.setPadding(pp, pp, pp, pp);
        preview.setLayoutParams(lp(MATCH, WRAP));
        form.addView(preview);

        form.addView(spacer(28));
        TextView sessionSection = Widgets.text(this, "Sessions", Theme.ACCENT_STRONG, 12, true);
        sessionSection.setAllCaps(true);
        sessionSection.setLetterSpacing(0.06f);
        form.addView(sessionSection);
        form.addView(spacer(12));

        form.addView(label("Default agent"));
        defaultAgentField = selector(defaultAgentLabel());
        defaultAgentField.setOnClickListener(v -> chooseDefaultAgent());
        form.addView(defaultAgentField);
        TextView agentHint = Widgets.text(this,
                "Preselected whenever you start a session. Server default follows "
                        + "the backend's choice.",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(agentHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(agentHint);

        form.addView(spacer(28));
        TextView projectSection = Widgets.text(this, "Projects", Theme.ACCENT_STRONG, 12, true);
        projectSection.setAllCaps(true);
        projectSection.setLetterSpacing(0.06f);
        form.addView(projectSection);
        form.addView(spacer(12));

        form.addView(label("Projects directory"));
        defaultDirField = field(prefs.defaultDir(), "/projects/", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI, true);
        form.addView(defaultDirField);
        TextView dirHint = Widgets.text(this,
                "Pre-filled when you start a new project. Sessions take their "
                        + "directory from the project they are in.",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(dirHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(dirHint);

        form.addView(spacer(28));
        TextView worktreeSection = Widgets.text(this, "Worktrees", Theme.ACCENT_STRONG, 12, true);
        worktreeSection.setAllCaps(true);
        worktreeSection.setLetterSpacing(0.06f);
        form.addView(worktreeSection);
        form.addView(spacer(12));

        form.addView(label("Worktree path template"));
        templateField = field(prefs.worktreeTemplate(), WorktreePath.DEFAULT_TEMPLATE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        form.addView(templateField);
        TextView templateHint = Widgets.text(this,
                "Fills in the directory when you create a worktree; you can still "
                        + "edit it there.\n"
                        + "%P  the project's parent directory\n"
                        + "%N  the project directory's own name\n"
                        + "%B  the branch, slashes turned into dashes\n"
                        + "%b  the branch exactly as typed",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(templateHint, 0, Theme.dp(this, 7), 0, 0);
        form.addView(templateHint);

        form.addView(spacer(12));
        templatePreview = Widgets.mono(this, "", Theme.MUTED, 13);
        templatePreview.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        templatePreview.setPadding(pp, pp, pp, pp);
        templatePreview.setLayoutParams(lp(MATCH, WRAP));
        form.addView(templatePreview);

        form.addView(spacer(24));
        TextView save = Widgets.primaryButton(this, "Save");
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setOnClickListener(v -> save());
        form.addView(save);

        // ---- archived ----
        // Below Save, because it navigates away rather than editing the form
        // the button belongs to.
        form.addView(spacer(28));
        TextView archivedSection = Widgets.text(this, "Archived", Theme.ACCENT_STRONG, 12, true);
        archivedSection.setAllCaps(true);
        archivedSection.setLetterSpacing(0.06f);
        form.addView(archivedSection);
        form.addView(spacer(12));

        LinearLayout archivedRow = Widgets.row(this);
        archivedRow.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        int ap = Theme.dp(this, 14);
        archivedRow.setPadding(ap, ap, ap, ap);
        archivedRow.setLayoutParams(lp(MATCH, WRAP));
        archivedRow.setClickable(true);
        archivedRow.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ArchivedActivity.class)));
        LinearLayout archivedText = Widgets.column(this);
        archivedText.setLayoutParams(lp(0, WRAP, 1f));
        archivedText.addView(Widgets.text(this, "Archived projects and sessions",
                Theme.INK, 15, false));
        // No count here: the archive lives on the server, and fetching both
        // listings to put a number on a menu row would not earn the round trip.
        TextView archivedHint = Widgets.text(this,
                "Work you've filed away. Still readable, and restorable.",
                Theme.MUTED, 12.5f, false);
        Widgets.margins(archivedHint, 0, Theme.dp(this, 3), 0, 0);
        archivedText.addView(archivedHint);
        archivedRow.addView(archivedText);
        archivedRow.addView(Widgets.text(this, "›", Theme.FAINT, 20, false));
        form.addView(archivedRow);

        scroll.addView(form);
        root.addView(scroll);

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
        prefs.save(host, port, tlsSwitch.isChecked(), defaultDirField.getText().toString(),
                defaultAgentId, templateField.getText().toString());
        toast("Saved");
        finish();
    }

    /** Refresh the chooser from the configured server; fallback stays usable offline. */
    private void loadAgents() {
        if (!prefs.isConfigured()) return;
        new Api(this).listAgents(new Api.Cb<List<Agent>>() {
            @Override public void onResult(List<Agent> list) {
                if (list.isEmpty()) return;
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

    /* ---------------------------------------------------------------- */

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
