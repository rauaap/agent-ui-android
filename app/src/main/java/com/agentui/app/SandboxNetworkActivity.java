package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Atomic, explicitly saved TCP exception editor shared by server and project settings. */
public class SandboxNetworkActivity extends Activity {
    static final String EXTRA_PROJECT_PATH = "project_path";
    private Api api;
    private String projectPath;
    private List<SandboxNetworkDestination> defaults = new ArrayList<>();
    private LinearLayout inherited;
    private String server;
    private List<SandboxNetworkDestination> draft = new ArrayList<>();
    private String baseline = "[]";
    private boolean loaded;
    private boolean saving;
    private LinearLayout entries;
    private TextView status;
    private TextView add;
    private TextView save;
    private TextView clear;
    private TextView retry;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        server = api.prefs().httpBase();
        projectPath = getIntent().getStringExtra(EXTRA_PROJECT_PATH);
        setContentView(buildRoot());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        if (state != null && state.getBoolean("loaded") && server.equals(state.getString("server"))) {
            try {
                defaults = SandboxNetworkDestination.from(new JSONArray(state.getString("defaults")));
                draft = SandboxNetworkDestination.from(new JSONArray(state.getString("draft")));
                baseline = state.getString("baseline");
                loaded = true;
                render();
                status.setText(isDirty() ? "Unsaved changes" : "");
                return;
            } catch (Exception ignored) { /* Reload unusable saved state. */ }
        }
        load();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("loaded", loaded);
        out.putString("server", server);
        out.putString("defaults", SandboxNetworkDestination.toJson(defaults).toString());
        out.putString("draft", SandboxNetworkDestination.toJson(draft).toString());
        out.putString("baseline", baseline);
    }

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);
        int pad = Theme.dp(this, 16);
        LinearLayout header = Widgets.row(this);
        header.setPadding(pad, pad, pad, pad);
        TextView back = button("‹", this::onBackPressed);
        back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);
        LinearLayout headings = Widgets.column(this);
        headings.setLayoutParams(lp(0, WRAP, 1f));
        headings.addView(Widgets.text(this, "Sandbox network", Theme.INK, 18, true));
        TextView scope = Widgets.text(this, projectPath == null ? "Server defaults" : "Project", Theme.MUTED, 13, false);
        Widgets.margins(scope, 0, Theme.dp(this, 2), 0, 0);
        headings.addView(scope);
        if (projectPath != null) headings.addView(headerMono(projectPath));
        headings.addView(headerMono(server));
        header.addView(headings);
        root.addView(header);
        View div = new View(this);
        div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        div.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(div);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        LinearLayout form = Widgets.column(this);
        form.setPadding(pad, pad, pad, pad);
        form.addView(section("How exceptions apply"));
        form.addView(Widgets.spacer(this, 12));
        form.addView(note("Each exception exposes only one TCP port at an exact unicast IPv4 address. "
                + "Other ports and UDP stay blocked. If Gitea and agent-ui-server share an IP, "
                + "allow only the needed service port; add HTTPS and SSH separately."));
        form.addView(note("No hostnames, CIDRs, IPv6, loopback, unspecified, reserved or multicast addresses, "
                + "or 169.254.0.53 (sandbox DNS proxy). The server validates destinations."));
        form.addView(note(projectPath == null
                ? "Changes apply to new sandboxed turns across all projects and sessions. Empty means no server-level exceptions; project exceptions may still apply."
                : "Changes apply to new sandboxed turns in this project's sessions, including worktrees. Server exceptions are inherited and cannot be removed here. "
                    + "Effective access is the union of server and project IP + port pairs. Empty means server inheritance only."));
        form.addView(note("Running turns retain their rules. These exceptions have no effect when sandboxing is disabled."));
        inherited = Widgets.column(this);
        form.addView(inherited);
        form.addView(Widgets.spacer(this, 28));
        form.addView(section(projectPath == null ? "Server exceptions" : "Project additions"));
        form.addView(Widgets.spacer(this, 12));
        entries = Widgets.column(this);
        form.addView(entries);
        add = button("+ Add IP + port", () -> edit(-1, new SandboxNetworkDestination("", 443)));
        add.setLayoutParams(lp(MATCH, WRAP));
        form.addView(add);
        form.addView(Widgets.spacer(this, 28));
        status = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        Widgets.margins(status, 0, 0, 0, Theme.dp(this, 12));
        form.addView(status);
        retry = button("Retry loading", this::load);
        retry.setLayoutParams(lp(MATCH, WRAP));
        Widgets.margins(retry, 0, 0, 0, Theme.dp(this, 10));
        form.addView(retry);
        save = Widgets.primaryButton(this, "Save exceptions");
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setOnClickListener(v -> save());
        form.addView(save);
        clear = Widgets.dangerButton(this, projectPath == null ? "Clear server exceptions" : "Reset all to server defaults");
        clear.setLayoutParams(lp(MATCH, WRAP));
        Widgets.margins(clear, 0, Theme.dp(this, 10), 0, 0);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(clear.getText())
                .setMessage("Remove all entries in this scope? Inherited server exceptions remain. This takes effect only after Save.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
                    draft.clear(); changed();
                }).show());
        form.addView(clear);
        scroll.addView(form);
        root.addView(scroll);
        return root;
    }

    private void load() {
        loaded = false;
        render();
        retry.setVisibility(View.GONE);
        status.setTextColor(Theme.MUTED);
        status.setText("Loading…");
        api.getSandboxNetwork(new Api.StatusCb<List<SandboxNetworkDestination>>() {
            @Override public void onResult(List<SandboxNetworkDestination> result) {
                if (isDestroyed()) return;
                defaults = result;
                if (projectPath == null) {
                    ready(result);
                    status.setText("");
                } else api.listProjects(new Api.Cb<List<Project>>() {
                    @Override public void onResult(List<Project> projects) {
                        if (isDestroyed()) return;
                        Project project = Archive.find(projects, projectPath);
                        if (project == null) fail("This project is no longer on the server.");
                        else { ready(project.sandboxNetworkAllowlist); status.setText(""); }
                    }
                    @Override public void onError(String message) { fail(message); }
                });
            }
            @Override public void onError(String message) { fail(message); }
            @Override public void onHttpError(int code, String message) {
                fail(code == 404 ? "This server does not support sandbox network settings." : message);
            }
        });
    }

    private void ready(List<SandboxNetworkDestination> result) {
        draft = new ArrayList<>(result);
        baseline = SandboxNetworkDestination.toJson(result).toString();
        loaded = true;
        render();
        status.setTextColor(Theme.MUTED);
    }

    private void changed() {
        render();
        status.setTextColor(Theme.MUTED);
        status.setText(isDirty() ? "Unsaved changes" : "");
    }

    private boolean isDirty() {
        return !baseline.equals(SandboxNetworkDestination.toJson(draft).toString());
    }

    private void render() {
        entries.removeAllViews();
        inherited.removeAllViews();
        if (loaded) {
            if (projectPath != null) {
                inherited.addView(Widgets.spacer(this, 28));
                inherited.addView(section("Inherited server defaults"));
                inherited.addView(Widgets.spacer(this, 12));
                inherited.addView(note("These TCP exceptions cannot be removed at project level."));
                if (defaults.isEmpty()) inherited.addView(note("No server defaults."));
                for (SandboxNetworkDestination entry : defaults) inherited.addView(card(entry, "inherited"));
            }
            if (draft.isEmpty()) entries.addView(note(projectPath == null ? "No server-level exceptions." : "Inheriting all server defaults."));
            for (int i = 0; i < draft.size(); i++) {
                final int index = i;
                SandboxNetworkDestination entry = draft.get(i);

                TextView edit = button("Edit", () -> edit(index, entry));
                TextView remove = button("Remove", () -> { draft.remove(index); changed(); });
                edit.setEnabled(!saving);
                remove.setEnabled(!saving);
                entries.addView(card(entry, projectPath == null ? null : "project entry", edit, remove));
            }
        }
        add.setEnabled(loaded && !saving);
        save.setEnabled(loaded && !saving);
        clear.setEnabled(loaded && !saving && !draft.isEmpty());
        retry.setVisibility(loaded ? View.GONE : View.VISIBLE);
    }

    private void edit(int index, SandboxNetworkDestination initial) {
        LinearLayout form = Widgets.column(this);
        int pad = Theme.dp(this, 20);
        form.setPadding(pad, pad, pad, pad);
        form.addView(Widgets.fieldLabel(this, "Unicast IPv4 address"));
        EditText ip = Widgets.field(this, "100.64.0.10",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        ip.setText(initial.ip);
        form.addView(ip);
        form.addView(Widgets.fieldLabel(this, "TCP port (1–65535)"));
        EditText port = Widgets.field(this, "443", InputType.TYPE_CLASS_NUMBER, true);
        port.setText(String.valueOf(initial.port));
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "Add exception" : "Edit exception")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = ip.getText().toString().trim();
            if (!SandboxNetworkDestination.isIpv4Literal(value)) {
                ip.setError("Enter an exact IPv4 address, not a hostname or CIDR"); return;
            }
            int tcpPort;
            try { tcpPort = SandboxNetworkDestination.parsePort(port.getText().toString().trim()); }
            catch (IllegalArgumentException e) { port.setError(e.getMessage()); return; }
            for (int i = 0; i < draft.size(); i++) {
                SandboxNetworkDestination other = draft.get(i);
                if (i != index && other.ip.equals(value) && other.port == tcpPort) {
                    ip.setError("This IP + port is already in the list"); return;
                }
            }
            SandboxNetworkDestination entry = new SandboxNetworkDestination(value, tcpPort);
            if (index < 0) draft.add(entry); else draft.set(index, entry);
            changed();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void save() {
        if (!server.equals(api.prefs().httpBase())) { fail("Server address changed. Reopen this editor."); return; }
        saving = true;
        render();
        status.setTextColor(Theme.MUTED);
        status.setText("Saving…");
        api.saveSandboxNetwork(projectPath, new ArrayList<>(draft), new Api.Cb<List<SandboxNetworkDestination>>() {
            @Override public void onResult(List<SandboxNetworkDestination> result) {
                if (isDestroyed()) return;
                saving = false;
                ready(result); // Use the normalized response, not the submitted draft.
                status.setText("Saved. Applies to new sandboxed turns only.");
            }
            @Override public void onError(String message) {
                saving = false;
                fail(message); // Keep the draft intact on validation or transport errors.
            }
        });
    }

    private void fail(String message) {
        if (isDestroyed()) return;
        render();
        status.setTextColor(Theme.DANGER);
        status.setText("Couldn't complete request: " + message);
    }

    // Modern gestures use the registered platform callback; older devices use this override.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (saving) return;
        if (loaded && isDirty()) {
            new AlertDialog.Builder(this).setTitle("Discard unsaved exceptions?")
                    .setNegativeButton("Keep editing", null)
                    .setPositiveButton("Discard", (d, w) -> finish()).show();
        } else super.onBackPressed();
    }

    private View card(SandboxNetworkDestination entry, String role, TextView... actions) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        card.setLayoutParams(lp(MATCH, WRAP));
        Widgets.margins(card, 0, 0, 0, Theme.dp(this, 12));
        card.addView(Widgets.mono(this, entry.ip + ":" + entry.port, Theme.INK, 13));
        TextView meta = Widgets.text(this, "TCP" + (role == null ? "" : "  ·  " + role), Theme.MUTED, 12.5f, false);
        Widgets.margins(meta, 0, Theme.dp(this, 6), 0, 0);
        card.addView(meta);
        if (actions.length > 0) {
            LinearLayout row = Widgets.row(this);
            row.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            Widgets.margins(row, 0, Theme.dp(this, 10), 0, 0);
            for (int i = 0; i < actions.length; i++) {
                actions[i].setMinimumHeight(Theme.dp(this, 36));
                if (i > 0) Widgets.margins(actions[i], Theme.dp(this, 8), 0, 0, 0);
                row.addView(actions[i]);
            }
            card.addView(row);
        }
        return card;
    }

    private TextView headerMono(String value) {
        TextView view = Widgets.mono(this, value, Theme.FAINT, 11.5f);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Widgets.margins(view, 0, Theme.dp(this, 2), 0, 0);
        return view;
    }

    private TextView section(String text) {
        TextView view = Widgets.text(this, text, Theme.ACCENT_STRONG, 12, true);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        return view;
    }

    private TextView note(String text) {
        TextView view = Widgets.text(this, text, Theme.MUTED, 12.5f, false);
        Widgets.margins(view, 0, 0, 0, Theme.dp(this, 12));
        return view;
    }

    private TextView button(String text, Runnable action) {
        TextView view = Widgets.ghostButton(this, text);
        view.setOnClickListener(v -> action.run());
        return view;
    }
}
