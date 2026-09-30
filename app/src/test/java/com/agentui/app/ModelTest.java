package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

/** Unit tests for {@code GET /models} parsing and the model picker's rules. */
public class ModelTest {

    private static final String BODY = "{"
            + "\"claude-code\":{\"models\":[{\"id\":\"claude-opus-5-5\",\"name\":\"Opus 5.5\"}],\"error\":null},"
            + "\"pi\":{\"models\":[],\"error\":\"pi --list-models timed out\"}"
            + "}";

    private static Map<String, Model.Catalog> catalogs() throws Exception {
        return Model.catalogs(new JSONObject(BODY));
    }

    @Test
    public void aDiscoveredCatalogCanCreateSessions() throws Exception {
        Model.Catalog c = Model.forAgent(catalogs(), "claude-code");
        assertNull(Model.unavailable(catalogs(), null, "claude-code"));
        assertNull(c.error);
        assertEquals(1, c.models.size());
        assertEquals("claude-opus-5-5", c.models.get(0).id);
        assertEquals("Opus 5.5", c.models.get(0).name);
    }

    @Test
    public void aFailedCatalogKeepsItsErrorWithoutAffectingTheOther() throws Exception {
        Map<String, Model.Catalog> all = catalogs();
        assertEquals("Model list unavailable: pi --list-models timed out",
                Model.unavailable(all, null, "pi"));
        assertNull(Model.unavailable(all, null, "claude-code"));
    }

    @Test
    public void idsAreOpaque() throws Exception {
        Model.Catalog c = Model.catalog(new JSONObject(
                "{\"models\":[{\"id\":\"openai-codex/gpt-5.5\",\"name\":\"gpt-5.5\"}],\"error\":null}"));
        assertEquals("openai-codex/gpt-5.5", c.models.get(0).id);
    }

    @Test
    public void aMissingNameFallsBackToTheIdAndIdlessEntriesAreDropped() throws Exception {
        Model.Catalog c = Model.catalog(new JSONObject(
                "{\"models\":[{\"id\":\"haiku\"},{\"name\":\"nameless\"}],\"error\":null}"));
        assertEquals(1, c.models.size());
        assertEquals("haiku", c.models.get(0).name);
    }

    @Test
    public void aCatalogWithoutModelsBlocksCreationEvenWithoutAnError() throws Exception {
        Map<String, Model.Catalog> empty = Model.catalogs(new JSONObject(
                "{\"pi\":{\"models\":[],\"error\":null}}"));
        assertNotNull(Model.unavailable(empty, null, "pi"));
    }

    @Test
    public void anUnlistedAgentBlocksCreation() throws Exception {
        assertNull(Model.forAgent(catalogs(), "opencode"));
        assertNotNull(Model.unavailable(catalogs(), null, "opencode"));
        assertNotNull(Model.unavailable(null, null, "claude-code"));
    }

    @Test
    public void aFailedFetchBlocksEveryAgent() throws Exception {
        // Including one whose catalog would otherwise be fine: there is no
        // fallback to letting the harness choose.
        assertEquals("Model list unavailable: Server error 404",
                Model.unavailable(catalogs(), "Server error 404", "claude-code"));
    }

    @Test
    public void labels() throws Exception {
        Map<String, Model.Catalog> all = catalogs();
        assertNull(Model.label(all, "claude-code", null));
        assertEquals("Opus 5.5", Model.label(all, "claude-code", "claude-opus-5-5"));
        // A model no longer in the startup catalog still renders, as itself.
        assertEquals("claude-sonnet-5", Model.label(all, "claude-code", "claude-sonnet-5"));
        // Lookup is scoped to the session's own harness.
        assertEquals("claude-opus-5-5", Model.label(all, "pi", "claude-opus-5-5"));
    }

    @Test
    public void sessionModelIsNullOnlyForLegacySessions() throws Exception {
        assertNull(Session.from(new JSONObject("{\"id\":1}")).model);
        assertNull(Session.from(new JSONObject("{\"id\":1,\"model\":null}")).model);
        assertEquals("openai-codex/gpt-5.5", Session.from(new JSONObject(
                "{\"id\":1,\"model\":\"openai-codex/gpt-5.5\"}")).model);
    }
}
