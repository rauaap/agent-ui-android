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
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Session list for one project. Launched from {@link ProjectListActivity} with
 * the project's working directory, which scopes the list and supplies the path
 * for new sessions — so creating one asks for a name and nothing else.
 *
 * <p>Started without a project (the back-compat path against a server with no
 * {@code /projects}) it lists every session, as it did before projects existed.
 */
public class SessionListActivity extends Activity {

    static final String EXTRA_PROJECT_ID = "project_id";
    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_PROJECT_NAME = "project_name";
    static final String EXTRA_PROJECT_IS_REPO = "project_is_repo";

    private Api api;
    private Prefs prefs;
    private TextView sessionCount;
    private LinearLayout listContainer;

    /** Working directory this list is scoped to, or null when unscoped. */
    private String projectDir;
    private String projectName;
    /** The project's server-side id; empty against a server that has none. */
    private String projectId;
    /** Whether the project directory is a git repo, so worktrees are on offer. */
    private boolean projectIsRepo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        prefs = api.prefs();
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        projectName = getIntent().getStringExtra(EXTRA_PROJECT_NAME);
        projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId == null) projectId = "";
        projectIsRepo = getIntent().getBooleanExtra(EXTRA_PROJECT_IS_REPO, false);
        if (projectName == null && projectDir != null) {
            projectName = ProjectListActivity.basename(projectDir);
        }
        setContentView(buildRoot());
        maybeRequestNotificationPermission();
    }

    /** Notifications need a runtime grant on Android 13+. */
    private void maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConfigured()) {
            renderNeedsServer();
        } else {
            loadSessions();
        }
    }

    /* ---------------------------------------------------------------- */
    /* layout                                                           */
    /* ---------------------------------------------------------------- */

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);

        // ---- topbar ----
        LinearLayout topbar = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        topbar.setPadding(pad, pad, pad, pad);
        topbar.setBackgroundColor(Theme.BG);

        // Scoped to a project this is a second-level screen, so it gets a back
        // affordance and the project's name instead of the brand mark.
        if (projectDir != null) {
            TextView back = Widgets.ghostButton(this, "‹");
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
            back.setOnClickListener(v -> finish());
            Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
            topbar.addView(back);
        }

        LinearLayout brand = Widgets.column(this);
        LinearLayout brandHead = Widgets.row(this);
        if (projectDir == null) {
            View dot = new View(this);
            dot.setBackground(Theme.pill(this, Theme.ACCENT, Theme.ACCENT_SOFT, 4));
            LinearLayout.LayoutParams dotLp = lp(Theme.dp(this, 10), Theme.dp(this, 10));
            dotLp.rightMargin = Theme.dp(this, 10);
            dot.setLayoutParams(dotLp);
            brandHead.addView(dot);
        }
        TextView title = Widgets.text(this,
                projectName != null ? projectName : "Agent UI", Theme.INK, 18, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        brandHead.addView(title);
        sessionCount = Widgets.text(this, "", Theme.MUTED, 13, false);
        Widgets.margins(sessionCount, 0, Theme.dp(this, 2), 0, 0);
        brand.addView(brandHead);
        brand.addView(sessionCount);
        if (projectDir != null) {
            TextView path = Widgets.mono(this, projectDir, Theme.FAINT, 11.5f);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            Widgets.margins(path, 0, Theme.dp(this, 2), 0, 0);
            brand.addView(path);
        }
        brand.setLayoutParams(lp(0, WRAP, 1f));
        topbar.addView(brand);

        TextView gear = Widgets.ghostButton(this, "⚙");
        gear.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        Widgets.margins(gear, 0, 0, Theme.dp(this, 8), 0);
        topbar.addView(gear);

        TextView newBtn = Widgets.primaryButton(this, "+ New");
        newBtn.setOnClickListener(v -> showNewSessionDialog());
        topbar.addView(newBtn);

        root.addView(topbar);
        root.addView(divider());

        // ---- scrolling list ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        scroll.setFillViewport(true);
        listContainer = Widgets.column(this);
        listContainer.setPadding(pad, pad, pad, pad);
        scroll.addView(listContainer);
        root.addView(scroll);

        return root;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        v.setBackgroundColor(Theme.LINE_SOFT);
        return v;
    }

    /* ---------------------------------------------------------------- */
    /* data                                                             */
    /* ---------------------------------------------------------------- */

    private void loadSessions() {
        sessionCount.setText("…");
        api.listSessions(new Api.Cb<List<Session>>() {
            @Override public void onResult(List<Session> sessions) {
                renderList(scopeToProject(sessions), null);
            }
            @Override public void onError(String message) { renderList(null, message); }
        });
    }

    /**
     * Keep only this project's sessions. The REST API stays flat — every row
     * carries the link, so grouping happens here rather than in a nested route
     * that would have to path-encode a filesystem path.
     *
     * <p>The link is {@code project_id}: a session running in a worktree has a
     * {@code working_dir} somewhere else entirely, and matching on the path
     * would drop it out of the list it belongs to. Path matching survives only
     * as the fallback for a server old enough not to send an id, which is also
     * a server old enough to have no worktrees.
     */
    private List<Session> scopeToProject(List<Session> sessions) {
        if (projectDir == null) return sessions;
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            boolean mine = !projectId.isEmpty() && !s.projectId.isEmpty()
                    ? projectId.equals(s.projectId)
                    : projectDir.equals(s.workingDir);
            if (mine) out.add(s);
        }
        return out;
    }

    private void renderNeedsServer() {
        sessionCount.setText("not configured");
        listContainer.removeAllViews();
        LinearLayout box = emptyBox("No server set.\nTap to configure the server address.");
        box.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        listContainer.addView(box);
    }

    private void renderList(List<Session> sessions, String error) {
        listContainer.removeAllViews();

        if (error != null) {
            sessionCount.setText("unreachable");
            listContainer.addView(emptyBox(error + "\n\nCheck the server address in Settings."));
            return;
        }

        int n = sessions.size();
        sessionCount.setText(n + (n == 1 ? " session" : " sessions"));

        if (n == 0) {
            listContainer.addView(emptyBox(projectDir != null
                    ? "No sessions yet.\nTap + New to start one."
                    : "No sessions yet"));
            return;
        }

        for (Session s : sessions) listContainer.addView(sessionCard(s));
    }

    private LinearLayout emptyBox(String message) {
        LinearLayout box = Widgets.column(this);
        box.setGravity(Gravity.CENTER);
        TextView t = Widgets.text(this, message, Theme.FAINT, 14, false);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0x05FFFFFF);
        d.setCornerRadius(Theme.dp(this, 14));
        d.setStroke(Theme.dp(this, 1), Theme.LINE, Theme.dp(this, 6), Theme.dp(this, 5));
        box.setBackground(d);
        LinearLayout.LayoutParams p = lp(MATCH, Theme.dp(this, 180));
        box.setLayoutParams(p);
        int pad = Theme.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private View sessionCard(Session s) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(this, 12);
        card.setLayoutParams(cardLp);
        card.setClickable(true);
        card.setOnClickListener(v -> openSession(s));

        // head: name + (badge, delete)
        LinearLayout head = Widgets.row(this);
        TextView name = Widgets.text(this, s.name, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);

        LinearLayout actions = Widgets.row(this);
        TextView badge = Widgets.statusBadge(this, s.status);
        actions.addView(badge);
        TextView del = Widgets.text(this, "🗑", Theme.MUTED, 15, false);
        int dp32 = Theme.dp(this, 32);
        del.setGravity(Gravity.CENTER);
        del.setLayoutParams(lp(dp32, dp32));
        Widgets.margins(del, Theme.dp(this, 8), 0, 0, 0);
        del.setClickable(true);
        del.setOnClickListener(v -> confirmDelete(s));
        actions.addView(del);
        head.addView(actions);
        card.addView(head);

        // Path — redundant inside a project, where sessions share the project's
        // directory. A worktree session does not, so its cwd is always shown,
        // tagged, so it is obvious the session is not running at the root.
        boolean elsewhere = projectDir == null || !projectDir.equals(s.workingDir);
        if (elsewhere) {
            LinearLayout where = Widgets.row(this);
            if (s.ownsWorktree) {
                TextView tag = Widgets.tag(this, "worktree", Theme.INFO);
                Widgets.margins(tag, 0, 0, Theme.dp(this, 8), 0);
                where.addView(tag);
            }
            TextView path = Widgets.mono(this, s.workingDir, Theme.FAINT, 12);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            path.setLayoutParams(lp(0, WRAP, 1f));
            where.addView(path);
            Widgets.margins(where, 0, Theme.dp(this, 10), 0, 0);
            card.addView(where);
        }

        // meta
        String meta = formatAgent(s.agent) + "  ·  " + formatTime(s.lastActiveAt);
        TextView metaView = Widgets.text(this, meta, Theme.MUTED, 12.5f, false);
        Widgets.margins(metaView, 0, Theme.dp(this, elsewhere ? 8 : 10), 0, 0);
        card.addView(metaView);

        return card;
    }

    private void openSession(Session s) {
        Intent i = new Intent(this, SessionActivity.class);
        i.putExtra(SessionActivity.EXTRA_ID, s.id);
        i.putExtra(SessionActivity.EXTRA_NAME, s.name);
        i.putExtra(SessionActivity.EXTRA_DIR, s.workingDir);
        i.putExtra(SessionActivity.EXTRA_WORKTREE, s.ownsWorktree);
        i.putExtra(SessionActivity.EXTRA_STATUS, s.status);
        i.putExtra(SessionActivity.EXTRA_AUTO_WRITE, s.autoApproveWrite);
        i.putExtra(SessionActivity.EXTRA_AUTO_COMMAND, s.autoApproveCommand);
        startActivity(i);
    }

    private void confirmDelete(Session s) {
        String message = "Delete session \"" + s.name + "\"? This removes its history.";
        if (s.ownsWorktree) {
            message += "\n\nIts worktree at " + s.workingDir + " is removed too, "
                    + "unless it still holds uncommitted or untracked files.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete session")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> api.deleteSession(s.id, new Api.Cb<Api.Deletion>() {
                    @Override public void onResult(Api.Deletion deletion) {
                        loadSessions();
                        if (deletion.worktreeError != null) showWorktreeLeftDialog(s, deletion);
                    }
                    @Override public void onError(String message) { toast("Unable to delete: " + message); }
                }))
                .show();
    }

    /**
     * The session is gone; its worktree is not. Removal is never forced, and
     * git counts untracked files as dirty, so this is the expected outcome for
     * any session that created a file — a notice, not a failure.
     */
    private void showWorktreeLeftDialog(Session s, Api.Deletion deletion) {
        new AlertDialog.Builder(this)
                .setTitle("Worktree left in place")
                .setMessage("The session was deleted, but its worktree still has "
                        + "uncommitted or untracked files and was left on disk:\n\n"
                        + s.workingDir + "\n\n" + deletion.worktreeError)
                .setPositiveButton("OK", null)
                .show();
    }

    /* ---------------------------------------------------------------- */
    /* new session dialog                                               */
    /* ---------------------------------------------------------------- */

    private void showNewSessionDialog() {
        if (!prefs.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }

        LinearLayout content = Widgets.column(this);
        int pad = Theme.dp(this, 20);
        content.setPadding(pad, pad, pad, pad);

        content.addView(fieldLabel("Name"));
        EditText nameField = field("refactor db", InputType.TYPE_CLASS_TEXT, false);
        // Generated per dialog, and selected so typing replaces it outright —
        // the point is that creating a session needs no typing at all.
        String suggestion = NameGenerator.suggest(this);
        nameField.setText(suggestion);
        nameField.setSelection(0, suggestion.length());
        content.addView(nameField);

        // No working-directory field: scoped, the path comes from the project.
        // Unscoped (old server) it falls back to the configured default, which
        // is what the name-to-directory guessing used to produce anyway.
        if (projectDir != null) {
            TextView where = Widgets.mono(this, projectDir, Theme.FAINT, 12);
            Widgets.margins(where, 0, Theme.dp(this, 10), 0, 0);
            content.addView(where);
        }

        content.addView(spacer(14));
        content.addView(fieldLabel("Agent"));
        final int[] agentIdx = {0};
        TextView agent = Widgets.text(this, AGENT_LABELS[agentIdx[0]], Theme.INK, 15, false);
        agent.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int fp = Theme.dp(this, 12);
        agent.setPadding(fp, 0, fp, 0);
        agent.setGravity(Gravity.CENTER_VERTICAL);
        agent.setMinimumHeight(Theme.dp(this, 44));
        agent.setOnClickListener(av -> new AlertDialog.Builder(this)
                .setTitle("Agent")
                .setSingleChoiceItems(AGENT_LABELS, agentIdx[0], (d, which) -> {
                    agentIdx[0] = which;
                    agent.setText(AGENT_LABELS[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show());
        content.addView(agent);

        // ---- worktree ----
        // Only offered for a project the server reports as a git repo: anywhere
        // else `git worktree add` would refuse, and the toggle would be an
        // invitation to a 400. The server checks again for real.
        final Switch worktreeSwitch = new Switch(this);
        final EditText pathField = field("/projects/app-fix-login",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        final EditText branchField = field("fix-login", InputType.TYPE_CLASS_TEXT, false);

        if (projectDir != null && projectIsRepo) {
            content.addView(spacer(22));
            LinearLayout worktreeRow = Widgets.row(this);
            TextView worktreeLabel = Widgets.text(this, "Create a git worktree", Theme.INK, 15, false);
            worktreeLabel.setLayoutParams(lp(0, WRAP, 1f));
            worktreeRow.addView(worktreeLabel);
            worktreeRow.addView(worktreeSwitch);
            content.addView(worktreeRow);

            TextView worktreeHint = Widgets.text(this,
                    "Run this session in its own checkout on a new branch, so it "
                            + "doesn't share the project directory with other sessions.",
                    Theme.MUTED, 12.5f, false);
            Widgets.margins(worktreeHint, 0, Theme.dp(this, 7), 0, 0);
            content.addView(worktreeHint);

            // The inputs live in their own block so the toggle can hide them
            // whole, rather than leaving two disabled fields taking up space.
            LinearLayout worktreeFields = Widgets.column(this);
            worktreeFields.setVisibility(View.GONE);
            worktreeFields.addView(spacer(14));
            worktreeFields.addView(fieldLabel("Worktree directory"));
            worktreeFields.addView(pathField);
            worktreeFields.addView(spacer(14));
            worktreeFields.addView(fieldLabel("Branch"));
            worktreeFields.addView(branchField);
            content.addView(worktreeFields);

            seedFromName(nameField, pathField, n -> Worktree.pathFor(projectDir, n));
            seedFromName(nameField, branchField, Worktree::slug);
            worktreeSwitch.setOnCheckedChangeListener((b, checked) ->
                    worktreeFields.setVisibility(checked ? View.VISIBLE : View.GONE));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New session")
                .setView(wrapScroll(content))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null) // overridden below to keep dialog open on error
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameField.getText().toString().trim();
            if (name.isEmpty()) {
                toast("Name is required");
                return;
            }
            String dir = projectDir != null ? projectDir : fallbackDir(name);

            String worktreePath = null;
            String branch = null;
            if (worktreeSwitch.isChecked()) {
                worktreePath = trimTrailingSlashes(pathField.getText().toString().trim());
                branch = branchField.getText().toString().trim();
                if (worktreePath.isEmpty() || branch.isEmpty()) {
                    toast("Worktree directory and branch are required");
                    return;
                }
                if (!worktreePath.startsWith("/")) {
                    toast("Worktree directory must be an absolute path");
                    return;
                }
            }

            v.setEnabled(false);
            // A worktree that could not be created means no session at all, so
            // the dialog stays open with the server's reason rather than
            // dropping the user into a session that runs somewhere unexpected.
            api.createSession(name, dir, AGENT_IDS[agentIdx[0]], worktreePath, branch,
                    new Api.Cb<Session>() {
                        @Override public void onResult(Session session) {
                            dialog.dismiss();
                            openSession(session);
                        }
                        @Override public void onError(String message) {
                            v.setEnabled(true);
                            toast("Unable to create: " + message);
                        }
                    });
        }));
        dialog.show();
    }

    private interface Seed { String from(String name); }

    /**
     * Keep {@code target} tracking the session name, until the user edits it by
     * hand — after which the two are independent. The same
     * seed-then-break-the-link pattern the new-project dialog uses for the name
     * and the directory it fills in.
     */
    private void seedFromName(EditText nameField, EditText target, Seed seed) {
        final boolean[] edited = {false};
        final boolean[] programmatic = {false};
        target.setText(seed.from(nameField.getText().toString()));
        target.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!programmatic[0]) edited[0] = true;
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        nameField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (edited[0]) return;
                programmatic[0] = true;
                target.setText(seed.from(s.toString()));
                programmatic[0] = false;
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    /** A trailing slash would make the path look unlike the one we get back. */
    private static String trimTrailingSlashes(String path) {
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Working directory for a session created from the unscoped list: the
     * configured projects directory plus the session name, the same shape the
     * name-tracking directory field used to produce.
     */
    private String fallbackDir(String name) {
        String base = prefs.defaultDir().trim();
        if (base.isEmpty()) base = "/projects/";
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private TextView fieldLabel(String s) {
        TextView t = Widgets.text(this, s, Theme.MUTED, 13, true);
        Widgets.margins(t, 0, 0, 0, Theme.dp(this, 7));
        return t;
    }

    private EditText field(String hint, int inputType, boolean mono) {
        EditText e = new EditText(this);
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
        e.setLayoutParams(lp(MATCH, WRAP));
        return e;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(lp(MATCH, Theme.dp(this, dp)));
        return v;
    }

    private ScrollView wrapScroll(View content) {
        ScrollView s = new ScrollView(this);
        s.addView(content);
        return s;
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                          */
    /* ---------------------------------------------------------------- */

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Agents the backend can run. Hardcoded — the server exposes no listing. */
    private static final String[] AGENT_IDS    = {"claude-code", "opencode"};
    private static final String[] AGENT_LABELS = {"Claude Code", "OpenCode"};

    static String formatAgent(String agent) {
        for (int i = 0; i < AGENT_IDS.length; i++) {
            if (AGENT_IDS[i].equals(agent)) return AGENT_LABELS[i];
        }
        return agent;
    }

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, h:mm a");

    static String formatTime(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Instant instant = Instant.parse(value);
            return instant.atZone(ZoneId.systemDefault()).format(TIME_FMT);
        } catch (Exception ignored) {
            return value;
        }
    }
}
