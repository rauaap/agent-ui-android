package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@code GET /agents} parsing and the agent and model pickers'
 * rules — which agent is preselected, how ids become labels, and when an
 * agent's catalog blocks session creation.
 */
public class AgentTest {

    private static final String BODY = "["
            + "{\"id\":\"claude-code\",\"name\":\"Claude Code\",\"default\":true,"
            + "\"models\":[],\"models_error\":\"Unavailable\"},"
            + "{\"id\":\"pi\",\"name\":\"Pi\",\"default\":false,"
            + "\"models\":[{\"id\":\"p/m\",\"name\":\"M\",\"reasoning_levels\":[\"off\",\"high\"]},"
            + "{\"id\":\"p/a\",\"name\":\"A\",\"reasoning_levels\":[]}],"
            + "\"models_error\":null}"
            + "]";

    private static List<Agent> catalog() throws Exception {
        return Agent.list(new JSONArray(BODY));
    }

    private static Agent agent(String id, String name, boolean isDefault) {
        return new Agent(id, name, isDefault, Collections.emptyList(), null);
    }

    private static final List<Agent> THREE = Arrays.asList(
            agent("claude-code", "Claude Code", false),
            agent("opencode", "OpenCode", true),
            agent("pi", "pi", false));

    @Test
    public void agentsAndTheirModelsKeepTheServersOrder() throws Exception {
        List<Agent> all = catalog();
        assertEquals(2, all.size());
        assertEquals("claude-code", all.get(0).id);
        assertEquals("Claude Code", all.get(0).name);
        assertTrue(all.get(0).isDefault);
        Agent pi = all.get(1);
        assertFalse(pi.isDefault);
        assertNull(pi.modelsError);
        assertEquals(2, pi.models.size());
        assertEquals("p/m", pi.models.get(0).id);
        assertEquals("M", pi.models.get(0).name);
        assertEquals("p/a", pi.models.get(1).id);
        assertEquals(Arrays.asList("off", "high"), pi.models.get(0).reasoningLevels);
        assertTrue(pi.models.get(1).reasoningLevels.isEmpty());
        assertEquals("M", pi.model("p/m").name);
        assertNull(pi.model("p/gone"));
    }

    @Test
    public void aDiscoveredCatalogCanCreateSessions() throws Exception {
        assertNull(Agent.find(catalog(), "pi").unavailable());
    }

    @Test
    public void aFailedCatalogKeepsItsErrorWithoutAffectingTheOther() throws Exception {
        Agent claude = Agent.find(catalog(), "claude-code");
        assertTrue(claude.models.isEmpty());
        assertEquals("Unavailable", claude.modelsError);
        assertEquals("Model list unavailable: Unavailable", claude.unavailable());
        assertNull(Agent.find(catalog(), "pi").unavailable());
    }

    @Test
    public void aCatalogWithoutModelsBlocksCreationEvenWithoutAnError() throws Exception {
        Agent empty = Agent.from(new JSONObject(
                "{\"id\":\"pi\",\"name\":\"Pi\",\"default\":false,\"models\":[],\"models_error\":null}"));
        assertNull(empty.modelsError);
        assertEquals("The server found no models for this agent.", empty.unavailable());
    }

    @Test
    public void modelLabels() throws Exception {
        List<Agent> all = catalog();
        assertNull(Agent.modelLabel(all, "pi", null));
        assertEquals("M", Agent.modelLabel(all, "pi", "p/m"));
        // A model no longer in the startup catalog still renders, as itself.
        assertEquals("p/gone", Agent.modelLabel(all, "pi", "p/gone"));
        // Lookup is scoped to the session's own agent.
        assertEquals("p/m", Agent.modelLabel(all, "claude-code", "p/m"));
        // And an agent the server no longer lists leaves the id as is.
        assertEquals("p/m", Agent.modelLabel(all, "codex", "p/m"));
    }

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
                agent("opencode", "OpenCode", false),
                agent("pi", "pi", false));
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
        assertEquals("codex", Agent.label(new ArrayList<>(), "codex"));
    }
}
