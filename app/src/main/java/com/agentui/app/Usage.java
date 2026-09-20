package com.agentui.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Subscription usage — what {@code GET /usage} reports about how much of each
 * plan's five-hour and weekly quota is already spent.
 *
 * <p>The two keys name <em>plans</em>, not agents: {@code claude_code} and
 * {@code codex} deliberately do not match the agent ids from {@code GET /agents},
 * because one subscription can back several harnesses. Nothing here maps
 * {@code codex} onto an adapter or reads a session's agent as the quota it draws
 * from; both are labelled by plan.
 *
 * <p>Both keys are always present, and either can carry an {@code error} of its
 * own while the other reads fine — so each plan is read independently and a
 * non-null error never condemns the whole response.
 */
final class Usage {

    static final String CLAUDE_CODE = "claude_code";
    static final String CODEX = "codex";

    /** How a plan's own {@code error} string should be acted on. */
    enum Trouble {
        /** No error: the windows are what the provider just said. */
        NONE,
        /** No credentials for this plan on the server; show it as unconfigured. */
        UNAUTHENTICATED,
        /** The token was rejected. The server does not refresh them, so a human must. */
        EXPIRED,
        /** Upstream hiccup or timeout — retry on the next poll, keep the last good values. */
        TRANSIENT,
        /** Upstream returned a shape the server couldn't read. Unavailable, and worth reporting. */
        UNREADABLE
    }

    /** Where a window's consumption sits against the thresholds worth colouring. */
    enum Severity { NORMAL, HIGH, CRITICAL }

    /** One quota window — how much of it is gone, and when it starts over. */
    static final class Window {
        /**
         * The share of the window already <em>spent</em>, 0–100, not the share
         * remaining. Rendered as a percentage exactly as it arrives.
         */
        final double usedPercent;
        /**
         * Unix seconds when the window resets, or null. A null is legitimate on
         * a successful read, and means the countdown is unavailable — never an
         * epoch date.
         */
        final Long resetAt;

        Window(double usedPercent, Long resetAt) {
            this.usedPercent = usedPercent;
            this.resetAt = resetAt;
        }

        /** The meter's fill, clamped: a provider over 100 fills the bar, not past it. */
        float fraction() {
            if (Double.isNaN(usedPercent) || usedPercent <= 0) return 0f;
            return usedPercent >= 100 ? 1f : (float) (usedPercent / 100.0);
        }

        Severity severity() { return Usage.severity(usedPercent); }

        /** {@code "23%"}, keeping a decimal only when it says something. */
        String percentLabel() {
            double rounded = Math.round(usedPercent * 10.0) / 10.0;
            return String.format(Locale.US,
                    rounded == Math.rint(rounded) ? "%.0f%%" : "%.1f%%", rounded);
        }

        /** Null for an absent or null window — the two mean the same thing here. */
        static Window from(JSONObject plan, String key) {
            if (plan == null || plan.isNull(key)) return null;
            JSONObject o = plan.optJSONObject(key);
            if (o == null) return null;
            return new Window(o.optDouble("used_percent", 0),
                    o.isNull("reset_at") ? null : Long.valueOf(o.optLong("reset_at")));
        }
    }

    /** One subscription's pair of windows, plus whatever went wrong reading it. */
    static final class Plan {
        final String key;
        /** Plan name for the UI — never an agent id. */
        final String label;
        /** Null when the provider gave nothing for that window. */
        final Window fiveHour;
        final Window weekly;
        /** The server's short reason, or empty on a clean read. */
        final String error;
        /**
         * Whether these windows were carried over from an earlier read because
         * this one failed transiently. Stale windows are shown as last-known
         * values with no countdown.
         */
        final boolean stale;
        /** When the windows in hand were actually read, in epoch millis. */
        final long readAtMillis;

        Plan(String key, String label, Window fiveHour, Window weekly, String error,
             boolean stale, long readAtMillis) {
            this.key = key;
            this.label = label;
            this.fiveHour = fiveHour;
            this.weekly = weekly;
            this.error = error == null ? "" : error;
            this.stale = stale;
            this.readAtMillis = readAtMillis;
        }

        boolean hasWindows() { return fiveHour != null || weekly != null; }

        Trouble trouble() { return classify(error); }

        static Plan from(JSONObject response, String key, String label, long readAtMillis) {
            JSONObject o = response == null ? null : response.optJSONObject(key);
            String error = o == null || o.isNull("error") ? "" : o.optString("error", "");
            return new Plan(key, label, Window.from(o, "five_hour"), Window.from(o, "weekly"),
                    error, false, readAtMillis);
        }
    }

    final Plan claudeCode;
    final Plan codex;

    private Usage(Plan claudeCode, Plan codex) {
        this.claudeCode = claudeCode;
        this.codex = codex;
    }

    /** Both plans, in the order the screen lists them. */
    List<Plan> plans() { return Arrays.asList(claudeCode, codex); }

    static Usage from(JSONObject response, long readAtMillis) {
        return new Usage(
                Plan.from(response, CLAUDE_CODE, "Claude Code", readAtMillis),
                Plan.from(response, CODEX, "Codex", readAtMillis));
    }

    /**
     * Classifies a plan's error string. Anything unrecognised is treated as
     * transient: a reason this app has never seen is far more likely to be a
     * new upstream hiccup than a permanent state worth wiping the screen for.
     */
    static Trouble classify(String error) {
        if (error == null) return Trouble.NONE;
        String e = error.trim().toLowerCase(Locale.US);
        if (e.isEmpty()) return Trouble.NONE;
        if (e.equals("not authenticated")) return Trouble.UNAUTHENTICATED;
        if (e.equals("http 401")) return Trouble.EXPIRED;
        if (e.startsWith("unreadable response")) return Trouble.UNREADABLE;
        return Trouble.TRANSIENT;
    }

    static Severity severity(double usedPercent) {
        if (usedPercent >= 90) return Severity.CRITICAL;
        if (usedPercent >= 70) return Severity.HIGH;
        return Severity.NORMAL;
    }

    /**
     * Folds a fresh read over the one on screen, so a plan that just failed
     * transiently keeps its last good numbers instead of blanking.
     *
     * <p>Only transient failures carry over. An authentication state is news —
     * a plan that has just started reporting "not authenticated" should say so
     * rather than keep showing numbers that no longer have a source.
     */
    static Usage merge(Usage previous, Usage fresh) {
        if (previous == null) return fresh;
        return new Usage(carry(previous.claudeCode, fresh.claudeCode),
                carry(previous.codex, fresh.codex));
    }

    private static Plan carry(Plan previous, Plan fresh) {
        if (fresh.hasWindows()) return fresh;
        if (!previous.hasWindows()) return fresh;
        if (fresh.trouble() != Trouble.TRANSIENT) return fresh;
        return new Plan(fresh.key, fresh.label, previous.fiveHour, previous.weekly,
                fresh.error, true, previous.readAtMillis);
    }

    /**
     * How long until a window resets — {@code "in 2h 14m"} — or empty when the
     * server had no reset time to give.
     *
     * <p>Always computed against a freshly read {@code resetAt}, never counted
     * down locally: Codex's five-hour window is rolling, and its reset moves on
     * every poll until usage starts.
     */
    static String countdown(Long resetAt, long nowSeconds) {
        if (resetAt == null) return "";
        long seconds = resetAt - nowSeconds;
        if (seconds <= 0) return "now";
        long minutes = seconds / 60;
        if (minutes < 1) return "in under a minute";
        if (minutes < 60) return "in " + minutes + "m";
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        if (hours < 24) {
            return restMinutes == 0 ? "in " + hours + "h" : "in " + hours + "h " + restMinutes + "m";
        }
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours == 0 ? "in " + days + "d" : "in " + days + "d " + restHours + "h";
    }
}
