package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
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
        Widgets.fitSystemWindows(root);
        int pad = Theme.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        TextView back = Widgets.ghostButton(this, "‹  Sandbox paths");
        back.setOnClickListener(v -> onBackPressed());
        root.addView(back);
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1));
        LinearLayout form = Widgets.column(this);
        form.addView(text(projectPath == null ? "Server defaults" : "Project: " + projectPath, true));
        form.addView(text("Server: " + server, false));
        form.addView(text("Paths refer to files or directories on the server, not this device. "
                + "Enter an absolute path, ~, ~user, $VAR or ${VAR}; expansion uses the server's "
                + "home and environment. No shell expressions or wildcards. Directory contents are exposed.", false));
        form.addView(text(projectPath == null
                ? "Changes apply to new turns across all sandboxed sessions (Pi and Claude)."
                : "Changes apply to new turns in this project's sandboxed sessions and worktrees. "
                    + "Project paths override matching server defaults. Clearing this list restores "
                    + "inheritance; inherited paths cannot be excluded.", false));
        form.addView(text("Saving does not restart agents or change/revoke access from running turns. "
                + "These paths have no effect when sandboxing is disabled. Direct shell commands remain outside the sandbox.", false));
        form.addView(text("Even read-only paths can expose credentials. Allowing writes lets agents "
                + "change or delete host data. Add needed data/cache paths explicitly; environment "
                + "variables are not forwarded. Conflicts with other scopes or built-in mounts may fail the next turn.", false));
        inherited = Widgets.column(this);
        form.addView(inherited);
        form.addView(text(projectPath == null ? "Server paths" : "Project additions / overrides", true));
        entries = Widgets.column(this);
        form.addView(entries);
        add = button("Add path", () -> edit(-1, new SandboxPath("", false)));
        form.addView(add);
        clear = button(projectPath == null ? "Clear server paths" : "Reset all to server defaults", () -> {
            new AlertDialog.Builder(this).setTitle(clear.getText())
                    .setMessage("Remove all entries in this scope? This takes effect only after Save.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
                        draft.clear(); changed();
                    }).show();
        });
        form.addView(clear);
        status = text("", false);
        form.addView(status);
        retry = button("Retry loading", this::load);
        form.addView(retry);
        save = Widgets.primaryButton(this, "Save paths");
        save.setOnClickListener(v -> save());
        form.addView(save);
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
                inherited.addView(text("Inherited server defaults", true));
                inherited.addView(text("Overrides below are identified by exact text. Equivalent spellings "
                        + "(for example ~/config and $HOME/config) are matched only by the server; "
                        + "this is not an effective-access preview. Remove a project override to restore its default.", false));
                if (defaults.isEmpty()) inherited.addView(text("No server defaults.", false));
                for (SandboxPath path : defaults) {
                    int index = SandboxPath.indexOf(draft, path.path);
                    inherited.addView(text(path.path + mode(path) + (index >= 0 ? " · overridden below" : " · inherited"), false));
                    TextView override = button(index >= 0 ? "Reset override to default" : "Override for project", () -> {
                        if (index >= 0) { draft.remove(index); changed(); }
                        else edit(-1, path);
                    });
                    override.setEnabled(!saving);
                    inherited.addView(override);
                }
            }
            if (draft.isEmpty()) entries.addView(text(projectPath == null ? "No additional paths." : "Inheriting all server defaults.", false));
            for (int i = 0; i < draft.size(); i++) {
                final int index = i;
                SandboxPath path = draft.get(i);
                boolean override = projectPath != null && SandboxPath.indexOf(defaults, path.path) >= 0;
                entries.addView(text(path.path + mode(path) + (projectPath == null ? "" : override ? " · project override" : " · project entry"), true));
                LinearLayout actions = Widgets.row(this);
                TextView edit = button("Edit", () -> edit(index, path));
                TextView remove = button(override ? "Reset to default" : "Remove", () -> { draft.remove(index); changed(); });
                edit.setEnabled(!saving);
                remove.setEnabled(!saving);
                actions.addView(edit);
                actions.addView(remove);
                entries.addView(actions);
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
        EditText path = new EditText(this);
        path.setTextColor(Theme.INK);
        path.setHintTextColor(Theme.MUTED);
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        path.setSingleLine(true);
        path.setHint("Server path, e.g. ~/.config/tool");
        path.setText(initial.path);
        form.addView(path);
        CheckBox writes = new CheckBox(this);
        writes.setText("Allow writes");
        writes.setTextColor(Theme.INK);
        writes.setChecked(initial.write);
        form.addView(writes);
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

    private static String mode(SandboxPath path) { return path.write ? " · read/write" : " · read-only"; }
    private TextView text(String value, boolean heading) {
        TextView view = Widgets.text(this, value, heading ? Theme.INK : Theme.MUTED, heading ? 15 : 13, heading);
        Widgets.margins(view, 0, Theme.dp(this, 12), 0, Theme.dp(this, 8));
        return view;
    }
    private TextView button(String title, Runnable action) {
        TextView view = Widgets.ghostButton(this, title);
        view.setOnClickListener(v -> action.run());
        return view;
    }
}
