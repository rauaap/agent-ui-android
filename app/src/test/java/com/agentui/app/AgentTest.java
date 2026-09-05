package com.agentui.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the agent list's pure parts — which agent is preselected and
 * how an id becomes a label. Parsing itself is not covered: org.json is a stub
 * in JVM unit tests, so a real {@code JSONObject} cannot be built here.
 */
public class AgentTest {

    private static final List<Agent> THREE = Arrays.asList(
            new Agent("claude-code", "Claude Code", false),
            new Agent("opencode", "OpenCode", true),
            new Agent("pi", "pi", false));

    @Test
    public void theFlaggedAgentIsPreselected() {
        assertEquals(1, Agent.defaultIndex(THREE));
    }

    @Test
    public void aSavedPreferenceIsPreselected() {
        assertEquals(2, Agent.defaultIndex(THREE, "pi"));
    }

    @Test
    public void anUnavailablePreferenceFallsBackToTheServerDefault() {
        assertEquals(1, Agent.defaultIndex(THREE, "codex"));
    }

    @Test
    public void withNoFlagTheFirstListedWins() {
        // The server lists adapters in registration order, so the first is as
        // good a pick as any — and better than an out-of-range index.
        List<Agent> unflagged = Arrays.asList(
                new Agent("opencode", "OpenCode", false),
                new Agent("pi", "pi", false));
        assertEquals(0, Agent.defaultIndex(unflagged));
        assertEquals(0, Agent.defaultIndex(new ArrayList<>()));
    }

    @Test
    public void idsBecomeTheServersLabel() {
        assertEquals("OpenCode", Agent.label(THREE, "opencode"));
    }

    @Test
    public void anUnknownIdIsShownAsItself() {
        // A session outlives the adapter it was created with; its card still
        // has to render, and the raw id says more than nothing does.
        assertEquals("codex", Agent.label(THREE, "codex"));
    }

    @Test
    public void theFallbackListOffersOnlyTheDefaultAgent() {
        assertEquals(1, Agent.FALLBACK.size());
        assertEquals("Claude Code", Agent.label(Agent.FALLBACK, "claude-code"));
        assertEquals(0, Agent.defaultIndex(Agent.FALLBACK));
    }
}
