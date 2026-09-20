package com.agentui.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/**
 * Subscription usage — how much of each plan's five-hour and weekly quota is
 * already spent, one meter per window.
 *
 * <p>Reached from the project list rather than from a session, because the
 * quotas belong to <em>plans</em> and not to agents: one subscription can back
 * several harnesses, so no session's agent picks out a row here.
 *
 * <p>Each call queries both providers live, so this refreshes about once a
 * minute while it is open — and every countdown on screen is recomputed from
 * the reset time that arrived with that read. Codex's five-hour window rolls
 * until usage starts, so counting one down locally would drift away from the
 * truth within a poll or two.
 */
public class UsageActivity extends Activity {

    /** Live reads, not a live clock: a minute matches how fast the numbers move. */
    private static final long POLL_MS = 60_000L;

    private static final DateTimeFormatter RESET_FMT =
            DateTimeFormatter.ofPattern("MMM d, h:mm a");
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("h:mm a");

    private Api api;
    private Prefs prefs;
    private ForegroundPoller poller;
    private TextView subtitle;
    private LinearLayout listContainer;

    /** What is on screen: the newest read, with any transient gaps filled in. */
    private Usage current;
    private boolean inFlight;
    /** Set once the server 404s on /usage, so we stop asking it. */
    private boolean unsupported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        prefs = api.prefs();
        poller = new ForegroundPoller(POLL_MS, this::load);
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.isConfigured()) {
            subtitle.setText("not configured");
            listContainer.removeAllViews();
            listContainer.addView(emptyBox("No server set.\n\nSet the server address in "
                    + "Settings, then come back."));
            return;
        }
        // A server with no /usage keeps whatever this already rendered; there is
        // nothing to poll it for.
        if (unsupported) return;
        load();
        poller.start();
    }

    @Override
    protected void onPause() {
        poller.stop();
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

        LinearLayout header = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        header.setPadding(pad, pad, pad, pad);

        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);

        LinearLayout titles = Widgets.column(this);
        titles.setLayoutParams(lp(0, WRAP, 1f));
        titles.addView(Widgets.text(this, "Usage", Theme.INK, 18, true));
        subtitle = Widgets.text(this, "", Theme.MUTED, 13, false);
        Widgets.margins(subtitle, 0, Theme.dp(this, 2), 0, 0);
        titles.addView(subtitle);
        header.addView(titles);

        TextView refresh = Widgets.ghostButton(this, "↻");
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        refresh.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        refresh.setOnClickListener(v -> load());
        header.addView(refresh);
        root.addView(header);

        View div = new View(this);
        div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        div.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(div);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        scroll.setFillViewport(true);
        listContainer = Widgets.column(this);
        listContainer.setPadding(pad, pad, pad, pad);
        scroll.addView(listContainer);
        root.addView(scroll);

        return root;
    }

    /* ---------------------------------------------------------------- */
    /* data                                                             */
    /* ---------------------------------------------------------------- */

    private void load() {
        if (unsupported || inFlight || !prefs.isConfigured()) return;
        inFlight = true;
        if (current == null) subtitle.setText("…");
        api.getUsage(new Api.StatusCb<Usage>() {
            @Override public void onResult(Usage usage) {
                inFlight = false;
                // Fold over what is on screen so a plan that failed transiently
                // keeps its last good numbers instead of blanking.
                current = Usage.merge(current, usage);
                render();
            }

            @Override public void onError(String message) {
                inFlight = false;
                // A failure here is this app's own hop to the server — the
                // providers report their trouble inside a 200. Keep whatever is
                // already rendered and say the refresh didn't land.
                if (current == null) {
                    subtitle.setText("unreachable");
                    listContainer.removeAllViews();
                    listContainer.addView(emptyBox(message
                            + "\n\nCheck the server address in Settings."));
                } else {
                    subtitle.setText("couldn't refresh  ·  " + message);
                }
            }

            @Override public void onHttpError(int code, String message) {
                inFlight = false;
                if (code == 404) {
                    unsupported = true;
                    poller.stop();
                    subtitle.setText("unsupported");
                    listContainer.removeAllViews();
                    listContainer.addView(emptyBox("This server doesn't report subscription "
                            + "usage.\n\nIt needs a backend with GET /usage."));
                } else {
                    onError(message);
                }
            }
        });
    }

    private void render() {
        listContainer.removeAllViews();
        long nowSeconds = System.currentTimeMillis() / 1000L;

        long readAt = Math.max(current.claudeCode.readAtMillis, current.codex.readAtMillis);
        subtitle.setText("updated " + format(readAt, CLOCK_FMT));

        for (Usage.Plan plan : current.plans()) {
            listContainer.addView(planCard(plan, nowSeconds));
        }

        // The keys are plans, and a reader who knows the agent picker will
        // otherwise try to match them up with it.
        TextView note = Widgets.text(this,
                "Subscription plans, not agents — one plan can back more than one agent, so "
                        + "a session's agent doesn't decide which quota it spends.",
                Theme.FAINT, 12.5f, false);
        Widgets.margins(note, 0, Theme.dp(this, 4), 0, 0);
        listContainer.addView(note);
    }

    /* ---------------------------------------------------------------- */
    /* cards                                                            */
    /* ---------------------------------------------------------------- */

    private View planCard(Usage.Plan plan, long nowSeconds) {
        LinearLayout card = Widgets.column(this);
        card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(this, 12);
        card.setLayoutParams(cardLp);

        LinearLayout head = Widgets.row(this);
        TextView name = Widgets.text(this, plan.label, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);
        TextView tag = planTag(plan);
        if (tag != null) {
            Widgets.margins(tag, Theme.dp(this, 8), 0, 0, 0);
            head.addView(tag);
        }
        card.addView(head);

        if (plan.hasWindows()) {
            card.addView(windowBlock("5-hour", plan.fiveHour, plan, nowSeconds, 16));
            card.addView(windowBlock("Weekly", plan.weekly, plan, nowSeconds, 18));
        }

        String detail = detail(plan);
        if (detail != null) {
            TextView text = Widgets.text(this, detail, detailColor(plan), 12.5f, false);
            Widgets.margins(text, 0, Theme.dp(this, plan.hasWindows() ? 16 : 12), 0, 0);
            card.addView(text);
        }

        return card;
    }

    /**
     * One window: its share spent, the meter, and when it starts over. A window
     * the provider didn't report is shown as such rather than as zero — the two
     * mean very different things to someone deciding whether to keep working.
     */
    private View windowBlock(String label, Usage.Window window, Usage.Plan plan,
                             long nowSeconds, int topDp) {
        LinearLayout block = Widgets.column(this);
        LinearLayout.LayoutParams blockLp = lp(MATCH, WRAP);
        blockLp.topMargin = Theme.dp(this, topDp);
        block.setLayoutParams(blockLp);

        LinearLayout head = Widgets.row(this);
        TextView caption = Widgets.text(this, label, Theme.MUTED, 13, false);
        caption.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(caption);
        // The value wears ink, not the meter's colour: the bar carries severity,
        // the number carries the reading.
        head.addView(Widgets.text(this,
                window == null ? "—" : window.percentLabel(), Theme.INK, 15, true));
        block.addView(head);

        Meter meter = new Meter(this);
        LinearLayout.LayoutParams meterLp = lp(MATCH, Theme.dp(this, 8));
        meterLp.topMargin = Theme.dp(this, 9);
        meter.setLayoutParams(meterLp);
        meter.set(window == null ? 0f : window.fraction(),
                window == null ? Theme.IDLE : severityColor(window.severity()));
        block.addView(meter);

        TextView foot = Widgets.text(this, resetLine(window, plan, nowSeconds),
                Theme.FAINT, 12, false);
        Widgets.margins(foot, 0, Theme.dp(this, 7), 0, 0);
        block.addView(foot);

        return block;
    }

    private String resetLine(Usage.Window window, Usage.Plan plan, long nowSeconds) {
        if (window == null) return "not reported";
        // A carried-over reset time is the one thing not worth showing: it was
        // read before the failure and, for a rolling window, has moved since.
        if (plan.stale) return "last known value";
        String countdown = Usage.countdown(window.resetAt, nowSeconds);
        if (countdown.isEmpty()) return "reset time unavailable";
        return "resets " + countdown + "  ·  " + format(window.resetAt * 1000L, RESET_FMT);
    }

    private TextView planTag(Usage.Plan plan) {
        if (plan.stale) return Widgets.tag(this, "stale", Theme.AWAITING);
        switch (plan.trouble()) {
            case UNAUTHENTICATED: return Widgets.tag(this, "not configured", Theme.MUTED);
            case EXPIRED: return Widgets.tag(this, "sign in again", Theme.DANGER);
            case UNREADABLE: return Widgets.tag(this, "unreadable", Theme.DANGER);
            case TRANSIENT:
                return plan.hasWindows() ? null : Widgets.tag(this, "unavailable", Theme.AWAITING);
            default: return null;
        }
    }

    /** The sentence under a plan, or null when the numbers speak for themselves. */
    private String detail(Usage.Plan plan) {
        if (plan.stale) {
            return "Showing the last good read, from " + format(plan.readAtMillis, CLOCK_FMT)
                    + ".  " + plan.error + " — the next refresh retries.";
        }
        switch (plan.trouble()) {
            case UNAUTHENTICATED:
                return "No credentials for this plan on the server, so it has no quota to report.";
            case EXPIRED:
                return "The server's token was rejected (" + plan.error + "). Re-authenticate on "
                        + "the server — it does not refresh tokens on its own.";
            case UNREADABLE:
                return "The provider sent back something the server couldn't read: " + plan.error
                        + ". Worth reporting.";
            case TRANSIENT:
                return plan.error + " — the next refresh retries.";
            default:
                return plan.hasWindows() ? null : "No usage reported for this plan.";
        }
    }

    private int detailColor(Usage.Plan plan) {
        if (plan.stale) return Theme.MUTED;
        switch (plan.trouble()) {
            case EXPIRED:
            case UNREADABLE: return Theme.DANGER;
            default: return Theme.MUTED;
        }
    }

    /**
     * The meter's fill. Plenty left, getting close, nearly gone — the app's own
     * status colours, and never the only thing saying so: the percentage sits
     * beside every bar.
     */
    private static int severityColor(Usage.Severity severity) {
        switch (severity) {
            case CRITICAL: return Theme.DANGER;
            case HIGH: return Theme.AWAITING;
            default: return Theme.RUNNING;
        }
    }

    /* ---------------------------------------------------------------- */
    /* helpers                                                          */
    /* ---------------------------------------------------------------- */

    private static String format(long epochMillis, DateTimeFormatter formatter) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter);
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
        box.setLayoutParams(lp(MATCH, Theme.dp(this, 180)));
        int pad = Theme.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }
}
