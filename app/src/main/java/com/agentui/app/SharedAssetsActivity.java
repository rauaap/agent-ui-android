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

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Immediately saved directory registrations, scoped to global or one project. */
public class SharedAssetsActivity extends Activity {
    static final String EXTRA_PROJECT_PATH = "project_path";
    private Api api;
    private String server;
    private String projectPath;
    private String projectId = "";
    private List<SharedAssetRoot> roots = new ArrayList<>();
    private boolean loaded;
    private boolean busy;
    private LinearLayout entries;
    private TextView status;
    private TextView add;
    private TextView refresh;

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
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy) load();
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
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        header.addView(back);
        LinearLayout headings = Widgets.column(this);
        headings.setLayoutParams(lp(0, WRAP, 1f));
        headings.addView(Widgets.text(this, "Shared assets", Theme.INK, 18, true));
        headings.addView(Widgets.text(this, projectPath == null ? "Global roots" : "Project roots",
                Theme.MUTED, 13, false));
        TextView address = Widgets.mono(this, server, Theme.FAINT, 11.5f);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        headings.addView(address);
        header.addView(headings);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        LinearLayout form = Widgets.column(this);
        form.setPadding(pad, pad, pad, pad);
        if (projectPath != null) form.addView(Widgets.mono(this, projectPath, Theme.FAINT, 12));
        form.addView(note("Registered directories are served directly. Anyone who can reach the server can read "
                + "their files without a token. Register only directories intended for sharing."));
        form.addView(note("Registration does not create directories, copy files, or grant sandbox access. "
                + "Project association only organizes settings; it is not an access boundary."));
        form.addView(note("Open targets the root's index.html. If none exists, the browser shows 404. "
                + "Renaming breaks existing links; deleting a registration never deletes files."));
        entries = Widgets.column(this);
        form.addView(entries);
        add = button("+ Add root", () -> edit(null));
        form.addView(add);
        status = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        form.addView(status);
        refresh = button("Refresh", this::load);
        form.addView(refresh);
        scroll.addView(form);
        root.addView(scroll);
        return root;
    }

    private boolean sameServer() {
        if (api.prefs().isConfigured() && server.equals(api.prefs().httpBase())) return true;
        loaded = false;
        fail("Server address changed or is not configured. Reopen this editor after saving Settings.");
        return false;
    }

    private void load() {
        if (busy || !sameServer()) return;
        loaded = false;
        busy = true;
        render();
        message("Loading…", Theme.MUTED);
        if (projectPath == null) loadRoots();
        else api.listProjects(new Api.StatusCb<List<Project>>() {
            @Override public void onResult(List<Project> projects) {
                if (isDestroyed()) return;
                if (!sameServer()) { busy = false; render(); return; }
                Project project = Archive.find(projects, projectPath);
                if (project == null || project.id.isEmpty()) {
                    busy = false;
                    fail("This project is no longer on the server or has no project ID.");
                    return;
                }
                projectId = project.id;
                loadRoots();
            }
            @Override public void onError(String error) { loadFailed(error); }
            @Override public void onHttpError(int code, String error) { loadFailed(error); }
        });
    }

    private void loadRoots() {
        api.listSharedAssetRoots(new Api.StatusCb<List<SharedAssetRoot>>() {
            @Override public void onResult(List<SharedAssetRoot> result) {
                if (isDestroyed()) return;
                busy = false;
                if (!sameServer()) return;
                roots = SharedAssetRoot.forProject(result, projectId);
                loaded = true;
                render();
                message("Changes save immediately to this server.", Theme.MUTED);
            }
            @Override public void onError(String error) { loadFailed(error); }
            @Override public void onHttpError(int code, String error) {
                loadFailed(code == 404 ? "This server does not support shared assets." : error);
            }
        });
    }

    private void loadFailed(String error) {
        if (isDestroyed()) return;
        busy = false;
        loaded = false;
        fail(error);
    }

    private void render() {
        entries.removeAllViews();
        if (loaded) {
            if (roots.isEmpty()) entries.addView(note("No roots registered in this scope."));
            for (SharedAssetRoot entry : roots) {
                LinearLayout card = Widgets.column(this);
                card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
                int pad = Theme.dp(this, 14);
                card.setPadding(pad, pad, pad, pad);
                card.setLayoutParams(lp(MATCH, WRAP));
                Widgets.margins(card, 0, 0, 0, Theme.dp(this, 12));
                card.addView(Widgets.text(this, entry.assetRoot, Theme.INK, 15, true));
                TextView path = Widgets.mono(this, entry.path, Theme.FAINT, 12);
                path.setTextIsSelectable(true);
                card.addView(path);
                card.addView(Widgets.mono(this, entry.url, Theme.INFO, 12));
                LinearLayout actions = Widgets.row(this);
                TextView open = button("Open", () -> {
                    if (sameServer()) MarkdownLinks.open(this, entry.url);
                });
                TextView edit = button("Edit", () -> edit(entry));
                TextView remove = button("Delete", () -> confirmDelete(entry));
                open.setEnabled(!busy);
                edit.setEnabled(!busy);
                remove.setEnabled(!busy);
                actions.addView(open);
                actions.addView(edit);
                actions.addView(remove);
                card.addView(actions);
                entries.addView(card);
            }
        }
        add.setEnabled(loaded && !busy);
        refresh.setEnabled(!busy);
    }

    private void edit(SharedAssetRoot initial) {
        if (busy || !loaded || !sameServer()) return;
        LinearLayout form = Widgets.column(this);
        int pad = Theme.dp(this, 20);
        form.setPadding(pad, pad, pad, pad);
        form.addView(Widgets.fieldLabel(this, "URL identifier"));
        EditText name = Widgets.field(this, "notes", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        name.setText(initial == null ? "" : initial.assetRoot);
        form.addView(name);
        form.addView(note("ASCII letters, digits, underscores and hyphens only. Renaming breaks old links."));
        form.addView(Widgets.fieldLabel(this, "Absolute server directory"));
        EditText path = Widgets.field(this, "/home/user/notes", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        path.setText(initial == null ? "" : initial.path);
        form.addView(path);
        form.addView(note("No ~ or environment-variable expansion. The directory need not exist yet."));
        TextView error = Widgets.text(this, "", Theme.DANGER, 12.5f, false);
        form.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(initial == null ? "Add shared asset root" : "Edit shared asset root")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy || !sameServer()) return;
            String identifier = name.getText().toString().trim();
            String directory = path.getText().toString(); // Preserve literal server path whitespace.
            if (!SharedAssetRoot.validIdentifier(identifier)) {
                name.setError("Use only ASCII letters, digits, _ and -"); return;
            }
            if (!SharedAssetRoot.validPath(directory)) {
                path.setError("Enter an absolute server path starting with /"); return;
            }
            busy = true;
            render();
            setSaving(dialog, true);
            error.setText("Saving…");
            Api.StatusCb<SharedAssetRoot> callback = new Api.StatusCb<SharedAssetRoot>() {
                @Override public void onResult(SharedAssetRoot saved) {
                    if (isDestroyed()) return;
                    busy = false;
                    dialog.dismiss();
                    load(); // Reload normalized path, renamed identifier and current scope from the server.
                }
                @Override public void onError(String problem) {
                    if (isDestroyed()) return;
                    busy = false;
                    render();
                    setSaving(dialog, false);
                    error.setText(problem); // Keep fields intact on conflicts and validation failures.
                }
                @Override public void onHttpError(int code, String problem) {
                    onError(code == 409 ? "Identifier or directory is already registered. " + problem : problem);
                }
            };
            if (initial == null) api.createSharedAssetRoot(identifier, directory, projectId, callback);
            else api.updateSharedAssetRoot(initial.assetRoot, identifier, directory, callback);
        }));
        dialog.show();
    }

    private void confirmDelete(SharedAssetRoot entry) {
        if (busy || !sameServer()) return;
        new AlertDialog.Builder(this).setTitle("Delete registration \"" + entry.assetRoot + "\"?")
                .setMessage("Files stay on disk. Existing links to this root stop working.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                    if (busy || !sameServer()) return;
                    busy = true;
                    render();
                    message("Deleting…", Theme.MUTED);
                    api.deleteSharedAssetRoot(entry.assetRoot, new Api.Cb<Void>() {
                        @Override public void onResult(Void ignored) {
                            if (isDestroyed()) return;
                            busy = false;
                            load();
                        }
                        @Override public void onError(String error) {
                            if (isDestroyed()) return;
                            busy = false;
                            fail(error);
                        }
                    });
                }).show();
    }

    private void setSaving(AlertDialog dialog, boolean saving) {
        dialog.setCancelable(!saving);
        dialog.setCanceledOnTouchOutside(!saving);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!saving);
    }

    private void fail(String error) {
        if (isDestroyed()) return;
        render();
        message("Couldn't complete request: " + error, Theme.DANGER);
    }

    private void message(String text, int color) {
        status.setTextColor(color);
        status.setText(text);
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (!busy) super.onBackPressed();
    }

    private TextView note(String text) {
        TextView view = Widgets.text(this, text, Theme.MUTED, 12.5f, false);
        Widgets.margins(view, 0, Theme.dp(this, 8), 0, Theme.dp(this, 12));
        return view;
    }

    private TextView button(String text, Runnable action) {
        TextView view = Widgets.ghostButton(this, text);
        view.setOnClickListener(v -> action.run());
        return view;
    }
}
