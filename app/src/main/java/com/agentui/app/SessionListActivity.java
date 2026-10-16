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
import android.widget.Button;
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

    private Api api;
    private Prefs prefs;
    private TextView sessionCount;
    private SessionVisibilityControl visibility;
    private LinearLayout listContainer;
    private ForegroundPoller statusPoller;
    private CatalogLoader loader;
    private final RefreshController agentRefresh = new RefreshController();
    private CatalogRepository catalogs;
    private final KeyedCards<Session, SessionCards.Card> sessionCards = new KeyedCards<>();
    private View retainedWorktreeRow;
    private TextView worktreeCount;

    /** Working directory this list is scoped to, or null when unscoped. */
    private String projectDir;
    private String projectName;
    /** The project's server-side id; empty against a server that has none. */
    private String projectId;
    /** Whether the project directory is a git repo, so worktrees are on offer. */
    private boolean projectIsRepo;
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
     * The agents this server can run, with their model catalogs, filled from
     * {@code GET /agents}. Empty until it lands, and after a failed fetch —
     * {@link #agentsError} then says why, and no session can be created until
     * the server answers. The pickers in the new-session dialog are built from
     * it, and the cards label a session's agent and model through it.
     */
    private final List<Agent> agents = new ArrayList<>();
    private boolean agentsLoading;
    private String agentsError;
    private boolean openNewAfterAgentsLoad;
    /** The sessions currently on screen, so a worktree load can re-render them. */
    private List<Session> lastSessions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        catalogs = new CatalogRepository(api);
        loader = new CatalogLoader(catalogs);
        prefs = api.prefs();
        statusPoller = new ForegroundPoller(3000, this::pollSessionStatuses);
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
            if (lastSessions != null) renderList(lastSessions, null);
            loadSessions();
            statusPoller.start();
        }
    }

    @Override
    protected void onPause() {
        statusPoller.stop();
        loader.cancel();
        agentRefresh.cancel();
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

        TextView newBtn = Widgets.primaryButton(this, "+");
        newBtn.setPadding(0, 0, 0, 0);
        newBtn.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        newBtn.setGravity(Gravity.CENTER);
        newBtn.setContentDescription("New session");
        newBtn.setOnClickListener(v -> showNewSessionDialog());
        topbar.addView(newBtn);

        root.addView(topbar);
        root.addView(divider());

        visibility = new SessionVisibilityControl(this, prefs, () -> {
            if (lastSessions != null) renderList(lastSessions, null);
        });
        root.addView(visibility);

        // ---- scrolling list ----
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        scroll.setFillViewport(true);
        listContainer = Widgets.column(this);
        listContainer.setPadding(pad, Theme.dp(this, 8), pad, pad);
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

    private String dataIdentity;

    private void loadSessions() {
        if (!catalogs.identity().equals(dataIdentity)) {
            loader.cancel();
            agentRefresh.cancel();
            dataIdentity = catalogs.identity();
            lastSessions = null;
            existence = DirectorySnapshot.unknown();
            worktrees.clear();
            worktreesSupported = true;
            clearCards();
        }
        // A stopped Activity normally retains its cards. Do not decode cached
        // catalogs or rebuild those cards before the back transition can draw.
        if (hasPopulatedData()) {
            // Retry an interrupted/failed initial agent load. Otherwise keep the
            // labels already used by the cards.
            if (agents.isEmpty()) loadAgents(false);
            refreshCatalogs(true);
            return;
        }
        sessionCount.setText("…");
        // Do not carry one server's registry into another after Settings changes
        // the address. Cards show raw ids until the fresh list lands.
        agents.clear();
        agentsError = null;
        loadAgents(true);
        loader.restoreSessions(worktreeProjectPath(), sessionUpdates(true));
    }

    private DirectorySnapshot existence = DirectorySnapshot.unknown();

    private boolean hasPopulatedData() { return lastSessions != null; }

    private void pollSessionStatuses() { refreshCatalogs(false); }

    private String worktreeProjectPath() { return projectIsRepo && worktreesSupported ? projectDir : null; }

    private void refreshCatalogs(boolean reportError) {
        loader.refreshSessions(worktreeProjectPath(), reportError, sessionUpdates(reportError));
    }

    private CatalogLoader.Listener sessionUpdates(boolean reportError) {
        return new CatalogLoader.Listener() {
            @Override public void cached(CatalogRepository.CachedCatalog cached) {
                if (cached.existence != null) existence = cached.existence;
                if (cached.worktrees != null) {
                    worktrees.clear();
                    worktrees.addAll(cached.worktrees);
                }
                if (cached.sessions != null) renderList(scopeToProject(cached.sessions), null);
                else if (hasPopulatedData()) renderList(lastSessions, null);
            }
            @Override public void sessions(List<Session> sessions) {
                // Rebind fresh action models; unchanged views remain attached.
                renderList(scopeToProject(sessions), null);
            }
            @Override public void worktrees(List<Worktree> fresh) {
                boolean changed = worktreeRowsChanged(fresh);
                worktrees.clear();
                worktrees.addAll(fresh);
                if (changed && hasPopulatedData()) renderList(lastSessions, null);
            }
            @Override public void onResult(CatalogSnapshot snapshot) {
                boolean changed = !existence.paths.equals(snapshot.existence.paths)
                        || (worktreesSupported && !snapshot.worktreesSupported);
                worktreesSupported &= snapshot.worktreesSupported;
                existence = snapshot.existence;
                if (changed && hasPopulatedData()) renderList(lastSessions, null);
            }
            @Override public void onError(String message) {
                if (reportError && !hasPopulatedData()) renderList(null, message);
            }
        };
    }

    private boolean worktreeRowsChanged(List<Worktree> fresh) {
        if (worktrees.size() != fresh.size()) return true;
        for (int i = 0; i < fresh.size(); i++) {
            Worktree before = worktrees.get(i), after = fresh.get(i);
            if (!before.id.equals(after.id) || !before.path.equals(after.path)
                    || !before.branch.equals(after.branch) || before.sessionCount != after.sessionCount) return true;
        }
        return false;
    }

    /**
     * The agents this server can run, with their model catalogs. Fixed from
     * server startup, so retained with the populated list on return. A server
     * identity change clears them and loads the new server's catalog.
     * A failure is kept for the new-session dialog, which refuses to
     * create without an agent and model; an unreachable server is already
     * being reported by the session list itself.
     */
    private void loadAgents(boolean useCache) {
        agentsLoading = true;
        agentRefresh.start(false, run -> {
            Api.Cb<List<Agent>> callback = run.finish(new Api.Cb<List<Agent>>() {
                @Override public void onResult(List<Agent> list) {
                    agents.clear();
                    agents.addAll(list);
                    agentsError = null;
                    agentsLoading = false;
                    // Cards label the agent and model through this, and are often
                    // on screen by the time it lands.
                    if (lastSessions != null) renderList(lastSessions, null);
                    openPendingNewSession();
                }

                @Override public void onError(String message) {
                    agentsError = message;
                    agentsLoading = false;
                    openPendingNewSession();
                }
            });
            if (useCache) catalogs.agents(callback);
            else api.listAgents(callback);
        });
    }

    private void openPendingNewSession() {
        if (!openNewAfterAgentsLoad || agentsLoading) return;
        openNewAfterAgentsLoad = false;
        showNewSessionDialog();
    }

    private void loadWorktrees() { refreshCatalogs(false); }

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
        lastSessions = null;
        sessionCount.setText("not configured");
        clearCards();
        LinearLayout box = emptyBox("No server set.\nTap to configure the server address.");
        box.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        listContainer.addView(box);
    }

    private void clearCards() {
        sessionCards.clear();
        retainedWorktreeRow = null;
        worktreeCount = null;
        listContainer.removeAllViews();
    }

    private void renderList(List<Session> sessions, String error) {
        if (error != null) {
            clearCards();
            lastSessions = null;
            sessionCount.setText("unreachable");
            listContainer.addView(emptyBox(error + "\n\nCheck the server address in Settings."));
            return;
        }

        lastSessions = sessions;
        visibility.refresh();
        List<Session> visible = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Session session : sessions) {
            if (SessionVisibility.visible(session, visibility.cutoff(), "", now)) visible.add(session);
        }
        int hidden = sessions.size() - visible.size();
        int n = visible.size();
        String count = n + (n == 1 ? " session" : " sessions") + (hidden > 0 ? " · " + hidden + " hidden" : "");
        Widgets.bindText(sessionCount, count);

        List<View> rows = new ArrayList<>();
        if (projectDir != null && projectIsRepo && worktreesSupported) {
            if (retainedWorktreeRow == null) retainedWorktreeRow = worktreeRow();
            Widgets.bindText(worktreeCount, worktrees.isEmpty() ? "none" : String.valueOf(worktrees.size()));
            rows.add(retainedWorktreeRow);
        }
        rows.addAll(sessionCards.bind(visible, s -> s.id, this::sessionCard,
                (card, s) -> card.bind(s, projectDir, agents, existence)));
        if (n == 0) rows.add(emptyBox(hidden > 0
                ? "No sessions within this cutoff.\nIncrease the days or switch off the filter."
                : projectDir != null ? "No sessions yet.\nTap + to start one." : "No sessions yet"));
        CardViews.reconcile(listContainer, rows);
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
                WorktreeListActivity.intent(this, projectDir, projectName)));

        TextView label = Widgets.text(this, "Worktrees", Theme.INK, 14, true);
        label.setLayoutParams(lp(0, WRAP, 1f));
        row.addView(label);
        int n = worktrees.size();
        worktreeCount = Widgets.text(this, n == 0 ? "none" : String.valueOf(n), Theme.MUTED, 13, false);
        row.addView(worktreeCount);
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

    private SessionCards.Card sessionCard(Session s) {
        return new SessionCards.Card(this, this::openSession, this::showSessionMenu);
    }

    private void openSession(Session s) {
        startActivity(SessionActivity.intent(this, s, projectDir));
    }

    /**
     * The long-press menu. Copying the id comes first so it can be handed to
     * another agent's session tools; delete repeats what the
     * settings screen and the card's own bin already offer.
     */
    private void showSessionMenu(Session s) {
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(new String[] {"Copy session ID", "Delete…"}, (d, which) -> {
                    if (which == 0) Widgets.copyId(this, s.id, "Session");
                    else confirmDelete(s);
                })
                .show();
    }

    /* ---------------------------------------------------------------- */
    /* project settings                                                 */
    /* ---------------------------------------------------------------- */

    private void openProjectSettings() {
        Intent i = new Intent(this, ProjectSettingsActivity.class);
        i.putExtra(ProjectSettingsActivity.EXTRA_PROJECT_DIR, projectDir);
        i.putExtra(ProjectSettingsActivity.EXTRA_PROJECT_NAME, projectName);
        startActivity(i);
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
                    WorktreeListActivity.intent(this, projectDir, projectName)));
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
        // Wait for the in-flight registry request, which carries the model
        // catalogs too, so both pickers are complete when the dialog opens.
        if (agentsLoading) {
            openNewAfterAgentsLoad = true;
            return;
        }
        if (agentsError != null) {
            toast("Agent list unavailable: " + agentsError);
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
        final List<Agent> choices = new ArrayList<>(agents);
        final CharSequence[] agentLabels = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) agentLabels[i] = choices.get(i).name;
        final int[] agentIdx = {Agent.defaultIndex(choices, prefs.defaultAgent())};
        android.widget.Switch sandbox = new android.widget.Switch(this);
        sandbox.setText("Sandbox");
        sandbox.setTextColor(Theme.INK);
        sandbox.setChecked(true);
        LinearLayout sandboxSection = Widgets.column(this);
        sandboxSection.addView(sandbox);
        sandboxSection.addView(Widgets.text(this,
                "Restricts agent file access. Applies to agent turns, not direct shell commands.",
                Theme.MUTED, 12.5f, false));
        // ---- model ----
        // Scoped to the chosen agent and always explicit: the first model in
        // its catalog is preselected, and any switch of agent starts over from
        // that agent's first, since model ids mean nothing across harnesses.
        // Null only while the agent has no usable catalog, which blocks Create.
        final String[] modelId = {null};
        // ---- reasoning ----
        // The chosen model's own levels, behind a "Default" that sends null and
        // leaves the choice to the harness. Any change of model starts over
        // from Default; hidden for a model with no levels.
        final String[] reasoningLevel = {null};
        LinearLayout reasoningSection = Widgets.column(this);
        TextView reasoningPicker = selector(Model.reasoningLabel(null));
        reasoningSection.addView(spacer(14));
        reasoningSection.addView(fieldLabel("Reasoning"));
        reasoningSection.addView(reasoningPicker);
        final Runnable showReasoning = () -> {
            Agent chosen = choices.get(agentIdx[0]);
            Model m = modelId[0] == null ? null : chosen.model(modelId[0]);
            reasoningLevel[0] = null;
            reasoningPicker.setText(Model.reasoningLabel(null));
            if (m == null || m.reasoningLevels.isEmpty()) {
                reasoningSection.setVisibility(View.GONE);
                reasoningPicker.setOnClickListener(null);
                return;
            }
            reasoningSection.setVisibility(View.VISIBLE);
            reasoningPicker.setOnClickListener(v -> {
                CharSequence[] labels = new CharSequence[m.reasoningLevels.size() + 1];
                labels[0] = Model.reasoningLabel(null);
                int checked = 0;
                for (int i = 0; i < m.reasoningLevels.size(); i++) {
                    labels[i + 1] = m.reasoningLevels.get(i);
                    if (m.reasoningLevels.get(i).equals(reasoningLevel[0])) checked = i + 1;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Reasoning")
                        .setSingleChoiceItems(labels, checked, (d, which) -> {
                            reasoningLevel[0] = which == 0 ? null : m.reasoningLevels.get(which - 1);
                            reasoningPicker.setText(labels[which]);
                            d.dismiss();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        };
        // Create is enabled only with a model chosen and no request in flight.
        final Button[] create = {null};
        final boolean[] creating = {false};
        final Runnable refreshCreate = () -> {
            if (create[0] != null) create[0].setEnabled(modelId[0] != null && !creating[0]);
        };
        TextView modelPicker = selector("");
        TextView modelHint = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        Widgets.margins(modelHint, 0, Theme.dp(this, 7), 0, 0);
        showModelChoices(choices.get(agentIdx[0]), modelPicker, modelHint, modelId, showReasoning);

        TextView agent = selector(choices.get(agentIdx[0]).name);
        agent.setOnClickListener(av -> new AlertDialog.Builder(this)
                .setTitle("Agent")
                .setSingleChoiceItems(agentLabels, agentIdx[0], (d, which) -> {
                    int previous = agentIdx[0];
                    agentIdx[0] = which;
                    if (which != previous) {
                        showModelChoices(choices.get(which), modelPicker, modelHint, modelId,
                                showReasoning);
                        refreshCreate.run();
                    }
                    agent.setText(agentLabels[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show());
        content.addView(agent);
        content.addView(sandboxSection);

        content.addView(spacer(14));
        content.addView(fieldLabel("Model"));
        content.addView(modelPicker);
        content.addView(modelHint);
        content.addView(reasoningSection);

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

        dialog.setOnShowListener(d -> {
            create[0] = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            refreshCreate.run();
            create[0].setOnClickListener(v -> {
                String name = nameField.getText().toString().trim();
                if (name.isEmpty()) {
                    toast("Name is required");
                    return;
                }
                String dir = projectDir != null ? projectDir : fallbackDir(name);

                creating[0] = true;
                refreshCreate.run();
                api.createSession(name, dir, choices.get(agentIdx[0]).id, modelId[0],
                        reasoningLevel[0], worktreeId[0],
                        sandbox.isChecked(),
                        new Api.StatusCb<Session>() {
                            @Override public void onResult(Session session) {
                                api.prefs().setNotify(session.id, true);
                                dialog.dismiss();
                                openSession(session);
                            }

                            @Override public void onError(String message) {
                                creating[0] = false;
                                refreshCreate.run();
                                toast("Unable to create: " + message);
                            }

                            @Override public void onHttpError(int code, String message) {
                                creating[0] = false;
                                refreshCreate.run();
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
                                // A refused model (400, 503) is shown as is: the
                                // server's catalog or harness needs fixing, and no
                                // other model is silently substituted.
                            }
                        });
            });
        });
        dialog.show();
    }

    /* ---------------------------------------------------------------- */
    /* model picker                                                     */
    /* ---------------------------------------------------------------- */

    /**
     * Point the model picker at one agent's catalog and select its first model.
     * Without a usable catalog nothing is selected, the picker is disabled and
     * the reason is shown underneath; the caller then keeps Create disabled.
     * {@code onModel} runs after every change of {@code modelId}.
     */
    private void showModelChoices(Agent agent, TextView picker, TextView hint, String[] modelId,
                                  Runnable onModel) {
        String unavailable = agent.unavailable();
        if (unavailable != null) {
            modelId[0] = null;
            onModel.run();
            picker.setText("Unavailable");
            picker.setEnabled(false);
            picker.setTextColor(Theme.MUTED);
            picker.setOnClickListener(null);
            hint.setText(unavailable);
            hint.setTextColor(Theme.DANGER);
            return;
        }
        List<Model> models = agent.models;
        modelId[0] = models.get(0).id;
        onModel.run();
        picker.setText(models.get(0).name);
        picker.setEnabled(true);
        picker.setTextColor(Theme.INK);
        hint.setText("Fixed for the session's life.");
        hint.setTextColor(Theme.MUTED);
        picker.setOnClickListener(v -> {
            CharSequence[] labels = new CharSequence[models.size()];
            int checked = 0;
            for (int i = 0; i < labels.length; i++) {
                Model m = models.get(i);
                labels[i] = m.name;
                if (m.id.equals(modelId[0])) checked = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Model")
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        if (!models.get(which).id.equals(modelId[0])) {
                            modelId[0] = models.get(which).id;
                            onModel.run();
                        }
                        picker.setText(labels[which]);
                        d.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
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
        List<Worktree> choices = new ArrayList<>(worktrees);
        for (int i = 0; i < choices.size(); i++) {
            Worktree w = choices.get(i);
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
                    Worktree w = choices.get(which - 1);
                    if (existence.missing(w.path)) confirmMissingDirectory(w, picker, worktreeId);
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
    private String pickerLabel(Worktree w) {
        String label = w.path;
        String subtitle = w.subtitle();
        if (existence.missing(w.path)) {
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
