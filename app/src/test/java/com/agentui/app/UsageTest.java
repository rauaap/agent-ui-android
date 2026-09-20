package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for reading {@code GET /usage}. Everything the spec warns about
 * lives here: the two plans are read independently, an error never condemns the
 * whole response, and a transient failure keeps the numbers already on screen.
 */
public class UsageTest {

    private static final long READ_AT = 1_789_000_000_000L;

    private static Usage parse(String json) throws Exception {
        return Usage.from(new JSONObject(json), READ_AT);
    }

    private static final String FULL =
            "{\"claude_code\":{\"five_hour\":{\"used_percent\":23.0,\"reset_at\":1789933800},"
            + "\"weekly\":{\"used_percent\":12.0,\"reset_at\":1790218800},\"error\":null},"
            + "\"codex\":{\"five_hour\":{\"used_percent\":6.0,\"reset_at\":1789936104},"
            + "\"weekly\":{\"used_percent\":51.0,\"reset_at\":1790415517},\"error\":null}}";

    @Test
    public void parsesBothPlans() throws Exception {
        Usage usage = parse(FULL);

        assertEquals("Claude Code", usage.claudeCode.label);
        assertEquals(23.0, usage.claudeCode.fiveHour.usedPercent, 0.001);
        assertEquals(Long.valueOf(1789933800L), usage.claudeCode.fiveHour.resetAt);
        assertEquals(12.0, usage.claudeCode.weekly.usedPercent, 0.001);

        assertEquals("Codex", usage.codex.label);
        assertEquals(6.0, usage.codex.fiveHour.usedPercent, 0.001);
        assertEquals(51.0, usage.codex.weekly.usedPercent, 0.001);

        for (Usage.Plan plan : usage.plans()) {
            assertEquals(Usage.Trouble.NONE, plan.trouble());
            assertFalse(plan.stale);
            assertTrue(plan.hasWindows());
        }
    }

    /** A user who has authenticated only one plan still gets a usable response. */
    @Test
    public void oneBadPlanLeavesTheOtherReadable() throws Exception {
        Usage usage = parse("{\"claude_code\":{\"five_hour\":{\"used_percent\":23.0,"
                + "\"reset_at\":1789933800},\"weekly\":{\"used_percent\":12.0,"
                + "\"reset_at\":1790218800},\"error\":null},"
                + "\"codex\":{\"five_hour\":null,\"weekly\":null,"
                + "\"error\":\"not authenticated\"}}");

        assertTrue(usage.claudeCode.hasWindows());
        assertEquals(23.0, usage.claudeCode.fiveHour.usedPercent, 0.001);

        assertFalse(usage.codex.hasWindows());
        assertNull(usage.codex.fiveHour);
        assertEquals(Usage.Trouble.UNAUTHENTICATED, usage.codex.trouble());
    }

    /** A null reset_at is a successful read with no countdown, not a failure. */
    @Test
    public void nullResetIsAWindowWithoutACountdown() throws Exception {
        Usage usage = parse("{\"claude_code\":{\"five_hour\":{\"used_percent\":4.0,"
                + "\"reset_at\":null},\"weekly\":null,\"error\":null}}");

        assertNotNull(usage.claudeCode.fiveHour);
        assertNull(usage.claudeCode.fiveHour.resetAt);
        assertEquals(Usage.Trouble.NONE, usage.claudeCode.trouble());
        assertEquals("", Usage.countdown(null, 1789933800L));
    }

    /** A key the server omitted reads as "nothing reported", never as zero used. */
    @Test
    public void missingPlanIsNotZeroUsage() throws Exception {
        Usage usage = parse("{\"claude_code\":{\"five_hour\":{\"used_percent\":9.0,"
                + "\"reset_at\":1789933800},\"weekly\":null,\"error\":null}}");

        assertFalse(usage.codex.hasWindows());
        assertNull(usage.codex.weekly);
        assertEquals(Usage.Trouble.NONE, usage.codex.trouble());
    }

    @Test
    public void classifiesTheDocumentedReasons() {
        assertEquals(Usage.Trouble.NONE, Usage.classify(null));
        assertEquals(Usage.Trouble.NONE, Usage.classify(""));
        assertEquals(Usage.Trouble.UNAUTHENTICATED, Usage.classify("not authenticated"));
        assertEquals(Usage.Trouble.EXPIRED, Usage.classify("HTTP 401"));
        assertEquals(Usage.Trouble.TRANSIENT, Usage.classify("HTTP 503"));
        assertEquals(Usage.Trouble.TRANSIENT, Usage.classify("unreachable: timed out"));
        assertEquals(Usage.Trouble.UNREADABLE,
                Usage.classify("unreadable response: expected object"));
        // A reason no release has seen yet is far likelier to be a new upstream
        // hiccup than a permanent state worth clearing the screen for.
        assertEquals(Usage.Trouble.TRANSIENT, Usage.classify("something new"));
    }

    /* ---------------------------------------------------------------- */
    /* merging                                                          */
    /* ---------------------------------------------------------------- */

    @Test
    public void transientFailureKeepsTheLastGoodValues() throws Exception {
        Usage previous = parse(FULL);
        Usage fresh = parse("{\"claude_code\":{\"five_hour\":null,\"weekly\":null,"
                + "\"error\":\"unreachable: timed out\"},"
                + "\"codex\":{\"five_hour\":{\"used_percent\":7.0,\"reset_at\":1789936104},"
                + "\"weekly\":{\"used_percent\":51.0,\"reset_at\":1790415517},\"error\":null}}");

        Usage merged = Usage.merge(previous, fresh);

        assertTrue(merged.claudeCode.stale);
        assertEquals(23.0, merged.claudeCode.fiveHour.usedPercent, 0.001);
        assertEquals("unreachable: timed out", merged.claudeCode.error);
        // The carried-over read keeps the time it was actually taken.
        assertEquals(READ_AT, merged.claudeCode.readAtMillis);

        // The plan that read fine is untouched by its neighbour's trouble.
        assertFalse(merged.codex.stale);
        assertEquals(7.0, merged.codex.fiveHour.usedPercent, 0.001);
    }

    /** Losing authentication is news; it must not hide behind old numbers. */
    @Test
    public void authenticationFailureReplacesTheLastGoodValues() throws Exception {
        Usage previous = parse(FULL);
        Usage fresh = parse("{\"claude_code\":{\"five_hour\":null,\"weekly\":null,"
                + "\"error\":\"HTTP 401\"},"
                + "\"codex\":{\"five_hour\":null,\"weekly\":null,"
                + "\"error\":\"not authenticated\"}}");

        Usage merged = Usage.merge(previous, fresh);

        assertFalse(merged.claudeCode.stale);
        assertFalse(merged.claudeCode.hasWindows());
        assertEquals(Usage.Trouble.EXPIRED, merged.claudeCode.trouble());
        assertFalse(merged.codex.stale);
        assertEquals(Usage.Trouble.UNAUTHENTICATED, merged.codex.trouble());
    }

    @Test
    public void freshValuesAlwaysWin() throws Exception {
        Usage previous = parse(FULL);
        Usage fresh = parse("{\"claude_code\":{\"five_hour\":{\"used_percent\":88.0,"
                + "\"reset_at\":1789933800},\"weekly\":{\"used_percent\":90.0,"
                + "\"reset_at\":1790218800},\"error\":null},"
                + "\"codex\":{\"five_hour\":{\"used_percent\":6.0,\"reset_at\":1789936104},"
                + "\"weekly\":{\"used_percent\":51.0,\"reset_at\":1790415517},\"error\":null}}");

        Usage merged = Usage.merge(previous, fresh);

        assertEquals(88.0, merged.claudeCode.fiveHour.usedPercent, 0.001);
        assertFalse(merged.claudeCode.stale);
    }

    @Test
    public void firstReadHasNothingToMergeOver() throws Exception {
        Usage fresh = parse(FULL);
        assertSame(fresh, Usage.merge(null, fresh));
    }

    /* ---------------------------------------------------------------- */
    /* formatting                                                       */
    /* ---------------------------------------------------------------- */

    @Test
    public void percentDropsAPointlessDecimal() {
        assertEquals("23%", new Usage.Window(23.0, null).percentLabel());
        assertEquals("6%", new Usage.Window(6.04, null).percentLabel());
        assertEquals("6.5%", new Usage.Window(6.54, null).percentLabel());
        assertEquals("0%", new Usage.Window(0.0, null).percentLabel());
        assertEquals("100%", new Usage.Window(100.0, null).percentLabel());
    }

    @Test
    public void fillIsClampedToTheTrack() {
        assertEquals(0f, new Usage.Window(0, null).fraction(), 0.0001f);
        assertEquals(0.23f, new Usage.Window(23, null).fraction(), 0.0001f);
        assertEquals(1f, new Usage.Window(100, null).fraction(), 0.0001f);
        // A provider that overshoots fills the bar; it does not draw past it.
        assertEquals(1f, new Usage.Window(140, null).fraction(), 0.0001f);
        assertEquals(0f, new Usage.Window(-5, null).fraction(), 0.0001f);
    }

    @Test
    public void severityTracksHowMuchIsLeft() {
        assertEquals(Usage.Severity.NORMAL, Usage.severity(0));
        assertEquals(Usage.Severity.NORMAL, Usage.severity(69.9));
        assertEquals(Usage.Severity.HIGH, Usage.severity(70));
        assertEquals(Usage.Severity.HIGH, Usage.severity(89.9));
        assertEquals(Usage.Severity.CRITICAL, Usage.severity(90));
        assertEquals(Usage.Severity.CRITICAL, Usage.severity(100));
    }

    @Test
    public void countdownReadsInWholeUnits() {
        long now = 1_789_000_000L;
        assertEquals("in under a minute", Usage.countdown(now + 30, now));
        assertEquals("in 1m", Usage.countdown(now + 60, now));
        assertEquals("in 59m", Usage.countdown(now + 59 * 60, now));
        assertEquals("in 2h", Usage.countdown(now + 2 * 3600, now));
        assertEquals("in 2h 14m", Usage.countdown(now + 2 * 3600 + 14 * 60, now));
        assertEquals("in 3d 6h", Usage.countdown(now + 3 * 86400 + 6 * 3600, now));
        assertEquals("in 3d", Usage.countdown(now + 3 * 86400, now));
        assertEquals("now", Usage.countdown(now, now));
        // A window whose reset has already passed on the server's clock.
        assertEquals("now", Usage.countdown(now - 500, now));
    }
}
