package com.agentui.app;

import android.app.Activity;
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
    private TextView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(buildRoot());
        updatePreview();
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

        form.addView(spacer(24));
        TextView save = Widgets.primaryButton(this, "Save");
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setOnClickListener(v -> save());
        form.addView(save);

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
        prefs.save(host, port, tlsSwitch.isChecked());
        toast("Saved");
        finish();
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
