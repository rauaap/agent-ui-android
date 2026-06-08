package com.agentui.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

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
    static final String EXTRA_DIR = "dir";
    static final String EXTRA_STATUS = "status";

    private Api api;
    private String sessionId;
    private String sessionName;
    private String status = "idle";
    private boolean notifyOn;

    // views
    private LinearLayout transcript;
    private ScrollView scroll;
    private LinearLayout statusHolder;
    private ImageView bellBtn;
    private TextView stopBtn;
    private TextView activity;
    private EditText input;
    private TextView sendBtn;

    // socket
    private WebSocket socket;
    private boolean active = true;
    private int reconnectAttempt = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;

    // transcript state
    private TextView agentBubble;     // coalesce consecutive output chunks
    private View pendingApprovalCard;
    private String pendingApprovalId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        sessionId = getIntent().getStringExtra(EXTRA_ID);
        sessionName = getIntent().getStringExtra(EXTRA_NAME);
        String dir = getIntent().getStringExtra(EXTRA_DIR);
        status = getIntent().getStringExtra(EXTRA_STATUS);
        if (status == null) status = "idle";
        notifyOn = api.prefs().notifyEnabled(sessionId);

        setContentView(buildRoot(sessionName, dir));
        applyStatus(status);
        connect();
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

    private View buildRoot(String name, String dir) {
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
        TextView nameView = Widgets.text(this, name == null ? "" : name, Theme.INK, 17, true);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headings.addView(nameView);
        TextView dirView = Widgets.mono(this, dir == null ? "" : dir, Theme.FAINT, 12);
        dirView.setMaxLines(1);
        dirView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        Widgets.margins(dirView, 0, Theme.dp(this, 2), 0, 0);
        headings.addView(dirView);
        header.addView(headings);

        bellBtn = new ImageView(this);
        bellBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int bellPad = Theme.dp(this, 10);
        bellBtn.setPadding(bellPad, bellPad, bellPad, bellPad);
        bellBtn.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        bellBtn.setClickable(true);
        bellBtn.setOnClickListener(v -> toggleNotify());
        header.addView(bellBtn);
        updateBell();

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
        scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        scroll.setFillViewport(true);
        transcript = Widgets.column(this);
        transcript.setPadding(pad, pad, pad, pad);
        scroll.addView(transcript);
        root.addView(scroll);

        // ---- composer ----
        LinearLayout footer = Widgets.column(this);
        footer.setPadding(pad, Theme.dp(this, 8), pad, pad);

        activity = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        Widgets.margins(activity, Theme.dp(this, 6), 0, 0, Theme.dp(this, 8));
        footer.addView(activity);

        LinearLayout composer = Widgets.row(this);
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
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
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

        footer.addView(composer);
        root.addView(footer);

        return root;
    }

    /* ---------------------------------------------------------------- */
    /* status                                                           */
    /* ---------------------------------------------------------------- */

    /* ---------------------------------------------------------------- */
    /* notifications                                                    */
    /* ---------------------------------------------------------------- */

    private void toggleNotify() {
        notifyOn = !notifyOn;
        api.prefs().setNotify(sessionId, notifyOn);
        updateBell();
        if (notifyOn) {
            // If a task is already in flight, start watching it right away.
            if ("running".equals(status) || "awaiting_approval".equals(status)) {
                WatchService.watch(this, sessionId, sessionName, status);
            }
        } else {
            WatchService.unwatch(this, sessionId);
        }
    }

    private void updateBell() {
        bellBtn.setImageResource(notifyOn ? R.drawable.ic_bell : R.drawable.ic_bell_off);
        bellBtn.setColorFilter(notifyOn ? Theme.INK : Theme.FAINT);
    }

    private void applyStatus(String s) {
        status = s;
        statusHolder.removeAllViews();
        statusHolder.addView(Widgets.statusBadge(this, s));

        boolean busy = "running".equals(s) || "awaiting_approval".equals(s);
        if (busy && notifyOn) {
            WatchService.watch(this, sessionId, sessionName, s);
        }
        input.setEnabled(!busy);
        sendBtn.setEnabled(!busy);
        sendBtn.setAlpha(busy ? 0.4f : 1f);
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);

        if ("running".equals(s)) {
            activity.setVisibility(View.VISIBLE);
            activity.setText("● Working…");
            activity.setTextColor(Theme.RUNNING);
        } else if ("awaiting_approval".equals(s)) {
            activity.setVisibility(View.VISIBLE);
            activity.setText("● Waiting for your approval");
            activity.setTextColor(Theme.AWAITING);
        } else {
            activity.setVisibility(View.GONE);
        }

        // Retire a still-open approval card if the turn ended.
        if (!"awaiting_approval".equals(s) && pendingApprovalCard != null) {
            finalizeApproval(pendingApprovalCard, null);
            pendingApprovalCard = null;
            pendingApprovalId = null;
        }
    }

    /* ---------------------------------------------------------------- */
    /* websocket                                                        */
    /* ---------------------------------------------------------------- */

    private void connect() {
        cancelReconnect();
        if (!active || sessionId == null) return;

        transcript.removeAllViews();
        agentBubble = null;
        pendingApprovalCard = null;
        pendingApprovalId = null;

        String url = api.prefs().wsBase() + "/ws/sessions/" + sessionId;
        Request req = new Request.Builder().url(url).build();
        socket = api.http().newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                runOnUiThread(() -> reconnectAttempt = 0);
            }
            @Override public void onMessage(WebSocket ws, String text) {
                runOnUiThread(() -> handleMessage(text));
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

    private void handleMessage(String raw) {
        JSONObject msg;
        try {
            msg = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        String type = msg.optString("type", "");
        switch (type) {
            case "status":
                applyStatus(msg.optString("status", "idle"));
                break;
            case "input":
                agentBubble = null;
                addUserMessage(msg.optString("text", ""));
                break;
            case "output":
                addAgentOutput(msg.optString("text", ""));
                break;
            case "tool_use":
                agentBubble = null;
                addToolUse(msg.optString("tool", "tool"), msg.optJSONObject("input"));
                break;
            case "approval_request":
                agentBubble = null;
                addApprovalRequest(msg);
                break;
            case "approval_response":
                resolveApproval(msg.optString("request_id", ""), msg.optString("behavior", ""));
                break;
            case "done":
                agentBubble = null;
                break;
            case "error":
                agentBubble = null;
                addError(msg.optString("message", "Unknown error"));
                break;
            default:
                break;
        }
    }

    /* ---------------------------------------------------------------- */
    /* transcript rendering                                             */
    /* ---------------------------------------------------------------- */

    private void append(View v) {
        LinearLayout.LayoutParams p = lp(MATCH, WRAP);
        p.bottomMargin = Theme.dp(this, 14);
        transcript.addView(v, p);
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addUserMessage(String text) {
        LinearLayout wrap = Widgets.column(this);
        wrap.setGravity(Gravity.END);
        TextView bubble = Widgets.text(this, text, Theme.INK, 15, false);
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
            agentBubble.append(text);
            scrollToBottom();
            return;
        }
        LinearLayout wrap = Widgets.column(this);
        TextView labelView = Widgets.text(this, "CLAUDE", Theme.ACCENT_STRONG, 11, true);
        labelView.setLetterSpacing(0.07f);
        Widgets.margins(labelView, 0, 0, 0, Theme.dp(this, 5));
        TextView bubble = Widgets.text(this, text, Theme.INK, 15, false);
        bubble.setBackground(Theme.rounded(this, Theme.PANEL, 16, Theme.LINE, 1));
        int p = Theme.dp(this, 12);
        bubble.setPadding(p + Theme.dp(this, 2), p, p + Theme.dp(this, 2), p);
        wrap.addView(labelView);
        wrap.addView(bubble);
        append(wrap);
        agentBubble = bubble;
    }

    private void addToolUse(String tool, JSONObject inputObj) {
        final JSONObject in = inputObj == null ? new JSONObject() : inputObj;

        LinearLayout wrap = Widgets.column(this);
        wrap.setBackground(Theme.rounded(this, Theme.PANEL, 10, Theme.LINE, 1));

        LinearLayout head = Widgets.row(this);
        int hp = Theme.dp(this, 11);
        head.setPadding(hp, Theme.dp(this, 9), hp, Theme.dp(this, 9));
        head.setClickable(true);

        TextView caret = Widgets.text(this, "▸", Theme.FAINT, 11, false);
        Widgets.margins(caret, 0, 0, Theme.dp(this, 8), 0);
        TextView nameView = Widgets.text(this, tool.toUpperCase(), Theme.INFO, 11, true);
        nameView.setLetterSpacing(0.04f);
        Widgets.margins(nameView, 0, 0, Theme.dp(this, 8), 0);
        TextView summary = Widgets.mono(this, toolSummary(tool, in), Theme.MUTED, 12.5f);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(caret);
        head.addView(nameView);
        head.addView(summary);

        TextView body = Widgets.mono(this, prettyJson(in), 0xFFD4CFE0, 12.5f);
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
    }

    private void addApprovalRequest(JSONObject msg) {
        final String id = msg.optString("request_id", "");
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.withAlpha(Theme.AWAITING, 0x18), 14,
                Theme.withAlpha(Theme.AWAITING, 0x66), 1));
        int p = Theme.dp(this, 14);
        card.setPadding(p, p, p, p);

        LinearLayout head = Widgets.row(this);
        TextView tag = Widgets.text(this, "APPROVAL REQUIRED", Theme.AWAITING, 11, true);
        tag.setLetterSpacing(0.06f);
        Widgets.margins(tag, 0, 0, Theme.dp(this, 10), 0);
        TextView toolView = Widgets.mono(this, msg.optString("tool", "tool"), Theme.INK, 12.5f);
        toolView.setBackground(Theme.rounded(this, 0x40000000, 6));
        int tp = Theme.dp(this, 8);
        toolView.setPadding(tp, Theme.dp(this, 2), tp, Theme.dp(this, 2));
        head.addView(tag);
        head.addView(toolView);
        card.addView(head);

        TextView pre = Widgets.mono(this, prettyJson(msg.optJSONObject("input")), Theme.INK, 12.5f);
        pre.setBackground(Theme.rounded(this, 0x47000000, 10, Theme.withAlpha(Theme.AWAITING, 0x22), 1));
        int pp = Theme.dp(this, 11);
        pre.setPadding(pp, pp, pp, pp);
        LinearLayout.LayoutParams preLp = lp(MATCH, WRAP);
        preLp.topMargin = Theme.dp(this, 11);
        pre.setLayoutParams(preLp);
        card.addView(pre);

        LinearLayout buttons = Widgets.row(this);
        LinearLayout.LayoutParams btnRowLp = lp(MATCH, WRAP);
        btnRowLp.topMargin = Theme.dp(this, 11);
        buttons.setLayoutParams(btnRowLp);
        TextView deny = Widgets.ghostButton(this, "Deny");
        deny.setLayoutParams(lp(0, Theme.dp(this, 44), 1f));
        TextView allow = Widgets.primaryButton(this, "Allow");
        LinearLayout.LayoutParams allowLp = lp(0, Theme.dp(this, 44), 1f);
        allowLp.leftMargin = Theme.dp(this, 10);
        allow.setLayoutParams(allowLp);
        deny.setOnClickListener(v -> respondApproval(id, "deny", card));
        allow.setOnClickListener(v -> respondApproval(id, "allow", card));
        buttons.addView(deny);
        buttons.addView(allow);
        card.addView(buttons);
        card.setTag(buttons); // remember the button row for finalize()

        append(card);
        pendingApprovalCard = card;
        pendingApprovalId = id;
    }

    private void respondApproval(String id, String behavior, View card) {
        if (socket != null) {
            try {
                JSONObject out = new JSONObject();
                out.put("type", "approval_response");
                out.put("request_id", id);
                out.put("behavior", behavior);
                socket.send(out.toString());
            } catch (Exception ignored) {}
        }
        finalizeApproval(card, behavior);
        if (id.equals(pendingApprovalId)) {
            pendingApprovalCard = null;
            pendingApprovalId = null;
        }
    }

    private void resolveApproval(String id, String behavior) {
        if (id.equals(pendingApprovalId) && pendingApprovalCard != null) {
            finalizeApproval(pendingApprovalCard, behavior);
            pendingApprovalCard = null;
            pendingApprovalId = null;
        }
    }

    private void finalizeApproval(View card, String behavior) {
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
    }

    private void addError(String message) {
        LinearLayout wrap = Widgets.column(this);
        TextView labelView = Widgets.text(this, "ERROR", Theme.DANGER, 11, true);
        labelView.setLetterSpacing(0.07f);
        Widgets.margins(labelView, 0, 0, 0, Theme.dp(this, 5));
        TextView text = Widgets.text(this, message, Theme.DANGER, 14, false);
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

    private void sendPrompt() {
        if (!input.isEnabled()) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty() || socket == null) return;
        try {
            JSONObject out = new JSONObject();
            out.put("type", "input");
            out.put("text", text);
            if (socket.send(out.toString())) {
                input.setText("");
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

    private static String firstString(JSONObject in, String... keys) {
        for (String k : keys) {
            String v = in.optString(k, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static String toolSummary(String tool, JSONObject in) {
        if (in == null) return "";
        switch (tool) {
            case "Bash":
                return in.optString("command", "");
            case "Read":
            case "Write":
            case "Edit":
            case "MultiEdit":
                return firstString(in, "file_path", "path");
            case "Glob":
            case "Grep":
                return firstString(in, "pattern", "query");
            case "WebFetch":
            case "WebSearch":
                return firstString(in, "url", "query");
            case "Task":
                return firstString(in, "description");
            default:
                String direct = firstString(in, "command", "file_path", "path", "pattern", "query", "url", "description");
                if (!direct.isEmpty()) return direct;
                java.util.Iterator<String> it = in.keys();
                while (it.hasNext()) {
                    Object v = in.opt(it.next());
                    if (v instanceof String && !((String) v).isEmpty()) return (String) v;
                }
                return "";
        }
    }
}
