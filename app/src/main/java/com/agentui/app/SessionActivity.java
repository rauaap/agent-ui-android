package com.agentui.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * A single agent session: live transcript over a WebSocket plus a composer.
 * Mirrors the web "view-session" and its message protocol.
 */
public class SessionActivity extends Activity {

    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_AGENT = "agent";
    static final String EXTRA_DIR = "dir";
    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_WORKTREE_ID = "worktree_id";
    static final String EXTRA_WORKTREE = "worktree"; // legacy caller compatibility
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_AUTO_WRITE = "auto_write";
    static final String EXTRA_AUTO_COMMAND = "auto_command";
    static final String EXTRA_ARCHIVED = "archived";

    private static final int REQ_SETTINGS = 2;

    private Api api;
    private String sessionId;
    private String sessionName;
    private String sessionAgent = "";
    private final List<Agent> agents = new ArrayList<>(Agent.FALLBACK);
    private final List<TextView> agentLabelViews = new ArrayList<>();
    private String status = "idle";
    private String workingDir;
    private String projectId = "";
    private String projectDir;
    private String worktreeId;
    private boolean notifyOn;
    // Cached per-session auto-approve toggles, kept fresh from `settings` events
    // and the settings screen, and handed to the settings screen on open.
    private boolean autoApproveWrite;
    private boolean autoApproveCommand;

    // views
    private LinearLayout transcript;
    private ScrollView scroll;
    private TextView scrollDownBtn;
    private LinearLayout statusHolder;
    private TextView nameView;
    private LinearLayout locationHolder;
    private TextView stopBtn;
    private TextView activity;
    private EditText input;
    private TextView sendBtn;
    private LinearLayout composerBox; // the bordered frame around input + send
    private boolean bashMode;         // the composer is showing command styling
    /**
     * Archived sessions are read-only: the transcript replays as usual, but the
     * server refuses prompts and shell commands, so the composer is swapped for
     * a notice rather than left to collect text that can only bounce.
     */
    private boolean archived;
    private LinearLayout composerHolder; // swaps between the composer and that notice

    // socket
    private WebSocket socket;
    private boolean active = true;
    private int reconnectAttempt = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;

    // transcript state
    private TextView agentBubble;     // coalesce consecutive output chunks
    private StringBuilder agentRaw;   // raw markdown backing the current bubble
    private View pendingApprovalCard;
    private String pendingApprovalId;
    // a pending AskUserQuestion card (multiple-choice prompt) awaiting the user's
    // pick — tracked separately from approvals, which gate whether a tool runs
    private View pendingQuestionCard;
    private String pendingQuestionId;
    // The most recently rendered canonical tool call. Only an immediately
    // eligible approval carrying this exact opaque call ID may replace it.
    private View lastToolCard;
    private String lastToolCallId;
    // The bash card still waiting for its output. A command runs alongside the
    // agent, so its result can arrive several messages after the echo that
    // opened the card — the card is rebuilt in place rather than appended.
    private View pendingBashCard;
    private String pendingBashCommand;
    // The first connection renders its replay straight into the visible transcript
    // (progressive, nothing to preserve). A reconnect instead assembles the replay
    // into a detached buffer and swaps it in only once it's complete, so a slow
    // reconnect keeps the old scrollback on screen instead of blanking it.
    private boolean firstConnect = true;
    private boolean awaitingReplay;   // true while buffering a reconnect's replay
    private LinearLayout replayBuffer; // detached build target during a reconnect

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        sessionId = getIntent().getStringExtra(EXTRA_ID);
        sessionName = getIntent().getStringExtra(EXTRA_NAME);
        sessionAgent = getIntent().getStringExtra(EXTRA_AGENT);
        if (sessionAgent == null) sessionAgent = "";
        workingDir = getIntent().getStringExtra(EXTRA_DIR);
        if (workingDir == null) workingDir = "";
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        worktreeId = getIntent().getStringExtra(EXTRA_WORKTREE_ID);
        if (worktreeId == null) {
            worktreeId = getIntent().getBooleanExtra(EXTRA_WORKTREE, false) ? "legacy" : "";
        }
        status = getIntent().getStringExtra(EXTRA_STATUS);
        if (status == null) status = "idle";
        autoApproveWrite = getIntent().getBooleanExtra(EXTRA_AUTO_WRITE, false);
        autoApproveCommand = getIntent().getBooleanExtra(EXTRA_AUTO_COMMAND, false);
        archived = getIntent().getBooleanExtra(EXTRA_ARCHIVED, false);
        notifyOn = api.prefs().notifyEnabled(sessionId);

        setContentView(buildRoot(sessionName));
        applyArchived(archived);
        applyStatus(status);
        connect();
        refreshSessionMetadata();
        loadAgents();
    }

    /** Load the server's display name for the adapter used by this session. */
    private void loadAgents() {
        api.listAgents(new Api.Cb<List<Agent>>() {
            @Override public void onResult(List<Agent> list) {
                if (!list.isEmpty()) {
                    agents.clear();
                    agents.addAll(list);
                }
                updateAgentLabels();
            }

            @Override public void onError(String message) {
                // Keep the older-server fallback; the raw id is used for anything unknown.
                updateAgentLabels();
            }
        });
    }

    private String agentChatLabel() {
        String label = sessionAgent.isEmpty() ? "Agent" : Agent.label(agents, sessionAgent);
        return label.toUpperCase(Locale.ROOT);
    }

    private void updateAgentLabels() {
        String label = agentChatLabel();
        for (TextView view : agentLabelViews) view.setText(label);
    }

    /**
     * The detach event is not replayed on WebSocket connect. REST therefore
     * refreshes effective cwd/worktree metadata, especially for notification
     * intents that carry only an id and name.
     */
    private void refreshSessionMetadata() {
        if (sessionId == null || sessionId.isEmpty()) return;
        api.listSessions(new Api.Cb<java.util.List<Session>>() {
            @Override public void onResult(java.util.List<Session> sessions) {
                for (Session session : sessions) {
                    if (!session.id.equals(sessionId)) continue;
                    sessionName = session.name;
                    nameView.setText(session.name);
                    sessionAgent = session.agent;
                    updateAgentLabels();
                    projectId = session.projectId;
                    workingDir = session.workingDir;
                    worktreeId = session.worktreeId;
                    autoApproveWrite = session.autoApproveWrite;
                    autoApproveCommand = session.autoApproveCommand;
                    applyArchived(session.isArchived());
                    applyStatus(session.status);
                    if (projectDir == null && !projectId.isEmpty()) loadProjectPath();
                    else renderLocation();
                    return;
                }
            }
            @Override public void onError(String message) {
                // The socket and intent metadata still leave the transcript usable.
            }
        });
    }

    private void loadProjectPath() {
        api.listProjects(new Api.StatusCb<java.util.List<Project>>() {
            @Override public void onResult(java.util.List<Project> projects) {
                for (Project project : projects) {
                    if (project.id.equals(projectId)) {
                        projectDir = project.path;
                        break;
                    }
                }
                renderLocation();
            }
            @Override public void onError(String message) {}
            @Override public void onHttpError(int code, String message) {}
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // While this session is on screen, suppress its completion notifications.
        WatchService.setViewing(sessionId);
    }

    @Override
    protected void onStop() {
        WatchService.clearViewing(sessionId);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        active = false;
        cancelReconnect();
        if (socket != null) {
            socket.close(1000, null);
            socket = null;
        }
        super.onDestroy();
    }

    /* ---------------------------------------------------------------- */
    /* layout                                                           */
    /* ---------------------------------------------------------------- */

    private View buildRoot(String name) {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);

        // ---- header ----
        LinearLayout header = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        header.setPadding(pad, pad, pad, pad);

        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);

        LinearLayout headings = Widgets.column(this);
        headings.setLayoutParams(lp(0, WRAP, 1f));
        nameView = Widgets.text(this, name == null ? "" : name, Theme.INK, 17, true);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headings.addView(nameView);
        // The effective cwd remains the former worktree path after an explicit
        // detach; worktree_id null must not make that look like the project root.
        locationHolder = Widgets.row(this);
        renderLocation();
        Widgets.margins(locationHolder, 0, Theme.dp(this, 2), 0, 0);
        headings.addView(locationHolder);
        header.addView(headings);

        ImageView gearBtn = new ImageView(this);
        gearBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gearBtn.setImageResource(R.drawable.ic_gear);
        gearBtn.setColorFilter(Theme.MUTED);
        int gearPad = Theme.dp(this, 10);
        gearBtn.setPadding(gearPad, gearPad, gearPad, gearPad);
        gearBtn.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        gearBtn.setClickable(true);
        gearBtn.setOnClickListener(v -> openSettings());
        header.addView(gearBtn);

        statusHolder = Widgets.row(this);
        Widgets.margins(statusHolder, Theme.dp(this, 8), 0, 0, 0);
        header.addView(statusHolder);

        stopBtn = Widgets.dangerButton(this, "Stop");
        Widgets.margins(stopBtn, Theme.dp(this, 8), 0, 0, 0);
        stopBtn.setOnClickListener(v -> {
            if (sessionId != null) api.stopSession(sessionId, new Api.Cb<Void>() {
                @Override public void onResult(Void value) {}
                @Override public void onError(String message) {}
            });
        });
        header.addView(stopBtn);

        root.addView(header);
        View div = new View(this);
        div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        div.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(div);

        // ---- transcript ----
        // A FrameLayout lets the "scroll to bottom" button float over the scrollback.
        FrameLayout scrollArea = new FrameLayout(this);
        scrollArea.setLayoutParams(lp(MATCH, 0, 1f));

        scroll = new ScrollView(this);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        scroll.setFillViewport(true);
        transcript = newTranscript();
        scroll.addView(transcript);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateScrollButton());
        // The keyboard opening (or the composer growing) takes height off the bottom
        // of the transcript. A ScrollView keeps its scroll offset through that, which
        // pushes whatever you were reading down behind the keyboard, so shift the
        // scroll by the same amount to hold the bottom edge of the scrollback still.
        scroll.addOnLayoutChangeListener((v, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            int shrink = (oldBottom - oldTop) - (bottom - top);
            if (shrink != 0) scroll.scrollBy(0, shrink);
            updateScrollButton();
        });
        scrollArea.addView(scroll);

        // Floating down arrow, shown only when the transcript can scroll further down.
        scrollDownBtn = Widgets.text(this, "↓", Theme.INK, 20, false);
        scrollDownBtn.setGravity(Gravity.CENTER);
        scrollDownBtn.setBackground(Theme.pill(this, Theme.PANEL2, Theme.LINE, 1));
        scrollDownBtn.setElevation(Theme.dp(this, 4));
        scrollDownBtn.setVisibility(View.GONE);
        scrollDownBtn.setOnClickListener(v -> scrollToBottom());
        int sdSize = Theme.dp(this, 40);
        FrameLayout.LayoutParams sdLp = new FrameLayout.LayoutParams(sdSize, sdSize);
        sdLp.gravity = Gravity.BOTTOM | Gravity.END;
        int sdMargin = Theme.dp(this, 12);
        sdLp.setMargins(0, 0, sdMargin, sdMargin);
        scrollDownBtn.setLayoutParams(sdLp);
        scrollArea.addView(scrollDownBtn);

        root.addView(scrollArea);

        // ---- composer ----
        LinearLayout footer = Widgets.column(this);
        footer.setPadding(pad, Theme.dp(this, 8), pad, pad);

        activity = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        Widgets.margins(activity, Theme.dp(this, 6), 0, 0, Theme.dp(this, 8));
        footer.addView(activity);

        LinearLayout composer = Widgets.row(this);
        composerBox = composer;
        composer.setGravity(Gravity.BOTTOM);
        composer.setBackground(Theme.rounded(this, Theme.PANEL, 18, Theme.LINE, 1));
        int cp = Theme.dp(this, 8);
        composer.setPadding(cp, cp, cp, cp);

        input = new EditText(this);
        input.setHint("Message the agent…");
        input.setTextColor(Theme.INK);
        input.setHintTextColor(Theme.FAINT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(null);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setInputType(promptInputType());
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                applyComposerMode();
            }
        });
        input.setMaxLines(6);
        input.setMinHeight(Theme.dp(this, 44));
        int ip = Theme.dp(this, 8);
        input.setPadding(ip, ip, ip, ip);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendPrompt(); return true; }
            return false;
        });
        input.setLayoutParams(lp(0, WRAP, 1f));
        composer.addView(input);

        sendBtn = Widgets.primaryButton(this, "↑");
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams sendLp = lp(Theme.dp(this, 44), Theme.dp(this, 44));
        sendLp.leftMargin = Theme.dp(this, 8);
        sendBtn.setLayoutParams(sendLp);
        sendBtn.setOnClickListener(v -> sendPrompt());
        composer.addView(sendBtn);

        composerHolder = Widgets.column(this);
        composerHolder.addView(composer);
        footer.addView(composerHolder);
        root.addView(footer);

        return root;
    }

    private void renderLocation() {
        if (locationHolder == null) return;
        locationHolder.removeAllViews();
        if (!worktreeId.isEmpty()) {
            TextView tag = Widgets.tag(this, "worktree", Theme.INFO);
            Widgets.margins(tag, 0, 0, Theme.dp(this, 8), 0);
            locationHolder.addView(tag);
        } else if (projectDir != null && !workingDir.equals(projectDir)) {
            TextView tag = Widgets.tag(this, "former worktree", Theme.MUTED);
            Widgets.margins(tag, 0, 0, Theme.dp(this, 8), 0);
            locationHolder.addView(tag);
        }
        TextView dirView = Widgets.mono(this, workingDir, Theme.FAINT, 12);
        dirView.setMaxLines(1);
        dirView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        dirView.setLayoutParams(lp(0, WRAP, 1f));
        locationHolder.addView(dirView);
    }

    /* ---------------------------------------------------------------- */
    /* archived                                                         */
    /* ---------------------------------------------------------------- */

    /**
     * Show the composer, or — when the session is archived — the reason it is
     * gone and the one action that brings it back. Authoritative and idempotent,
     * like {@link #applyStatus}: it is driven by the {@code archived} event,
     * which the server also sends on connect, so another device's archive
     * arrives here without anything being polled.
     */
    private void applyArchived(boolean value) {
        archived = value;
        if (composerHolder == null) return;
        composerHolder.removeAllViews();
        if (!archived) {
            composerHolder.addView(composerBox);
            return;
        }

        // Whatever was half-typed can't be sent now; keeping the keyboard open
        // over a dead composer would be the only thing worse than losing it.
        hideKeyboard();

        LinearLayout box = Widgets.row(this);
        box.setBackground(Theme.rounded(this, Theme.PANEL, 18, Theme.LINE, 1));
        int p = Theme.dp(this, 14);
        box.setPadding(p, p, p, p);
        TextView text = Widgets.text(this,
                "This session is archived. Unarchive to continue working in it.",
                Theme.MUTED, 13, false);
        text.setLayoutParams(lp(0, WRAP, 1f));
        box.addView(text);
        TextView button = Widgets.ghostButton(this, "Unarchive");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        Widgets.margins(button, Theme.dp(this, 10), 0, 0, 0);
        button.setOnClickListener(this::unarchive);
        box.addView(button);
        composerHolder.addView(box);
    }

    private void unarchive(View button) {
        button.setEnabled(false);
        api.setSessionArchived(sessionId, false, new Api.StatusCb<Session>() {
            @Override public void onResult(Session session) {
                // The server unarchives the project along with it, so the lists
                // behind this screen are stale too — they reload on resume.
                applyArchived(session.isArchived());
            }
            @Override public void onError(String message) {
                button.setEnabled(true);
                android.widget.Toast.makeText(SessionActivity.this,
                        "Couldn't unarchive: " + message,
                        android.widget.Toast.LENGTH_LONG).show();
            }
            @Override public void onHttpError(int code, String message) {
                button.setEnabled(true);
                if (code == 409) showMissingWorkingDirectory(message);
                else onError(message);
            }
        });
    }

    private void showMissingWorkingDirectory(String detail) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Working directory unavailable")
                .setMessage(detail + "\n\nRecreate a directory at the same absolute path, then "
                        + "try unarchiving again:\n\n" + workingDir)
                .setPositiveButton("OK", null)
                .show();
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /* ---------------------------------------------------------------- */
    /* composer mode                                                    */
    /* ---------------------------------------------------------------- */

    private static int promptInputType() {
        return android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    }

    /**
     * A command is not prose: sentence capitalisation turns {@code ls} into
     * {@code Ls} and the suggestion strip is noise over a path. Bash mode drops
     * both. NO_SUGGESTIONS is only a hint — Gboard and AOSP honour it, some
     * third-party IMEs ignore it — but losing CAP_SENTENCES is universal, and
     * that is the flag that actually corrupts commands.
     */
    private static int bashInputType() {
        return android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    /**
     * Say what pressing send will do. A `!` line goes to the shell, not to the
     * agent, so the composer turns red and switches to a monospace, autocorrect-
     * free keyboard while one is being typed.
     *
     * Only fires on the transition. Reapplying the input type on every keystroke
     * would restart the IME under the user's fingers.
     */
    private void applyComposerMode() {
        boolean bash = Composer.isBash(input.getText().toString());
        if (bash == bashMode) return;
        bashMode = bash;

        composerBox.setBackground(Theme.rounded(
                this, Theme.PANEL, 18, bash ? Theme.DANGER_LINE : Theme.LINE, 1));
        input.setHint(bash ? "Run a shell command…" : "Message the agent…");

        // setInputType resets the typeface and can move the cursor, so restore
        // both, then tell the running IME to pick the new flags up — without
        // restartInput a keyboard that is already open keeps autocorrecting.
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        input.setInputType(bash ? bashInputType() : promptInputType());
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTypeface(bash ? Typeface.MONOSPACE : Typeface.DEFAULT);
        int length = input.getText().length();
        input.setSelection(Math.min(Math.max(start, 0), length), Math.min(Math.max(end, 0), length));

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(input);
    }

    /* ---------------------------------------------------------------- */
    /* notifications                                                    */
    /* ---------------------------------------------------------------- */

    private void openSettings() {
        Intent i = new Intent(this, SessionSettingsActivity.class);
        i.putExtra(SessionSettingsActivity.EXTRA_ID, sessionId);
        i.putExtra(SessionSettingsActivity.EXTRA_NAME, sessionName);
        i.putExtra(SessionSettingsActivity.EXTRA_STATUS, status);
        i.putExtra(SessionSettingsActivity.EXTRA_AUTO_WRITE, autoApproveWrite);
        i.putExtra(SessionSettingsActivity.EXTRA_AUTO_COMMAND, autoApproveCommand);
        i.putExtra(SessionSettingsActivity.EXTRA_ARCHIVED, archived);
        i.putExtra(SessionSettingsActivity.EXTRA_WORKING_DIR, workingDir);
        i.putExtra(SessionSettingsActivity.EXTRA_PROJECT_DIR, projectDir);
        i.putExtra(SessionSettingsActivity.EXTRA_WORKTREE_ID, worktreeId);
        startActivityForResult(i, REQ_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SETTINGS) return;
        // Archived from settings: the session is no longer on the list this
        // screen was opened from, so close it and follow it back there. Staying
        // put and going read-only is the right answer for someone else's
        // archive, arriving over the socket — not for the one you just did.
        if (data != null
                && data.getBooleanExtra(SessionSettingsActivity.EXTRA_JUST_ARCHIVED, false)) {
            finish();
            return;
        }
        if (data != null) {
            applyArchived(data.getBooleanExtra(SessionSettingsActivity.EXTRA_ARCHIVED, archived));
        }
        // The notification opt-in lives in Prefs; re-read it after settings close.
        notifyOn = api.prefs().notifyEnabled(sessionId);
        if (data != null) {
            String newName = data.getStringExtra(SessionSettingsActivity.EXTRA_NAME);
            if (newName != null && !newName.isEmpty()) {
                sessionName = newName;
                nameView.setText(newName);
            }
            autoApproveWrite = data.getBooleanExtra(
                    SessionSettingsActivity.EXTRA_AUTO_WRITE, autoApproveWrite);
            autoApproveCommand = data.getBooleanExtra(
                    SessionSettingsActivity.EXTRA_AUTO_COMMAND, autoApproveCommand);
            String newWorkingDir = data.getStringExtra(SessionSettingsActivity.EXTRA_WORKING_DIR);
            String newWorktreeId = data.getStringExtra(SessionSettingsActivity.EXTRA_WORKTREE_ID);
            if (newWorkingDir != null) workingDir = newWorkingDir;
            if (newWorktreeId != null) worktreeId = newWorktreeId;
            renderLocation();
        }
        // Keep the watch in sync with the (possibly changed) name and opt-in.
        if (notifyOn && ("running".equals(status) || "awaiting_approval".equals(status))) {
            WatchService.watch(this, sessionId, sessionName, status);
        }
    }

    private void applyStatus(String s) {
        status = s;
        statusHolder.removeAllViews();
        statusHolder.addView(Widgets.statusBadge(this, s));

        boolean busy = "running".equals(s) || "awaiting_approval".equals(s);
        if (busy && notifyOn) {
            WatchService.watch(this, sessionId, sessionName, s);
        }
        // The composer stays live while the agent works: a `!` command never
        // takes the server's turn lock, so it can run mid-turn. A prompt sent
        // now is turned away in sendPrompt() with a toast instead.
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);

        if ("running".equals(s)) {
            activity.setVisibility(View.VISIBLE);
            activity.setText("● Working…");
            activity.setTextColor(Theme.RUNNING);
        } else if ("awaiting_approval".equals(s)) {
            activity.setVisibility(View.VISIBLE);
            // A pending question reuses this status; word it for the user's pick.
            boolean question = pendingQuestionCard != null && pendingApprovalCard == null;
            activity.setText(question ? "● Waiting for your answer" : "● Waiting for your approval");
            activity.setTextColor(Theme.AWAITING);
        } else {
            activity.setVisibility(View.GONE);
        }

        // Retire a still-open approval card if the turn ended.
        if (!"awaiting_approval".equals(s) && pendingApprovalCard != null) {
            finalizeApproval(pendingApprovalCard, null, null);
            pendingApprovalCard = null;
            pendingApprovalId = null;
        }
        // Likewise retire a still-open question card.
        if (!"awaiting_approval".equals(s) && pendingQuestionCard != null) {
            finalizeQuestion(pendingQuestionCard, null);
            pendingQuestionCard = null;
            pendingQuestionId = null;
        }
    }

    /* ---------------------------------------------------------------- */
    /* websocket                                                        */
    /* ---------------------------------------------------------------- */

    private void connect() {
        cancelReconnect();
        if (!active || sessionId == null) return;

        if (firstConnect) {
            // First open: stream the replay straight into the (empty) visible
            // transcript so content shows up progressively as it arrives.
            awaitingReplay = false;
        } else {
            // Reconnect (e.g. the socket dropped while we were backgrounded):
            // assemble the server's replay into a detached buffer and keep the
            // current scrollback on screen until it's fully rebuilt, then swap it
            // in atomically — see commitReplay(), driven from the end-of-replay
            // status message. A slow reconnect never blanks the transcript and
            // never shows it refilling row by row.
            awaitingReplay = true;
            replayBuffer = newTranscript();
            transcript = replayBuffer;
            agentBubble = null;
            agentRaw = null;
            pendingApprovalCard = null;
            pendingApprovalId = null;
            pendingQuestionCard = null;
            pendingQuestionId = null;
            pendingBashCard = null;
            pendingBashCommand = null;
            clearLastTool();
        }

        String url = api.prefs().wsBase() + "/ws/sessions/" + sessionId;
        Request req = new Request.Builder().url(url).build();
        socket = api.http().newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                runOnUiThread(() -> reconnectAttempt = 0);
            }
            @Override public void onMessage(WebSocket ws, String text) {
                // Ignore stragglers from a superseded socket so they can't trigger
                // the rebuild before the current socket's replay arrives.
                runOnUiThread(() -> { if (ws == socket) handleMessage(text); });
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                runOnUiThread(() -> scheduleReconnect(ws));
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                runOnUiThread(() -> scheduleReconnect(ws));
            }
        });
    }

    private void scheduleReconnect(WebSocket ws) {
        if (!active || ws != socket) return;
        long delay = Math.min(1000L * (1L << Math.min(reconnectAttempt, 4)), 10000L);
        reconnectAttempt++;
        reconnectRunnable = this::connect;
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    /** A fresh, empty transcript column styled like the live one. */
    private LinearLayout newTranscript() {
        LinearLayout t = Widgets.column(this);
        int pad = Theme.dp(this, 16);
        t.setPadding(pad, pad, pad, pad);
        return t;
    }

    /**
     * Swap a fully-rebuilt reconnect replay in for the old scrollback in one shot.
     * Driven by the end-of-replay status message so the visible transcript is
     * replaced only once the buffer is complete — never blanked mid-load.
     */
    private void commitReplay() {
        awaitingReplay = false;
        if (replayBuffer == null) return;
        scroll.removeAllViews();
        scroll.addView(replayBuffer);
        transcript = replayBuffer;
        replayBuffer = null;
        scrollToBottom();
    }

    private void handleMessage(String raw) {
        JSONObject msg;
        try {
            msg = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        // Once any data has arrived, future (re)connects buffer their replay
        // off-screen rather than rendering into the live transcript.
        firstConnect = false;
        String type = msg.optString("type", "");
        switch (type) {
            case "status":
                // The server sends this right after the replay finishes, so it's
                // the cue to swap a buffered reconnect in for the old scrollback.
                if (awaitingReplay) commitReplay();
                applyStatus(msg.optString("status", "idle"));
                break;
            case "archived":
                // Sent on every change and again on connect, so a session
                // archived from another device is caught even if it happened
                // while this client was offline. archived_at null means live.
                applyArchived(!msg.isNull("archived_at"));
                break;
            case "worktree_detached":
                // Not replayed on connect, but authoritative and safe to receive
                // more than once after an idempotent detach retry.
                worktreeId = "";
                workingDir = msg.optString("working_dir", workingDir);
                renderLocation();
                break;
            case "settings":
                // Toggles may change from another client; keep our cache fresh so
                // the settings screen opens with the right state.
                autoApproveWrite = msg.optBoolean("auto_approve_write", autoApproveWrite);
                autoApproveCommand = msg.optBoolean("auto_approve_command", autoApproveCommand);
                break;
            case "input":
                agentBubble = null;
                clearLastTool();
                addUserMessage(msg.optString("text", ""));
                break;
            case "output":
                clearLastTool();
                addAgentOutput(msg.optString("text", ""));
                break;
            case "tool_use":
                agentBubble = null;
                addToolUse(msg);
                break;
            case "approval_request":
                agentBubble = null;
                addApprovalRequest(msg);
                break;
            case "approval_response":
                resolveApproval(msg.optString("request_id", ""),
                        msg.optString("behavior", ""), msg.optString("message", null));
                break;
            case "question":
                agentBubble = null;
                clearLastTool();
                addQuestionRequest(msg);
                break;
            case "question_response":
                resolveQuestion(msg.optString("request_id", ""), msg.optJSONObject("answers"));
                break;
            // Bash mode: the echo opens a card, the result fills it in.
            case "bash_input":
                agentBubble = null;
                clearLastTool();
                addBashCommand(msg.optString("command", ""));
                break;
            case "bash_output":
                fillBashOutput(msg.optString("command", ""), msg);
                break;
            case "done":
                agentBubble = null;
                clearLastTool();
                break;
            case "error":
                agentBubble = null;
                clearLastTool();
                addError(msg.optString("message", "Unknown error"));
                break;
            default:
                break;
        }
    }

    /* ---------------------------------------------------------------- */
    /* transcript rendering                                             */
    /* ---------------------------------------------------------------- */

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = lp(MATCH, WRAP);
        p.bottomMargin = Theme.dp(this, 14);
        return p;
    }

    private void append(View v) {
        transcript.addView(v, rowParams());
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> {
            scroll.fullScroll(View.FOCUS_DOWN);
            updateScrollButton();
        });
    }

    /** Show the floating down arrow only while the transcript can scroll further. */
    private void updateScrollButton() {
        if (scrollDownBtn == null) return;
        scrollDownBtn.setVisibility(scroll.canScrollVertically(1) ? View.VISIBLE : View.GONE);
    }

    private void addUserMessage(String text) {
        LinearLayout wrap = Widgets.column(this);
        wrap.setGravity(Gravity.END);
        TextView bubble = Widgets.text(this, text, Theme.INK, 15, false);
        bubble.setTextIsSelectable(true);
        bubble.setBackground(Theme.rounded(this, Theme.ACCENT_SOFT, 16, Theme.ACCENT_LINE, 1));
        int p = Theme.dp(this, 12);
        bubble.setPadding(p + Theme.dp(this, 2), p, p + Theme.dp(this, 2), p);
        bubble.setLayoutParams(lp(WRAP, WRAP));
        wrap.addView(bubble);
        append(wrap);
    }

    private void addAgentOutput(String text) {
        if (text == null || text.isEmpty()) return;
        if (agentBubble != null) {
            if (agentRaw == null) agentRaw = new StringBuilder();
            agentRaw.append(text);
            agentBubble.setText(Markdown.render(this, agentRaw.toString(), Theme.INK));
            scrollToBottom();
            return;
        }
        // The raw markdown is kept verbatim: the bubble shows the rendered form,
        // but the Copy button (and any re-render as chunks stream in) uses this.
        final StringBuilder raw = new StringBuilder(text);
        agentRaw = raw;

        LinearLayout wrap = Widgets.column(this);

        LinearLayout labelRow = Widgets.row(this);
        Widgets.margins(labelRow, 0, 0, 0, Theme.dp(this, 5));
        TextView labelView = Widgets.text(this, agentChatLabel(), Theme.ACCENT_STRONG, 11, true);
        agentLabelViews.add(labelView);
        labelView.setLetterSpacing(0.07f);
        labelView.setLayoutParams(lp(0, WRAP, 1f));
        TextView copyBtn = Widgets.text(this, "COPY", Theme.FAINT, 11, true);
        copyBtn.setLetterSpacing(0.06f);
        int cbp = Theme.dp(this, 6);
        copyBtn.setPadding(cbp, Theme.dp(this, 2), cbp, Theme.dp(this, 2));
        copyBtn.setClickable(true);
        copyBtn.setOnClickListener(v -> copyToClipboard(raw.toString()));
        labelRow.addView(labelView);
        labelRow.addView(copyBtn);
        wrap.addView(labelRow);

        TextView bubble = Widgets.text(this, "", Theme.INK, 15, false);
        bubble.setText(Markdown.render(this, raw.toString(), Theme.INK));
        bubble.setTextIsSelectable(true);
        bubble.setBackground(Theme.rounded(this, Theme.PANEL, 16, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        bubble.setPadding(p + Theme.dp(this, 2), p, p + Theme.dp(this, 2), p);
        wrap.addView(bubble);
        append(wrap);
        agentBubble = bubble;
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agent message", text));
        android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * A small "copy" chip that puts {@code raw} on the clipboard verbatim — used
     * on Bash cards so the exact command (no {@code $} prompt or formatting) can
     * be lifted out. Returns {@code null} when there's nothing to copy.
     */
    private TextView copyChip(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        TextView b = Widgets.text(this, "COPY", Theme.INFO, 10, true);
        b.setLetterSpacing(0.06f);
        b.setBackground(Theme.rounded(this, 0x40000000, 6, Theme.LINE, 1));
        int padH = Theme.dp(this, 8);
        int padV = Theme.dp(this, 3);
        b.setPadding(padH, padV, padH, padV);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> copyToClipboard(raw));
        return b;
    }

    private void addToolUse(JSONObject event) {
        CanonicalAction action = CanonicalAction.parse(event.optJSONObject("action"));
        String callId = strictString(event, "call_id");
        if (action == null || callId == null || callId.isEmpty()) {
            clearLastTool();
            addUnsupportedToolEvent(event, !event.has("action"));
            return;
        }

        LinearLayout wrap = Widgets.column(this);
        wrap.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));

        LinearLayout head = Widgets.row(this);
        int hp = Theme.dp(this, 11);
        head.setPadding(hp, Theme.dp(this, 9), hp, Theme.dp(this, 9));
        head.setClickable(true);

        TextView caret = Widgets.text(this, "▸", Theme.FAINT, 11, false);
        Widgets.margins(caret, 0, 0, Theme.dp(this, 8), 0);
        TextView nameView = Widgets.text(this, action.title().toUpperCase(Locale.ROOT),
                Theme.INFO, 11, true);
        nameView.setLetterSpacing(0.04f);
        Widgets.margins(nameView, 0, 0, Theme.dp(this, 8), 0);
        TextView summary = Widgets.mono(this, action.summary(), Theme.MUTED, 12.5f);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(caret);
        head.addView(nameView);
        head.addView(summary);
        TextView copy = copyChip(action.command());
        if (copy != null) {
            Widgets.margins(copy, Theme.dp(this, 8), 0, 0, 0);
            head.addView(copy);
        }

        View content = ToolFormat.body(this, action);
        if (content == null) {
            content = Widgets.mono(this, prettyJson(action.detail()), 0xFFD4CFE0, 12.5f);
        }
        if (content instanceof TextView) ((TextView) content).setTextIsSelectable(true);
        HorizontalScrollView body = new HorizontalScrollView(this);
        body.addView(content, lp(WRAP, WRAP));
        int bp = Theme.dp(this, 12);
        body.setPadding(bp, bp, bp, bp);
        body.setBackgroundColor(0x40000000);
        body.setVisibility(View.GONE);

        head.setOnClickListener(v -> {
            boolean open = body.getVisibility() == View.VISIBLE;
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            caret.setText(open ? "▸" : "▾");
        });

        wrap.addView(head);
        wrap.addView(body);
        append(wrap);

        lastToolCard = wrap;
        lastToolCallId = callId;
    }

    private void addUnsupportedToolEvent(JSONObject event, boolean legacy) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        card.setPadding(p, p, p, p);
        String notice = legacy ? "Legacy event from an older server version"
                : "Unsupported or malformed tool action";
        card.addView(Widgets.text(this, notice, Theme.AWAITING, 12, true));
        TextView raw = Widgets.mono(this, prettyJson(event), Theme.MUTED, 12f);
        raw.setTextIsSelectable(true);
        Widgets.margins(raw, 0, Theme.dp(this, 8), 0, 0);
        card.addView(raw);
        append(card);
    }

    private void clearLastTool() {
        lastToolCard = null;
        lastToolCallId = null;
    }

    /* ---------------------------------------------------------------- */
    /* bash mode                                                        */
    /* ---------------------------------------------------------------- */

    private void addBashCommand(String command) {
        View card = bashCard(command, null);
        append(card);
        pendingBashCard = card;
        pendingBashCommand = command;
    }

    private void fillBashOutput(String command, JSONObject result) {
        if (pendingBashCard != null && command.equals(pendingBashCommand)) {
            int index = transcript.indexOfChild(pendingBashCard);
            pendingBashCard = null;
            pendingBashCommand = null;
            if (index >= 0) {
                // Rebuilt where it already sits, rather than appended: a command
                // that finishes mid-turn must not land below — or interrupt —
                // the agent message still streaming underneath it.
                transcript.removeViewAt(index);
                transcript.addView(bashCard(command, result), index, rowParams());
                scrollToBottom();
                return;
            }
        }
        // No card to fill in — a replay that began past the echo, or a command
        // another client started before we connected. It stands on its own.
        agentBubble = null;
        clearLastTool();
        append(bashCard(command, result));
    }

    /**
     * A `!` command and what it printed. Bordered in red rather than the
     * conversation's terracotta: the agent neither ran this nor ever sees the
     * output. The body is selectable monospace with a COPY chip, so it can be
     * lifted into a prompt if you decide the agent should see it after all.
     *
     * @param result the {@code bash_output} payload, or null while it runs
     */
    private LinearLayout bashCard(String command, JSONObject result) {
        LinearLayout wrap = Widgets.column(this);
        wrap.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.DANGER_LINE, 1));

        LinearLayout head = Widgets.row(this);
        head.setGravity(Gravity.TOP);
        int hp = Theme.dp(this, 11);
        head.setPadding(hp, Theme.dp(this, 9), hp, Theme.dp(this, 9));

        TextView prompt = Widgets.mono(this, "$", Theme.DANGER, 12.5f);
        Widgets.margins(prompt, 0, 0, Theme.dp(this, 8), 0);
        TextView cmd = Widgets.mono(this, command, Theme.INK, 12.5f);
        cmd.setTextIsSelectable(true);
        cmd.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(prompt);
        head.addView(cmd);
        if (result != null) {
            // The output, not the command: pasting what a command printed into a
            // prompt is the whole point of running one here.
            TextView copy = copyChip(bashOutputText(result));
            if (copy != null) {
                Widgets.margins(copy, Theme.dp(this, 8), 0, 0, 0);
                head.addView(copy);
            }
        }
        wrap.addView(head);

        boolean waiting = result == null;
        TextView out = Widgets.mono(this,
                waiting ? "running…" : bashBody(result),
                waiting ? Theme.FAINT : 0xFFD4CFE0, 12.5f);
        out.setTextIsSelectable(true);
        HorizontalScrollView body = new HorizontalScrollView(this);
        body.addView(out, lp(WRAP, WRAP));
        int bp = Theme.dp(this, 12);
        body.setPadding(bp, bp, bp, bp);
        body.setBackgroundColor(0x40000000);
        wrap.addView(body);

        if (result != null) {
            TextView meta = Widgets.text(this, bashStatus(result),
                    bashFailed(result) ? Theme.DANGER : Theme.FAINT, 11, false);
            meta.setPadding(hp, Theme.dp(this, 6), hp, Theme.dp(this, 8));
            wrap.addView(meta);
        }
        return wrap;
    }

    /** stdout, then stderr in the danger colour — one block of selectable text. */
    private static CharSequence bashBody(JSONObject result) {
        String out = jsonString(result, "stdout");
        String err = jsonString(result, "stderr");
        if (out.isEmpty() && err.isEmpty()) return "(no output)";
        if (err.isEmpty()) return out;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (!out.isEmpty()) {
            sb.append(out);
            if (!out.endsWith("\n")) sb.append('\n');
        }
        int start = sb.length();
        sb.append(err);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Theme.DANGER),
                start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** The same two streams unstyled, which is what a copy should hand over. */
    private static String bashOutputText(JSONObject result) {
        String out = jsonString(result, "stdout");
        String err = jsonString(result, "stderr");
        if (out.isEmpty()) return err;
        if (err.isEmpty()) return out;
        return out.endsWith("\n") ? out + err : out + "\n" + err;
    }

    /** How the command ended, and how long it took getting there. */
    private static String bashStatus(JSONObject result) {
        StringBuilder sb = new StringBuilder();
        // A null exit code means it never started — most often a working
        // directory deleted out from under the session.
        sb.append(result.isNull("exit_code")
                ? "did not start" : "exit " + result.optInt("exit_code"));
        if (!result.isNull("duration_ms")) {
            long ms = result.optLong("duration_ms", -1);
            if (ms >= 0) {
                sb.append(" · ").append(ms < 1000
                        ? ms + " ms"
                        : String.format(java.util.Locale.US, "%.1f s", ms / 1000.0));
            }
        }
        if (result.optBoolean("timed_out", false)) sb.append(" · timed out");
        if (result.optBoolean("truncated", false)) sb.append(" · output truncated");
        return sb.toString();
    }

    private static boolean bashFailed(JSONObject result) {
        return result.isNull("exit_code")
                || result.optInt("exit_code") != 0
                || result.optBoolean("timed_out", false);
    }

    /** optString renders an explicit JSON null as the text "null"; this doesn't. */
    private static String jsonString(JSONObject o, String key) {
        return o == null || o.isNull(key) ? "" : o.optString(key, "");
    }

    private void addApprovalRequest(JSONObject msg) {
        final String id = strictString(msg, "request_id");
        final String callId = strictString(msg, "call_id");
        final CanonicalAction action = CanonicalAction.parse(msg.optJSONObject("action"));
        final JSONArray options = msg.optJSONArray("options");
        final boolean autoFieldValid = !msg.has("auto_approved")
                || Boolean.TRUE.equals(msg.opt("auto_approved"));
        if (id == null || id.isEmpty() || callId == null || callId.isEmpty()
                || action == null || !CanonicalAction.validOptions(options) || !autoFieldValid) {
            clearLastTool();
            addUnsupportedToolEvent(msg, !msg.has("action"));
            return;
        }
        // Auto-approved requests never expose active controls while their normal
        // approval_response is still in flight or being replayed.
        final boolean auto = msg.has("auto_approved");
        final int accent = auto ? Theme.RUNNING : Theme.AWAITING;

        // Replace only the immediately eligible call with the exact same opaque
        // invocation ID. The approval's repeated action renders independently.
        if (lastToolCard != null && callId.equals(lastToolCallId)) {
            transcript.removeView(lastToolCard);
        }
        clearLastTool();

        LinearLayout card = Widgets.column(this);
        if (auto) {
            card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        } else {
            card.setBackground(Theme.rounded(this, Theme.withAlpha(Theme.AWAITING, 0x18), 14,
                    Theme.withAlpha(Theme.AWAITING, 0x66), 1));
        }
        int p = Theme.dp(this, 14);
        card.setPadding(p, p, p, p);

        LinearLayout head = Widgets.row(this);
        TextView tag = Widgets.text(this, auto ? "AUTO-APPROVED" : "APPROVAL REQUIRED", accent, 11, true);
        tag.setLetterSpacing(0.06f);
        Widgets.margins(tag, 0, 0, Theme.dp(this, 10), 0);
        TextView toolView = Widgets.mono(this, action.title(), Theme.INK, 12.5f);
        toolView.setBackground(Theme.rounded(this, 0x40000000, 6));
        int tp = Theme.dp(this, 8);
        toolView.setPadding(tp, Theme.dp(this, 2), tp, Theme.dp(this, 2));
        head.addView(tag);
        head.addView(toolView);
        {
            TextView copy = copyChip(action.command());
            if (copy != null) {
                // Height must be 0, not WRAP: a bare View measures wrap_content as
                // the full available height (View.getDefaultSize), so under the
                // transcript's fillViewport pass it would balloon the header row and
                // shove the body and Deny/Allow buttons off-screen.
                View spacer = new View(this);
                head.addView(spacer, lp(0, 0, 1f));
                head.addView(copy);
            }
        }
        card.addView(head);

        // The canonical action is repeated on the request, so this card never
        // depends on retaining or looking up the preceding tool row.
        View formatted = ToolFormat.body(this, action);
        if (formatted instanceof TextView) ((TextView) formatted).setTextIsSelectable(true);
        int bp = Theme.dp(this, 11);
        if (formatted != null) {
            HorizontalScrollView bodyScroll = new HorizontalScrollView(this);
            bodyScroll.addView(formatted, lp(WRAP, WRAP));
            bodyScroll.setPadding(bp, bp, bp, bp);
            bodyScroll.setBackground(Theme.rounded(this, 0x47000000, 10,
                    Theme.withAlpha(accent, 0x22), 1));
            LinearLayout.LayoutParams bsLp = lp(MATCH, WRAP);
            bsLp.topMargin = Theme.dp(this, 11);
            bodyScroll.setLayoutParams(bsLp);
            card.addView(bodyScroll);
            addRawToggle(card, action.json());
        } else {
            TextView pre = Widgets.mono(this, prettyJson(action.detail()), Theme.INK, 12.5f);
            pre.setTextIsSelectable(true);
            pre.setBackground(Theme.rounded(this, 0x47000000, 10, Theme.withAlpha(accent, 0x22), 1));
            pre.setPadding(bp, bp, bp, bp);
            LinearLayout.LayoutParams preLp = lp(MATCH, WRAP);
            preLp.topMargin = Theme.dp(this, 11);
            pre.setLayoutParams(preLp);
            card.addView(pre);
        }

        if (auto) {
            TextView marker = Widgets.text(this, "✓ Auto-approved", accent, 14, true);
            LinearLayout.LayoutParams mp = lp(WRAP, WRAP);
            mp.topMargin = Theme.dp(this, 10);
            card.addView(marker, mp);
            append(card);
            // Not pending: the paired approval_response (auto) is a no-op since
            // we never set pendingApprovalId.
            return;
        }

        View buttons = buildApprovalButtons(id, options, card);
        card.addView(buttons);
        card.setTag(buttons); // remember the button container for finalize()

        append(card);
        pendingApprovalCard = card;
        pendingApprovalId = id;
    }

    /**
     * Render the choice buttons for an approval. The backend may offer an
     * arbitrary {@code options} list (each {@code {id, name, kind}}) — one button
     * per option, styled by {@code kind} and answered with its {@code option_id}.
     * When {@code options} is absent or empty we fall back to plain Deny / Allow
     * answered with a {@code behavior}. A free-form reason field sits under the
     * buttons; a reject reads it and forwards whatever the user typed (it may be
     * left empty for no message).
     */
    private View buildApprovalButtons(String id, JSONArray options, View card) {
        java.util.List<JSONObject> opts = new java.util.ArrayList<>();
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject o = options.optJSONObject(i);
                if (o != null && !o.optString("id", "").isEmpty()) opts.add(o);
            }
        }
        // Fall back to a synthetic Deny / Allow pair answered with a behavior.
        boolean fallback = opts.isEmpty();
        if (fallback) {
            opts.add(syntheticOption(null, "Deny", "reject_once"));
            opts.add(syntheticOption(null, "Allow", "allow_once"));
        }

        // When two choices sit side by side, keep deny on the left and allow on
        // the right regardless of the order the backend sent them.
        if (opts.size() == 2
                && !opts.get(0).optString("kind", "").startsWith("reject")
                && opts.get(1).optString("kind", "").startsWith("reject")) {
            opts.add(0, opts.remove(1));
        }

        // The whole block (buttons + deny field) is removed together on finalize,
        // so wrap it in one column the card can swap out for the result label.
        LinearLayout wrapper = Widgets.column(this);
        LinearLayout.LayoutParams wrapperLp = lp(MATCH, WRAP);
        wrapperLp.topMargin = Theme.dp(this, 11);
        wrapper.setLayoutParams(wrapperLp);

        // Two choices sit side by side (the common Allow / Deny case); more than
        // that stack full-width so longer agent-supplied labels stay legible.
        boolean horizontal = opts.size() <= 2;
        LinearLayout container = horizontal ? Widgets.row(this) : Widgets.column(this);
        container.setLayoutParams(lp(MATCH, WRAP));

        // Optional reason forwarded to the agent on a deny (Claude Code delivers
        // it inline). Empty means no message.
        final EditText denyField = new EditText(this);
        denyField.setHint("Deny with a message (optional)");
        denyField.setTextColor(Theme.INK);
        denyField.setHintTextColor(Theme.FAINT);
        denyField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        denyField.setBackground(Theme.rounded(this, 0x47000000, 10, Theme.LINE, 1));
        denyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        denyField.setMaxLines(5);
        int dfp = Theme.dp(this, 10);
        denyField.setPadding(dfp, dfp, dfp, dfp);
        LinearLayout.LayoutParams dfLp = lp(MATCH, WRAP);
        dfLp.topMargin = Theme.dp(this, 10);
        denyField.setLayoutParams(dfLp);

        for (int i = 0; i < opts.size(); i++) {
            JSONObject opt = opts.get(i);
            String optionId = fallback ? null : opt.optString("id", "");
            String name = opt.optString("name", optionId == null ? "?" : optionId);
            String kind = opt.optString("kind", "");
            boolean reject = kind.startsWith("reject");
            String behavior = reject ? "deny" : "allow";

            TextView btn = reject ? Widgets.ghostButton(this, name)
                                  : Widgets.primaryButton(this, name);
            LinearLayout.LayoutParams btnLp = horizontal
                    ? lp(0, Theme.dp(this, 44), 1f)
                    : lp(MATCH, Theme.dp(this, 44));
            if (i > 0) {
                if (horizontal) btnLp.leftMargin = Theme.dp(this, 10);
                else btnLp.topMargin = Theme.dp(this, 8);
            }
            btn.setLayoutParams(btnLp);
            btn.setOnClickListener(v -> {
                if (reject) {
                    String reason = denyField.getText().toString().trim();
                    respondApproval(id, optionId, behavior,
                            reason.isEmpty() ? null : reason, card);
                } else {
                    respondApproval(id, optionId, behavior, null, card);
                }
            });
            container.addView(btn);
        }

        wrapper.addView(container);
        wrapper.addView(denyField);
        return wrapper;
    }

    private JSONObject syntheticOption(String optId, String name, String kind) {
        JSONObject o = new JSONObject();
        try {
            if (optId != null) o.put("id", optId);
            o.put("name", name);
            o.put("kind", kind);
        } catch (Exception ignored) {}
        return o;
    }

    /** A collapsed disclosure that reveals the complete canonical action. */
    private void addRawToggle(LinearLayout card, JSONObject in) {
        TextView toggle = Widgets.mono(this, "▸ action JSON", Theme.FAINT, 11.5f);
        LinearLayout.LayoutParams tLp = lp(WRAP, WRAP);
        tLp.topMargin = Theme.dp(this, 9);
        toggle.setLayoutParams(tLp);
        toggle.setClickable(true);

        TextView raw = Widgets.mono(this, prettyJson(in), Theme.MUTED, 12f);
        raw.setTextIsSelectable(true);
        raw.setBackground(Theme.rounded(this, 0x47000000, 8));
        int rp = Theme.dp(this, 10);
        raw.setPadding(rp, rp, rp, rp);
        LinearLayout.LayoutParams rawLp = lp(MATCH, WRAP);
        rawLp.topMargin = Theme.dp(this, 6);
        raw.setLayoutParams(rawLp);
        raw.setVisibility(View.GONE);

        toggle.setOnClickListener(v -> {
            boolean open = raw.getVisibility() == View.VISIBLE;
            raw.setVisibility(open ? View.GONE : View.VISIBLE);
            toggle.setText(open ? "▸ action JSON" : "▾ action JSON");
        });

        card.addView(toggle);
        card.addView(raw);
    }

    /**
     * Answer an approval. When {@code optionId} is non-null we send the agent's
     * specific {@code option_id} (the backend derives the behavior from it);
     * otherwise we send the resolved {@code behavior}. A denial may carry a
     * free-form {@code message} the backend forwards to the agent.
     */
    private void respondApproval(String id, String optionId, String behavior,
                                 String message, View card) {
        if (socket != null) {
            try {
                JSONObject out = new JSONObject();
                out.put("type", "approval_response");
                out.put("request_id", id);
                if (optionId != null && !optionId.isEmpty()) out.put("option_id", optionId);
                else out.put("behavior", behavior);
                // message is only meaningful on a deny; the server ignores it on allow.
                if ("deny".equals(behavior) && message != null && !message.isEmpty()) {
                    out.put("message", message);
                }
                socket.send(out.toString());
            } catch (Exception ignored) {}
        }
        finalizeApproval(card, behavior, message);
        if (id.equals(pendingApprovalId)) {
            pendingApprovalCard = null;
            pendingApprovalId = null;
        }
    }

    private void resolveApproval(String id, String behavior, String message) {
        if (id.equals(pendingApprovalId) && pendingApprovalCard != null) {
            finalizeApproval(pendingApprovalCard, behavior, message);
            pendingApprovalCard = null;
            pendingApprovalId = null;
        }
    }

    private void finalizeApproval(View card, String behavior, String message) {
        if (!(card instanceof LinearLayout)) return;
        LinearLayout c = (LinearLayout) card;
        Object tag = c.getTag();
        if (!(tag instanceof View)) return; // already resolved
        View buttons = (View) tag;
        int idx = c.indexOfChild(buttons);
        if (idx < 0) return;
        c.removeView(buttons);
        c.setTag(null);
        c.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));

        String label;
        int color;
        if ("allow".equals(behavior)) { label = "✓ Allowed"; color = Theme.RUNNING; }
        else if ("deny".equals(behavior)) { label = "✕ Denied"; color = Theme.DANGER; }
        else { label = "— No longer pending"; color = Theme.FAINT; }
        TextView result = Widgets.text(this, label, color, 14, true);
        LinearLayout.LayoutParams rp = lp(WRAP, WRAP);
        rp.topMargin = Theme.dp(this, 4);
        c.addView(result, idx, rp);

        // Show the denial reason (if any) so the transcript records what the
        // agent was told to do instead.
        if ("deny".equals(behavior) && message != null && !message.isEmpty()) {
            TextView reason = Widgets.text(this, message, Theme.MUTED, 13, false);
            reason.setTextIsSelectable(true);
            LinearLayout.LayoutParams reasonLp = lp(MATCH, WRAP);
            reasonLp.topMargin = Theme.dp(this, 4);
            c.addView(reason, idx + 1, reasonLp);
        }
    }

    /* ---------------------------------------------------------------- */
    /* questions (AskUserQuestion)                                      */
    /* ---------------------------------------------------------------- */

    /** Bundle the live state of a question card so it can be finalized later. */
    private static final class QuestionCard {
        final String id;
        View action; // the Submit button (or a placeholder) swapped for the result
        final java.util.List<View> optionRows = new java.util.ArrayList<>();
        boolean resolved;
        QuestionCard(String id) { this.id = id; }
    }

    /**
     * Render an {@code AskUserQuestion} call as a real multiple-choice prompt:
     * each question's options become numbered buttons, and the user's pick is
     * sent back as the tool's answer (a {@code question_response}). A single
     * single-select question submits on tap; anything else (multiSelect, or
     * several questions) collects selections and submits via a Submit button.
     */
    private void addQuestionRequest(JSONObject msg) {
        final String id = msg.optString("request_id", "");
        JSONArray questionsArr = msg.optJSONArray("questions");
        if (questionsArr == null) questionsArr = new JSONArray();
        final java.util.List<JSONObject> qs = new java.util.ArrayList<>();
        for (int i = 0; i < questionsArr.length(); i++) {
            JSONObject q = questionsArr.optJSONObject(i);
            if (q != null) qs.add(q);
        }

        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.withAlpha(Theme.INFO, 0x18), 14,
                Theme.withAlpha(Theme.INFO, 0x66), 1));
        int p = Theme.dp(this, 14);
        card.setPadding(p, p, p, p);

        TextView tag = Widgets.text(this, "QUESTION", Theme.INFO, 11, true);
        tag.setLetterSpacing(0.06f);
        card.addView(tag);

        final QuestionCard qc = new QuestionCard(id);
        final boolean fastPath = qs.size() == 1 && !qs.get(0).optBoolean("multiSelect", false);

        // Per-question state, indexed by question. Single-select keeps <=1 entry.
        final java.util.List<String> questionText = new java.util.ArrayList<>();
        final java.util.List<Boolean> multi = new java.util.ArrayList<>();
        final java.util.List<java.util.List<String>> labels = new java.util.ArrayList<>();
        final java.util.List<java.util.LinkedHashSet<Integer>> sel = new java.util.ArrayList<>();
        final java.util.List<java.util.List<View>> rows = new java.util.ArrayList<>();
        final TextView[] submitHolder = new TextView[1];

        // Repaint every option to reflect the current selection, and gate Submit
        // on every question having an answer.
        final Runnable restyle = () -> {
            for (int qi = 0; qi < rows.size(); qi++) {
                java.util.Set<Integer> chosen = sel.get(qi);
                java.util.List<View> rl = rows.get(qi);
                for (int oi = 0; oi < rl.size(); oi++) {
                    styleOptionRow(rl.get(oi), chosen.contains(oi));
                }
            }
            TextView submit = submitHolder[0];
            if (submit != null) {
                boolean ready = true;
                for (java.util.Set<Integer> s : sel) if (s.isEmpty()) { ready = false; break; }
                submit.setEnabled(ready);
                submit.setAlpha(ready ? 1f : 0.4f);
            }
        };

        for (int qi = 0; qi < qs.size(); qi++) {
            JSONObject q = qs.get(qi);
            final int qIndex = qi;
            final boolean qMulti = q.optBoolean("multiSelect", false);
            final String qText = q.optString("question", "");
            questionText.add(qText);
            multi.add(qMulti);
            sel.add(new java.util.LinkedHashSet<>());
            final java.util.List<String> optLabels = new java.util.ArrayList<>();
            final java.util.List<View> optRows = new java.util.ArrayList<>();

            LinearLayout block = Widgets.column(this);
            LinearLayout.LayoutParams blockLp = lp(MATCH, WRAP);
            blockLp.topMargin = Theme.dp(this, qi == 0 ? 10 : 16);
            block.setLayoutParams(blockLp);

            String header = q.optString("header", "");
            if (!header.isEmpty()) {
                TextView hv = Widgets.text(this, header.toUpperCase(), Theme.FAINT, 10.5f, true);
                hv.setLetterSpacing(0.06f);
                Widgets.margins(hv, 0, 0, 0, Theme.dp(this, 3));
                block.addView(hv);
            }
            block.addView(Widgets.text(this, qText, Theme.INK, 15, true));

            JSONArray options = q.optJSONArray("options");
            if (options == null) options = new JSONArray();
            for (int oi = 0; oi < options.length(); oi++) {
                JSONObject opt = options.optJSONObject(oi);
                if (opt == null) continue;
                final int optIndex = optLabels.size();
                String label = opt.optString("label", "");
                String desc = opt.optString("description", "");
                optLabels.add(label);

                LinearLayout row = Widgets.row(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int rpH = Theme.dp(this, 11);
                int rpV = Theme.dp(this, 9);
                row.setPadding(rpH, rpV, rpH, rpV);
                LinearLayout.LayoutParams rowLp = lp(MATCH, WRAP);
                rowLp.topMargin = Theme.dp(this, 8);
                row.setLayoutParams(rowLp);
                row.setClickable(true);

                int ns = Theme.dp(this, 26);
                TextView num = Widgets.text(this, String.valueOf(optIndex + 1), Theme.INFO, 13, true);
                num.setGravity(Gravity.CENTER);
                num.setBackground(Theme.pill(this, Theme.withAlpha(Theme.INFO, 0x22),
                        Theme.withAlpha(Theme.INFO, 0x66), 1));
                num.setLayoutParams(lp(ns, ns));
                Widgets.margins(num, 0, 0, Theme.dp(this, 11), 0);
                row.addView(num);

                LinearLayout textCol = Widgets.column(this);
                textCol.setLayoutParams(lp(0, WRAP, 1f));
                textCol.addView(Widgets.text(this,
                        label.isEmpty() ? ("Option " + (optIndex + 1)) : label, Theme.INK, 14.5f, true));
                if (!desc.isEmpty()) {
                    TextView dv = Widgets.text(this, desc, Theme.MUTED, 13, false);
                    Widgets.margins(dv, 0, Theme.dp(this, 2), 0, 0);
                    textCol.addView(dv);
                }
                row.addView(textCol);

                TextView check = Widgets.text(this, "✓", Theme.INFO, 16, true);
                check.setVisibility(View.INVISIBLE);
                Widgets.margins(check, Theme.dp(this, 8), 0, 0, 0);
                row.addView(check);
                row.setTag(check); // styleOptionRow toggles this on selection

                styleOptionRow(row, false);
                optRows.add(row);
                block.addView(row);

                row.setOnClickListener(v -> {
                    if (qc.resolved) return;
                    java.util.LinkedHashSet<Integer> chosen = sel.get(qIndex);
                    if (qMulti) {
                        if (!chosen.remove(optIndex)) chosen.add(optIndex);
                    } else {
                        chosen.clear();
                        chosen.add(optIndex);
                    }
                    if (fastPath) {
                        submitQuestion(id, questionText, multi, labels, sel, card);
                    } else {
                        restyle.run();
                    }
                });
            }
            labels.add(optLabels);
            rows.add(optRows);
            qc.optionRows.addAll(optRows);
            card.addView(block);
        }

        if (fastPath) {
            // Tapping an option submits, so there's no Submit button; leave a
            // zero-height anchor that finalize swaps for the result line.
            View anchor = new View(this);
            anchor.setLayoutParams(lp(MATCH, 0));
            qc.action = anchor;
            card.addView(anchor);
        } else {
            TextView submit = Widgets.primaryButton(this, "Submit");
            LinearLayout.LayoutParams sLp = lp(MATCH, Theme.dp(this, 46));
            sLp.topMargin = Theme.dp(this, 14);
            submit.setLayoutParams(sLp);
            submit.setOnClickListener(v -> {
                if (qc.resolved || !v.isEnabled()) return;
                submitQuestion(id, questionText, multi, labels, sel, card);
            });
            submitHolder[0] = submit;
            qc.action = submit;
            card.addView(submit);
        }

        card.setTag(qc);
        restyle.run(); // set the initial (disabled) Submit state
        append(card);
        pendingQuestionCard = card;
        pendingQuestionId = id;

        // The status may already have flipped to awaiting_approval; correct the
        // activity wording to match a question rather than an approval.
        if ("awaiting_approval".equals(status) && pendingApprovalCard == null) {
            activity.setVisibility(View.VISIBLE);
            activity.setText("● Waiting for your answer");
            activity.setTextColor(Theme.AWAITING);
        }
    }

    /** Paint a question option row according to whether it's selected. */
    private void styleOptionRow(View row, boolean selected) {
        row.setBackground(selected
                ? Theme.rounded(this, Theme.withAlpha(Theme.INFO, 0x26), 10,
                        Theme.withAlpha(Theme.INFO, 0x99), 1)
                : Theme.rounded(this, Theme.PANEL2, 10, Theme.LINE, 1));
        Object t = row.getTag();
        if (t instanceof View) ((View) t).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Build the {@code answers} payload from the user's selections (a bare label
     * per single-select question, an array of labels per multiSelect), send it as
     * a {@code question_response}, and finalize the card.
     */
    private void submitQuestion(String id, java.util.List<String> questionText,
                                java.util.List<Boolean> multi,
                                java.util.List<java.util.List<String>> labels,
                                java.util.List<java.util.LinkedHashSet<Integer>> sel, View card) {
        JSONObject answers = new JSONObject();
        try {
            for (int qi = 0; qi < questionText.size(); qi++) {
                java.util.LinkedHashSet<Integer> chosen = sel.get(qi);
                java.util.List<String> opts = labels.get(qi);
                if (multi.get(qi)) {
                    JSONArray arr = new JSONArray();
                    for (int oi : chosen) if (oi < opts.size()) arr.put(opts.get(oi));
                    answers.put(questionText.get(qi), arr);
                } else if (!chosen.isEmpty()) {
                    int oi = chosen.iterator().next();
                    if (oi < opts.size()) answers.put(questionText.get(qi), opts.get(oi));
                }
            }
        } catch (Exception ignored) {}

        if (socket != null) {
            try {
                JSONObject out = new JSONObject();
                out.put("type", "question_response");
                out.put("request_id", id);
                out.put("answers", answers);
                socket.send(out.toString());
            } catch (Exception ignored) {}
        }
        finalizeQuestion(card, answers);
        if (id.equals(pendingQuestionId)) {
            pendingQuestionCard = null;
            pendingQuestionId = null;
        }
    }

    private void resolveQuestion(String id, JSONObject answers) {
        if (id.equals(pendingQuestionId) && pendingQuestionCard != null) {
            finalizeQuestion(pendingQuestionCard, answers);
            pendingQuestionCard = null;
            pendingQuestionId = null;
        }
    }

    /**
     * Freeze a question card: stop the options responding, drop the Submit
     * button, and append a result line. {@code answers == null} (turn ended)
     * shows "no longer pending".
     */
    private void finalizeQuestion(View card, JSONObject answers) {
        if (!(card instanceof LinearLayout)) return;
        LinearLayout c = (LinearLayout) card;
        Object tag = c.getTag();
        if (!(tag instanceof QuestionCard)) return; // already resolved
        QuestionCard qc = (QuestionCard) tag;
        if (qc.resolved) return;
        qc.resolved = true;
        c.setTag(null);

        for (View row : qc.optionRows) row.setClickable(false);

        int idx = qc.action == null ? -1 : c.indexOfChild(qc.action);
        if (idx >= 0) c.removeView(qc.action);
        c.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));

        String summary = answers == null ? null : summarizeAnswers(answers);
        String label;
        int color;
        if (summary != null && !summary.isEmpty()) {
            label = "✓ Answered: " + summary;
            color = Theme.RUNNING;
        } else {
            label = "— No longer pending";
            color = Theme.FAINT;
        }
        TextView result = Widgets.text(this, label, color, 14, true);
        LinearLayout.LayoutParams rp = lp(MATCH, WRAP);
        rp.topMargin = Theme.dp(this, 12);
        if (idx >= 0) c.addView(result, idx, rp); else c.addView(result, rp);
    }

    /** Flatten an answers object to a human summary, e.g. "Rocket" or "A / B". */
    private static String summarizeAnswers(JSONObject answers) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        java.util.Iterator<String> it = answers.keys();
        while (it.hasNext()) {
            Object v = answers.opt(it.next());
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                java.util.List<String> labs = new java.util.ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (!s.isEmpty()) labs.add(s);
                }
                if (!labs.isEmpty()) parts.add(android.text.TextUtils.join(" / ", labs));
            } else if (v != null) {
                String s = v.toString();
                if (!s.isEmpty()) parts.add(s);
            }
        }
        return android.text.TextUtils.join(", ", parts);
    }

    private void addError(String message) {
        LinearLayout wrap = Widgets.column(this);
        TextView labelView = Widgets.text(this, "ERROR", Theme.DANGER, 11, true);
        labelView.setLetterSpacing(0.07f);
        Widgets.margins(labelView, 0, 0, 0, Theme.dp(this, 5));
        TextView text = Widgets.text(this, message, Theme.DANGER, 14, false);
        text.setTextIsSelectable(true);
        text.setBackground(Theme.rounded(this, Theme.DANGER_SOFT, 10, Theme.DANGER_LINE, 1));
        int p = Theme.dp(this, 12);
        text.setPadding(p, p, p, p);
        text.setLayoutParams(lp(MATCH, WRAP));
        wrap.addView(labelView);
        wrap.addView(text);
        append(wrap);
    }

    /* ---------------------------------------------------------------- */
    /* sending                                                          */
    /* ---------------------------------------------------------------- */

    private boolean isBusy() {
        return "running".equals(status) || "awaiting_approval".equals(status);
    }

    private void sendPrompt() {
        // The composer is off screen while archived, but the IME's send action
        // can still reach here from a detached-but-focused input.
        if (archived) return;
        Composer parsed = Composer.parse(input.getText().toString());
        if (parsed == null || socket == null) return;
        try {
            JSONObject out = new JSONObject();
            if (parsed.bash) {
                // Nothing typed after the `!` yet.
                if (parsed.text.isEmpty()) return;
                // Deliberately not gated on status: bash never takes the turn
                // lock server-side, so a command runs while the agent works.
                out.put("type", "bash");
                out.put("command", parsed.text);
            } else {
                if (isBusy()) {
                    // Rejected, but the text stays put: it is still worth sending
                    // once the turn ends, and it may be what you meant to run.
                    android.widget.Toast.makeText(this,
                            "The agent is busy — wait for the turn to finish, or prefix with ! to run a command",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                out.put("type", "input");
                out.put("text", parsed.text);
            }
            if (socket.send(out.toString())) {
                input.setText("");
                applyComposerMode();
            }
        } catch (Exception ignored) {}
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                          */
    /* ---------------------------------------------------------------- */

    private static String prettyJson(JSONObject o) {
        if (o == null) return "{}";
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private static String strictString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        return raw instanceof String ? (String) raw : null;
    }
}
