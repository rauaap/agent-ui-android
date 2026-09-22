package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Atomic, explicitly saved editor shared by the server and project scopes. */
public class SandboxPathsActivity extends Activity {
    static final String EXTRA_PROJECT_PATH = "project_path";
    private Api api;
    private String projectPath;
    private String server;
    private List<SandboxPath> defaults = new ArrayList<>();
    private List<SandboxPath> draft = new ArrayList<>();
    private String baseline = "[]";
    private boolean loaded;
    private boolean saving;
    private LinearLayout entries;
    private LinearLayout inherited;
    private TextView status;
    private TextView save;
    private TextView add;
    private TextView clear;
    private TextView retry;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        server = api.prefs().httpBase();
        projectPath = getIntent().getStringExtra(EXTRA_PROJECT_PATH);
        setContentView(buildRoot());
        if (state != null && state.getBoolean("loaded") && server.equals(state.getString("server"))) {
            try {
                defaults = SandboxPath.from(new JSONArray(state.getString("defaults")));
                draft = SandboxPath.from(new JSONArray(state.getString("draft")));
                baseline = state.getString("baseline");
                loaded = true;
                render();
                return;
            } catch (Exception ignored) { /* Reload an unusable saved state. */ }
        }
        load();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("loaded", loaded);
        out.putString("server", server);
        out.putString("defaults", SandboxPath.toJson(defaults).toString());
        out.putString("draft", SandboxPath.toJson(draft).toString());
        out.putString("baseline", baseline);
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
        back.setOnClickListener(v -> onBackPressed());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);

        LinearLayout headings = Widgets.column(this);
        headings.setLayoutParams(lp(0, WRAP, 1f));
        headings.addView(Widgets.text(this, "Sandbox paths", Theme.INK, 18, true));
        TextView scope = Widgets.text(this, projectPath == null ? "Server defaults" : "Project",
                Theme.MUTED, 13, false);
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

        // ---- form ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        LinearLayout form = Widgets.column(this);
        form.setPadding(pad, pad, pad, pad);

        form.addView(section("How paths apply"));
        form.addView(Widgets.spacer(this, 12));
        form.addView(note("Paths refer to files or directories on the server, not this device. "
                + "Enter an absolute path, ~, ~user, $VAR or ${VAR}; expansion uses the server's "
                + "home and environment. No shell expressions or wildcards. Directory contents are exposed.", 0));
        form.addView(note(projectPath == null
                ? "Changes apply to new turns across all sandboxed sessions (Pi and Claude)."
                : "Changes apply to new turns in this project's sandboxed sessions and worktrees. "
                    + "Project paths override matching server defaults. Clearing this list restores "
                    + "inheritance; inherited paths cannot be excluded.", 8));
        form.addView(note("Saving does not restart agents or change/revoke access from running turns. "
                + "These paths have no effect when sandboxing is disabled. Direct shell commands remain outside the sandbox.", 8));
        form.addView(note("Even read-only paths can expose credentials. Allowing writes lets agents "
                + "change or delete host data. Add needed data/cache paths explicitly; environment "
                + "variables are not forwarded. Conflicts with other scopes or built-in mounts may fail the next turn.", 8));

        inherited = Widgets.column(this);
        form.addView(inherited);

        form.addView(Widgets.spacer(this, 28));
        form.addView(section(projectPath == null ? "Server paths" : "Project additions / overrides"));
        form.addView(Widgets.spacer(this, 12));
        entries = Widgets.column(this);
        form.addView(entries);
        add = button("+ Add path", () -> edit(-1, new SandboxPath("", false)));
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
        save = Widgets.primaryButton(this, "Save paths");
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setOnClickListener(v -> save());
        form.addView(save);
        clear = Widgets.dangerButton(this, projectPath == null ? "Clear server paths" : "Reset all to server defaults");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(clear.getText())
                .setMessage("Remove all entries in this scope? This takes effect only after Save.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
                    draft.clear(); changed();
                }).show());
        clear.setLayoutParams(lp(MATCH, WRAP));
        Widgets.margins(clear, 0, Theme.dp(this, 10), 0, 0);
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
        api.getSandboxPaths(new Api.StatusCb<List<SandboxPath>>() {
            @Override public void onResult(List<SandboxPath> paths) {
                if (isDestroyed()) return;
                defaults = paths;
                if (projectPath == null) ready(paths);
                else api.listProjects(new Api.Cb<List<Project>>() {
                    @Override public void onResult(List<Project> projects) {
                        if (isDestroyed()) return;
                        Project project = Archive.find(projects, projectPath);
                        if (project == null) fail("This project is no longer on the server.");
                        else if (!project.hasSandboxPaths) fail("This server does not support project sandbox paths.");
                        else ready(project.sandboxPaths);
                    }
                    @Override public void onError(String message) { fail(message); }
                });
            }
            @Override public void onError(String message) { fail(message); }
            @Override public void onHttpError(int code, String message) {
                fail(code == 404 ? "This server does not support sandbox path settings." : message);
            }
        });
    }

    private void ready(List<SandboxPath> paths) {
        draft = new ArrayList<>(paths);
        baseline = SandboxPath.toJson(paths).toString();
        loaded = true;
        render();
        status.setTextColor(Theme.MUTED);
        status.setText("");
    }

    private void changed() {
        render();
        status.setTextColor(Theme.MUTED);
        status.setText("Unsaved changes");
    }

    private void render() {
        entries.removeAllViews();
        inherited.removeAllViews();
        if (loaded) {
            if (projectPath != null) {
                inherited.addView(Widgets.spacer(this, 28));
                inherited.addView(section("Inherited server defaults"));
                inherited.addView(Widgets.spacer(this, 12));
                TextView hint = Widgets.text(this, "Overrides below are identified by exact text. Equivalent spellings "
                        + "(for example ~/config and $HOME/config) are matched only by the server; "
                        + "this is not an effective-access preview. Remove a project override to restore its default.",
                        Theme.FAINT, 12, false);
                Widgets.margins(hint, 0, 0, 0, Theme.dp(this, 12));
                inherited.addView(hint);
                if (defaults.isEmpty()) inherited.addView(empty("No server defaults."));
                for (SandboxPath path : defaults) {
                    int index = SandboxPath.indexOf(draft, path.path);
                    TextView override = button(index >= 0 ? "Reset override" : "Override", () -> {
                        if (index >= 0) { draft.remove(index); changed(); }
                        else edit(-1, path);
                    });
                    override.setEnabled(!saving);
                    inherited.addView(card(path, index >= 0 ? "overridden below" : "inherited", override));
                }
            }
            if (draft.isEmpty()) entries.addView(empty(projectPath == null ? "No additional paths." : "Inheriting all server defaults."));
            for (int i = 0; i < draft.size(); i++) {
                final int index = i;
                SandboxPath path = draft.get(i);
                boolean override = projectPath != null && SandboxPath.indexOf(defaults, path.path) >= 0;
                TextView edit = button("Edit", () -> edit(index, path));
                TextView remove = button(override ? "Reset to default" : "Remove", () -> { draft.remove(index); changed(); });
                edit.setEnabled(!saving);
                remove.setEnabled(!saving);
                entries.addView(card(path, projectPath == null ? null : override ? "project override" : "project entry",
                        edit, remove));
            }
        }
        add.setEnabled(loaded && !saving);
        clear.setEnabled(loaded && !saving && !draft.isEmpty());
        save.setEnabled(loaded && !saving);
        retry.setVisibility(loaded ? View.GONE : View.VISIBLE);
    }

    private void edit(int index, SandboxPath initial) {
        LinearLayout form = Widgets.column(this);
        int p = Theme.dp(this, 20);
        form.setPadding(p, p, p, p);
        form.addView(Widgets.fieldLabel(this, "Server path"));
        EditText path = Widgets.field(this, "~/.config/tool",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        path.setText(initial.path);
        form.addView(path);
        LinearLayout writesRow = Widgets.row(this);
        Widgets.margins(writesRow, 0, Theme.dp(this, 14), 0, 0);
        TextView writesLabel = Widgets.text(this, "Allow writes", Theme.INK, 15, false);
        writesLabel.setLayoutParams(lp(0, WRAP, 1f));
        writesRow.addView(writesLabel);
        Switch writes = new Switch(this);
        writes.setChecked(initial.write);
        writesRow.addView(writes);
        form.addView(writesRow);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "Add path" : "Edit path")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = path.getText().toString();
            if (value.isEmpty()) { path.setError("Enter a server path"); return; }
            int duplicate = SandboxPath.indexOf(draft, value);
            if (duplicate >= 0 && duplicate != index) { path.setError("This path is already in this list"); return; }
            SandboxPath entry = new SandboxPath(value, writes.isChecked());
            if (index < 0) draft.add(entry); else draft.set(index, entry);
            changed();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void save() {
        // Never apply a draft fetched from one server to a newly configured server.
        if (!server.equals(api.prefs().httpBase())) { fail("Server address changed. Reopen this editor."); return; }
        saving = true;
        render();
        status.setTextColor(Theme.MUTED);
        status.setText("Saving…");
        api.saveSandboxPaths(projectPath, new ArrayList<>(draft), new Api.Cb<List<SandboxPath>>() {
            @Override public void onResult(List<SandboxPath> paths) {
                if (isDestroyed()) return;
                saving = false;
                ready(paths);
                status.setText("Saved. Applies to new turns only.");
            }
            @Override public void onError(String message) {
                saving = false;
                fail(message); // Keep the draft intact, including on 400/422.
            }
        });
    }

    private void fail(String message) {
        if (isDestroyed()) return;
        render();
        status.setText("Couldn't complete request: " + message);
        status.setTextColor(Theme.DANGER);
    }

    @Override public void onBackPressed() {
        if (saving) return;
        if (loaded && !baseline.equals(SandboxPath.toJson(draft).toString())) {
            new AlertDialog.Builder(this).setTitle("Discard unsaved paths?")
                    .setNegativeButton("Keep editing", null)
                    .setPositiveButton("Discard", (d, w) -> finish()).show();
        } else super.onBackPressed();
    }

    /** One path: monospace path, its mode and role, then its actions right-aligned. */
    private View card(SandboxPath path, String role, TextView... actions) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(this, 12);
        card.setLayoutParams(cardLp);

        card.addView(Widgets.mono(this, path.path, Theme.INK, 13));
        String meta = path.write ? "read/write" : "read-only";
        if (role != null) meta += "  ·  " + role;
        TextView metaView = Widgets.text(this, meta, path.write ? Theme.AWAITING : Theme.MUTED, 12.5f, false);
        Widgets.margins(metaView, 0, Theme.dp(this, 6), 0, 0);
        card.addView(metaView);

        LinearLayout row = Widgets.row(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Widgets.margins(row, 0, Theme.dp(this, 10), 0, 0);
        for (int i = 0; i < actions.length; i++) {
            actions[i].setMinimumHeight(Theme.dp(this, 36));
            if (i > 0) Widgets.margins(actions[i], Theme.dp(this, 8), 0, 0, 0);
            row.addView(actions[i]);
        }
        card.addView(row);
        return card;
    }

    private TextView empty(String message) {
        TextView view = Widgets.text(this, message, Theme.FAINT, 13, false);
        Widgets.margins(view, 0, 0, 0, Theme.dp(this, 12));
        return view;
    }

    private TextView note(String value, int topDp) {
        TextView view = Widgets.text(this, value, Theme.MUTED, 12.5f, false);
        Widgets.margins(view, 0, Theme.dp(this, topDp), 0, 0);
        return view;
    }

    private TextView headerMono(String value) {
        TextView view = Widgets.mono(this, value, Theme.FAINT, 11.5f);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        Widgets.margins(view, 0, Theme.dp(this, 2), 0, 0);
        return view;
    }

    private TextView section(String s) {
        TextView t = Widgets.text(this, s, Theme.ACCENT_STRONG, 12, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private TextView button(String title, Runnable action) {
        TextView view = Widgets.ghostButton(this, title);
        view.setOnClickListener(v -> action.run());
        return view;
    }
}
