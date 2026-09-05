package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    static final String EXTRA_PROJECT_ARCHIVED = "project_archived";

    private Api api;
    private Prefs prefs;
    private TextView sessionCount;
    private LinearLayout listContainer;
    private ForegroundPoller statusPoller;
    private boolean statusPollInFlight;

    /** Working directory this list is scoped to, or null when unscoped. */
    private String projectDir;
    private String projectName;
    /** The project's server-side id; empty against a server that has none. */
    private String projectId;
    /** Whether the project directory is a git repo, so worktrees are on offer. */
    private boolean projectIsRepo;
    /**
     * Whether the project itself is archived — reached from the archive, then.
     * Its sessions are all archived by the server's cascade, so this list shows
     * them instead of hiding them, and offers no way to start another, in it or
     * in one of its worktrees.
     */
    private boolean projectArchived;
    /**
     * The project's worktrees, refreshed with the session list. Kept here
     * because three things want them: the picker in the new-session dialog, the
     * count on the way into the worktree screen, and the note shown when the
     * last session using one is deleted.
     */
    private final List<Worktree> worktrees = new ArrayList<>();
    /**
     * Cleared when a server 404s on {@code /worktrees}, i.e. one predating them
     * — the picker and the worktree screen then stay out of the way entirely.
     */
    private boolean worktreesSupported = true;
    /**
     * The agents this server can run, filled from {@code GET /agents} and empty
     * until it lands — or against a server that has no such endpoint, where
     * {@link Agent#FALLBACK} stands in. The picker in the new-session dialog is
     * built from it, and the cards label a session's agent through it.
     */
    private final List<Agent> agents = new ArrayList<>();
    private boolean agentsLoading;
    private boolean openNewAfterAgentsLoad;
    /** The sessions currently on screen, so a worktree load can re-render them. */
    private List<Session> lastSessions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        prefs = api.prefs();
        statusPoller = new ForegroundPoller(3000, this::pollSessionStatuses);
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        projectName = getIntent().getStringExtra(EXTRA_PROJECT_NAME);
        projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId == null) projectId = "";
        projectIsRepo = getIntent().getBooleanExtra(EXTRA_PROJECT_IS_REPO, false);
        projectArchived = getIntent().getBooleanExtra(EXTRA_PROJECT_ARCHIVED, false);
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
            statusPoller.start();
        }
    }

    @Override
    protected void onPause() {
        statusPoller.stop();
        super.onPause();
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
        if (projectArchived) {
            TextView tag = Widgets.tag(this, "archived", Theme.MUTED);
            Widgets.margins(tag, Theme.dp(this, 8), 0, 0, 0);
            brandHead.addView(tag);
        }
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

        // The gear always opens the settings for what is on screen: this
        // project's, or — unscoped, where there is no project — the server's.
        TextView gear = Widgets.ghostButton(this, "⚙");
        gear.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        gear.setOnClickListener(v -> {
            if (projectDir == null) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                openProjectSettings();
            }
        });
        Widgets.margins(gear, 0, 0, Theme.dp(this, 8), 0);
        topbar.addView(gear);

        // The server refuses a session in an archived project, so the control is
        // hidden rather than left to 409.
        if (!projectArchived) {
            TextView newBtn = Widgets.primaryButton(this, "+ New");
            newBtn.setOnClickListener(v -> showNewSessionDialog());
            topbar.addView(newBtn);
        }

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
        // Do not carry one server's registry into another after Settings changes
        // the address. The fallback supplies labels until the fresh list lands.
        agents.clear();
        loadAgents();
        loadWorktrees();
        api.listSessions(new Api.Cb<List<Session>>() {
            @Override public void onResult(List<Session> sessions) {
                renderList(scopeToProject(sessions), null);
            }
            @Override public void onError(String message) { renderList(null, message); }
        });
    }

    /** Refresh live badges without repeating the agents and worktrees loads. */
    private void pollSessionStatuses() {
        if (statusPollInFlight) return;
        statusPollInFlight = true;
        api.listSessions(new Api.Cb<List<Session>>() {
            @Override public void onResult(List<Session> sessions) {
                statusPollInFlight = false;
                List<Session> scoped = scopeToProject(sessions);
                if (sessionStatusesChanged(scoped)) {
                    renderList(scoped, null);
                } else {
                    // Keep non-visible fields fresh for actions that consult the
                    // cached list, without rebuilding the UI every three seconds.
                    lastSessions = scoped;
                }
            }

            @Override public void onError(String message) {
                statusPollInFlight = false;
                // Keep the last known list through a transient polling failure.
            }
        });
    }

    private boolean sessionStatusesChanged(List<Session> sessions) {
        if (lastSessions == null || lastSessions.size() != sessions.size()) return true;
        for (Session oldSession : lastSessions) {
            Session current = null;
            for (Session candidate : sessions) {
                if (oldSession.id.equals(candidate.id)) {
                    current = candidate;
                    break;
                }
            }
            if (current == null
                    || !oldSession.status.equals(current.status)
                    || !oldSession.archivedAt.equals(current.archivedAt)
                    || !oldSession.worktreeId.equals(current.worktreeId)
                    || !oldSession.workingDir.equals(current.workingDir)) return true;
        }
        return false;
    }

    /**
     * The agents this server can run. Refetched with the list rather than once
     * per process: it is a different server's answer after the address changes
     * in Settings, and one request against a hand-written list is a fair trade.
     *
     * <p>A failure is silent, like the worktree load — the fallback list is
     * still a working picker, and an unreachable server is already being
     * reported by the session list itself.
     */
    private void loadAgents() {
        agentsLoading = true;
        api.listAgents(new Api.Cb<List<Agent>>() {
            @Override public void onResult(List<Agent> list) {
                agents.clear();
                // An empty answer leaves this empty, which agentChoices() reads
                // as no answer and falls back — a picker with nothing in it
                // would be worse than a stale one.
                agents.addAll(list);
                agentsLoading = false;
                // Cards label the agent through this, and are often on screen
                // by the time it lands.
                if (lastSessions != null) renderList(lastSessions, null);
                openPendingNewSession();
            }

            @Override public void onError(String message) {
                agentsLoading = false;
                openPendingNewSession();
            }
        });
    }

    private void openPendingNewSession() {
        if (!openNewAfterAgentsLoad) return;
        openNewAfterAgentsLoad = false;
        showNewSessionDialog();
    }

    /**
     * The project's worktrees, for the picker and the header row. A failure is
     * silent: the session list is what this screen is for, and the picker's
     * fallback — "Project directory", plus the form itself — still works.
     */
    private void loadWorktrees() {
        if (projectDir == null || !projectIsRepo || !worktreesSupported) return;
        api.listWorktrees(projectDir, new Api.StatusCb<List<Worktree>>() {
            @Override public void onResult(List<Worktree> list) {
                worktrees.clear();
                worktrees.addAll(list);
                // The header row counts them, and the list is already on screen
                // by the time this lands as often as not.
                if (lastSessions != null) renderList(lastSessions, null);
            }

            @Override public void onError(String message) {}

            @Override public void onHttpError(int code, String message) {
                // A server old enough to create worktrees only as part of a
                // session has no /worktrees at all. Stop asking, and stop
                // offering a picker whose every option would 404.
                if (code == 404) {
                    worktreesSupported = false;
                    worktrees.clear();
                }
            }
        });
    }

    /**
     * Keep only this project's sessions. The REST API stays flat — every row
     * carries the link, so grouping happens here rather than in a nested route
     * that would have to path-encode a filesystem path. See
     * {@link Project#owns(String, String, Session)} for what counts as a link.
     */
    private List<Session> scopeToProject(List<Session> sessions) {
        if (projectDir == null) return sessions;
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (Project.owns(projectId, projectDir, s)) out.add(s);
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
            lastSessions = null;
            sessionCount.setText("unreachable");
            listContainer.addView(emptyBox(error + "\n\nCheck the server address in Settings."));
            return;
        }

        // Unfiltered: an archived session still holds its worktree, so it has
        // to count when working out whether one has been left with none.
        lastSessions = sessions;

        // Archived sessions are hidden here and listed under Settings ▸ Archived;
        // the count still admits to them, so none go missing silently. Under an
        // archived project there is nothing but archived sessions, so hiding
        // them would leave an empty screen — they are the content there.
        List<Session> shown = projectArchived ? sessions : Archive.liveSessions(sessions);
        int hidden = sessions.size() - shown.size();

        int n = shown.size();
        String count = n + (n == 1 ? " session" : " sessions");
        if (projectArchived) count = n + (n == 1 ? " archived session" : " archived sessions");
        else if (hidden > 0) count = count + "  ·  " + hidden + " archived";
        sessionCount.setText(count);

        if (projectDir != null && projectIsRepo && worktreesSupported) {
            listContainer.addView(worktreeRow());
        }

        if (n == 0) {
            listContainer.addView(emptyBox(hidden > 0
                    ? "Every session here is archived.\nRestore one from Settings ▸ Archived."
                    : projectDir != null
                            ? "No sessions yet.\nTap + New to start one."
                            : "No sessions yet"));
            return;
        }

        for (Session s : shown) listContainer.addView(sessionCard(s));
    }

    /**
     * Way into the worktree screen. Worktrees are a project's, not a session's,
     * so they are listed beside the sessions rather than inside one — and this
     * is the only place a worktree can be cleaned up from.
     */
    private View worktreeRow() {
        LinearLayout row = Widgets.row(this);
        row.setBackground(Theme.rounded(this, Theme.PANEL, 12, Theme.LINE, 1));
        int padH = Theme.dp(this, 14);
        int padV = Theme.dp(this, 12);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams rowLp = lp(MATCH, WRAP);
        rowLp.bottomMargin = Theme.dp(this, 12);
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setOnClickListener(v -> startActivity(
                WorktreeListActivity.intent(this, projectDir, projectName, projectArchived)));

        TextView label = Widgets.text(this, "Worktrees", Theme.INK, 14, true);
        label.setLayoutParams(lp(0, WRAP, 1f));
        row.addView(label);
        int n = worktrees.size();
        row.addView(Widgets.text(this, n == 0 ? "none" : String.valueOf(n), Theme.MUTED, 13, false));
        TextView chevron = Widgets.text(this, "›", Theme.FAINT, 18, false);
        Widgets.margins(chevron, Theme.dp(this, 10), 0, 0, 0);
        row.addView(chevron);
        return row;
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
        // Under an archived project every row is archived and the header says so
        // once; a lone archived session is the surprise worth marking.
        if (s.isArchived() && !projectArchived) {
            TextView tag = Widgets.tag(this, "archived", Theme.MUTED);
            Widgets.margins(tag, 0, 0, Theme.dp(this, 8), 0);
            actions.addView(tag);
        }
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
            if (!s.worktreeId.isEmpty()) {
                TextView tag = Widgets.tag(this, "worktree", Theme.INFO);
                Widgets.margins(tag, 0, 0, Theme.dp(this, 8), 0);
                where.addView(tag);
            } else if (s.isFormerWorktree(projectDir)) {
                TextView tag = Widgets.tag(this, "former worktree", Theme.MUTED);
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
        i.putExtra(SessionActivity.EXTRA_PROJECT_DIR, projectDir);
        i.putExtra(SessionActivity.EXTRA_WORKTREE_ID, s.worktreeId);
        i.putExtra(SessionActivity.EXTRA_STATUS, s.status);
        i.putExtra(SessionActivity.EXTRA_AUTO_WRITE, s.autoApproveWrite);
        i.putExtra(SessionActivity.EXTRA_AUTO_COMMAND, s.autoApproveCommand);
        i.putExtra(SessionActivity.EXTRA_ARCHIVED, s.isArchived());
        startActivity(i);
    }

    /* ---------------------------------------------------------------- */
    /* project settings                                                 */
    /* ---------------------------------------------------------------- */

    private static final int REQ_PROJECT_SETTINGS = 3;

    private void openProjectSettings() {
        Intent i = new Intent(this, ProjectSettingsActivity.class);
        i.putExtra(ProjectSettingsActivity.EXTRA_PROJECT_DIR, projectDir);
        i.putExtra(ProjectSettingsActivity.EXTRA_PROJECT_NAME, projectName);
        startActivityForResult(i, REQ_PROJECT_SETTINGS);
    }

    /**
     * Archiving the whole project takes it off the project list and leaves this
     * screen showing sessions that are no longer live, so step back rather than
     * pretend otherwise. Unarchiving is the reverse and stays put — only the
     * controls that an archived project withholds have to come back.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECT_SETTINGS || data == null) return;
        if (!data.hasExtra(ProjectSettingsActivity.EXTRA_ARCHIVED)) return;
        boolean archived = data.getBooleanExtra(ProjectSettingsActivity.EXTRA_ARCHIVED, false);
        if (archived) {
            finish();
            return;
        }
        // The header's archived tag and the withheld "+ New" are decided in
        // buildRoot, so rebuild it; onResume refills the list right after.
        projectArchived = false;
        setContentView(buildRoot());
    }

    private void confirmDelete(Session s) {
        // Deleting a session touches nothing on disk: its worktree belongs to
        // the project and stays, along with any other session using it.
        String message = "Delete session \"" + s.name + "\"? This removes its history.";
        if (!s.worktreeId.isEmpty()) {
            message += "\n\nThe worktree at " + s.workingDir + " stays where it is.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete session")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> api.deleteSession(s.id, new Api.Cb<Void>() {
                    @Override public void onResult(Void ignored) {
                        boolean wasLast = wasLastSessionOnWorktree(s);
                        loadSessions();
                        if (wasLast) showWorktreeRemainsDialog(s);
                    }
                    @Override public void onError(String message) { toast("Unable to delete: " + message); }
                }))
                .show();
    }

    /** Whether {@code s} was the only session left in its worktree, if it had one. */
    private boolean wasLastSessionOnWorktree(Session s) {
        if (s.worktreeId.isEmpty() || lastSessions == null) return false;
        for (Session other : lastSessions) {
            if (!other.id.equals(s.id) && s.worktreeId.equals(other.worktreeId)) return false;
        }
        return true;
    }

    /**
     * The last session using a worktree is gone; the worktree is not. Said so
     * the worktree does not feel abandoned — not a nudge to delete it, since
     * finishing a session does not mean finishing with the branch.
     */
    private void showWorktreeRemainsDialog(Session s) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Session deleted")
                .setMessage("Its worktree is still there:\n\n" + s.workingDir
                        + "\n\nStart another session in it, or clean it up from Worktrees.")
                .setNegativeButton("OK", null);
        // The worktree screen is per project, so it needs one to open in.
        if (projectDir != null) {
            b.setPositiveButton("Worktrees", (d, w) -> startActivity(
                    WorktreeListActivity.intent(this, projectDir, projectName, projectArchived)));
        }
        b.show();
    }

    /* ---------------------------------------------------------------- */
    /* new session dialog                                               */
    /* ---------------------------------------------------------------- */

    private void showNewSessionDialog() {
        if (!prefs.isConfigured()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        // A saved preference may name an adapter absent from the legacy fallback
        // list. Wait for the in-flight registry request so "New" never briefly
        // preselects the wrong agent just because it was tapped quickly.
        if (agentsLoading) {
            openNewAfterAgentsLoad = true;
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
        // Snapshotted for the life of the dialog: a refresh landing while it is
        // open must not renumber the choice under the selected index.
        final List<Agent> choices = agentChoices();
        final CharSequence[] agentLabels = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) agentLabels[i] = choices.get(i).name;
        final int[] agentIdx = {Agent.defaultIndex(choices, prefs.defaultAgent())};
        TextView agent = selector(choices.get(agentIdx[0]).name);
        agent.setOnClickListener(av -> new AlertDialog.Builder(this)
                .setTitle("Agent")
                .setSingleChoiceItems(agentLabels, agentIdx[0], (d, which) -> {
                    agentIdx[0] = which;
                    agent.setText(agentLabels[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show());
        content.addView(agent);

        // ---- worktree ----
        // Only offered for a project the server reports as a git repo: anywhere
        // else `git worktree add` would refuse, and the picker's every option
        // would be an invitation to a 400. The server checks again for real.
        //
        // Empty means the project directory, which is the default and needs no
        // worktree_id at all.
        final String[] worktreeId = {""};
        final TextView worktreePicker;
        if (projectDir != null && projectIsRepo && worktreesSupported) {
            content.addView(spacer(14));
            content.addView(fieldLabel("Run in"));
            worktreePicker = selector(PROJECT_DIRECTORY);
            content.addView(worktreePicker);
            TextView worktreeHint = Widgets.text(this,
                    "The project directory, or one of its worktrees — a separate "
                            + "checkout on its own branch. Sharing one with another "
                            + "session is fine.",
                    Theme.MUTED, 12.5f, false);
            Widgets.margins(worktreeHint, 0, Theme.dp(this, 7), 0, 0);
            content.addView(worktreeHint);
            worktreePicker.setOnClickListener(v ->
                    chooseWorktree(nameField, worktreePicker, worktreeId));
        } else {
            worktreePicker = null;
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

            v.setEnabled(false);
            api.createSession(name, dir, choices.get(agentIdx[0]).id, worktreeId[0],
                    new Api.StatusCb<Session>() {
                        @Override public void onResult(Session session) {
                            dialog.dismiss();
                            openSession(session);
                        }

                        @Override public void onError(String message) {
                            v.setEnabled(true);
                            toast("Unable to create: " + message);
                        }

                        @Override public void onHttpError(int code, String message) {
                            v.setEnabled(true);
                            toast("Unable to create: " + message);
                            // A worktree removed since the picker was filled: no
                            // session was created, so the selection is reset to
                            // somewhere that still exists and the list refreshed.
                            // A 404 naming the project instead is a different
                            // problem, and resetting the picker would not help.
                            if (code == 404 && message.contains("Worktree")) {
                                selectWorktree(worktreePicker, worktreeId, null);
                                loadWorktrees();
                            }
                        }
                    });
        }));
        dialog.show();
    }

    /* ---------------------------------------------------------------- */
    /* worktree picker                                                  */
    /* ---------------------------------------------------------------- */

    /** The picker's default: no worktree at all, i.e. no {@code worktree_id}. */
    private static final String PROJECT_DIRECTORY = "Project directory";

    /**
     * Pick where the session runs: the project directory, one of its worktrees,
     * or a worktree created on the spot — which the form then selects, since
     * creating one from here is only ever a step towards using it.
     */
    private void chooseWorktree(EditText nameField, TextView picker, String[] worktreeId) {
        List<CharSequence> labels = new ArrayList<>();
        labels.add(PROJECT_DIRECTORY);
        int checked = 0;
        for (int i = 0; i < worktrees.size(); i++) {
            Worktree w = worktrees.get(i);
            labels.add(pickerLabel(w));
            if (w.id.equals(worktreeId[0])) checked = i + 1;
        }
        final int newIndex = labels.size();
        labels.add("New worktree…");

        new AlertDialog.Builder(this)
                .setTitle("Run in")
                .setSingleChoiceItems(twoLineChoices(labels), checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        selectWorktree(picker, worktreeId, null);
                        return;
                    }
                    if (which == newIndex) {
                        // The session name is the branch seed, the same way it
                        // seeds the name of everything else here.
                        WorktreeForm.show(this, api, projectDir,
                                nameField.getText().toString(), created -> {
                                    mergeWorktree(created);
                                    selectWorktree(picker, worktreeId, created);
                                });
                        return;
                    }
                    Worktree w = worktrees.get(which - 1);
                    if (!w.exists) confirmMissingDirectory(w, picker, worktreeId);
                    else selectWorktree(picker, worktreeId, w);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The full path, then what is worth knowing about it. Sharing a worktree
     * with another session is a supported choice, so the session count is said
     * plainly rather than warned about.
     */
    private static String pickerLabel(Worktree w) {
        String label = w.path;
        String subtitle = w.subtitle();
        if (!w.exists) {
            subtitle = subtitle.isEmpty()
                    ? "directory missing" : subtitle + "  ·  directory missing";
        }
        return subtitle.isEmpty() ? label : label + "\n" + subtitle;
    }

    /**
     * The platform's single-choice row, allowed to wrap. Its {@code CheckedTextView}
     * is single-line in some themes, which would cut a worktree's path down to
     * its head — the least useful half.
     */
    private ArrayAdapter<CharSequence> twoLineChoices(List<CharSequence> labels) {
        return new ArrayAdapter<CharSequence>(
                this, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                View text = row.findViewById(android.R.id.text1);
                if (text instanceof TextView) {
                    ((TextView) text).setSingleLine(false);
                    ((TextView) text).setMaxLines(3);
                }
                return row;
            }
        };
    }

    /**
     * The server will happily attach a session to a worktree whose directory is
     * gone — it deliberately does not recreate one, since a bare directory is
     * not a worktree — and the session then fails on its first turn. So the
     * warning is here.
     */
    private void confirmMissingDirectory(Worktree w, TextView picker, String[] worktreeId) {
        new AlertDialog.Builder(this)
                .setTitle("Directory missing")
                .setMessage("The directory for this worktree is gone:\n\n" + w.path
                        + "\n\nA session started here fails on its first turn. Clean it "
                        + "up from Worktrees instead.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use anyway", (d, x) -> selectWorktree(picker, worktreeId, w))
                .show();
    }

    private void selectWorktree(TextView picker, String[] worktreeId, Worktree w) {
        worktreeId[0] = w == null ? "" : w.id;
        if (picker != null) picker.setText(w == null ? PROJECT_DIRECTORY : w.path);
    }

    /** Keep a just-created worktree in the cache, newest first, without a refetch. */
    private void mergeWorktree(Worktree created) {
        boolean known = false;
        for (int i = 0; i < worktrees.size(); i++) {
            if (worktrees.get(i).id.equals(created.id)) {
                worktrees.set(i, created);
                known = true;
                break;
            }
        }
        if (!known) worktrees.add(0, created);
        // The list behind the dialog counts them in its Worktrees row.
        if (lastSessions != null) renderList(lastSessions, null);
    }

    /** A field-shaped tap target that opens a chooser — the picker's look. */
    private TextView selector(String label) {
        TextView t = Widgets.text(this, label, Theme.INK, 15, false);
        t.setBackground(Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        t.setPadding(p, 0, p, 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinimumHeight(Theme.dp(this, 44));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setLayoutParams(lp(MATCH, WRAP));
        t.setClickable(true);
        return t;
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
        return Widgets.fieldLabel(this, s);
    }

    private EditText field(String hint, int inputType, boolean mono) {
        return Widgets.field(this, hint, inputType, mono);
    }

    private View spacer(int dp) {
        return Widgets.spacer(this, dp);
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

    /**
     * What the picker offers: what the server said it can run, or the built-in
     * list while that is in flight and against a server that cannot answer.
     * Never empty, so callers can index it.
     */
    private List<Agent> agentChoices() {
        return agents.isEmpty() ? Agent.FALLBACK : agents;
    }

    private String formatAgent(String agent) {
        return Agent.label(agentChoices(), agent);
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
