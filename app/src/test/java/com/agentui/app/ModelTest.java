package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/** Unit tests for a catalog model entry and a session's model and reasoning level. */
public class ModelTest {

    @Test
    public void idsAreOpaque() throws Exception {
        Model m = Model.from(new JSONObject(
                "{\"id\":\"openai-codex/gpt-5.5\",\"name\":\"gpt-5.5\",\"reasoning_levels\":[]}"));
        assertEquals("openai-codex/gpt-5.5", m.id);
        assertEquals("gpt-5.5", m.name);
    }

    @Test
    public void reasoningLevelsKeepTheHarnessesVocabularyAndOrder() throws Exception {
        Model m = Model.from(new JSONObject("{\"id\":\"claude-opus-5-5\",\"name\":\"Opus 5.5\","
                + "\"reasoning_levels\":[\"low\",\"medium\",\"high\",\"xhigh\",\"max\"]}"));
        assertEquals(Arrays.asList("low", "medium", "high", "xhigh", "max"), m.reasoningLevels);
        Model haiku = Model.from(new JSONObject(
                "{\"id\":\"claude-haiku-4-5-20251001\",\"name\":\"Haiku 4.5\",\"reasoning_levels\":[]}"));
        assertTrue(haiku.reasoningLevels.isEmpty());
    }

    @Test(expected = org.json.JSONException.class)
    public void reasoningLevelsAreRequired() throws Exception {
        Model.from(new JSONObject("{\"id\":\"m\",\"name\":\"M\"}"));
    }

    @Test
    public void aNullLevelReadsAsDefault() {
        assertEquals("Default", Model.reasoningLabel(null));
        assertEquals("xhigh", Model.reasoningLabel("xhigh"));
    }

    @Test
    public void sessionModelIsNullOnlyForLegacySessions() throws Exception {
        assertNull(Session.from(new JSONObject("{\"id\":1}")).model);
        assertNull(Session.from(new JSONObject("{\"id\":1,\"model\":null}")).model);
        assertEquals("openai-codex/gpt-5.5", Session.from(new JSONObject(
                "{\"id\":1,\"model\":\"openai-codex/gpt-5.5\"}")).model);
    }

    @Test
    public void sessionReasoningLevelIsNullWhenTheHarnessPicks() throws Exception {
        assertNull(Session.from(new JSONObject("{\"id\":1,\"reasoning_level\":null}")).reasoningLevel);
        assertEquals("high", Session.from(new JSONObject(
                "{\"id\":1,\"reasoning_level\":\"high\"}")).reasoningLevel);
    }

    @Test
    public void aLiveReasoningEventWinsOverAnInflightRefresh() throws Exception {
        SessionState state = new SessionState();
        long started = SessionState.snapshot();
        state.reasoningLevel("high");
        state.accept(Session.from(new JSONObject("{\"id\":1,\"reasoning_level\":\"low\"}")), started);
        assertEquals("high", state.session.reasoningLevel);
        // A refresh started after the event is newer, and is taken as is.
        state.accept(Session.from(new JSONObject("{\"id\":1,\"reasoning_level\":\"max\"}")),
                SessionState.snapshot());
        assertEquals("max", state.session.reasoningLevel);
    }

    @Test
    public void aPatchResponseUpdatesTheLiveLevel() throws Exception {
        SessionState state = new SessionState();
        long started = SessionState.snapshot();
        state.confirmed(Session.from(new JSONObject("{\"id\":1,\"reasoning_level\":\"xhigh\"}")), started);
        state.accept(Session.from(new JSONObject("{\"id\":1,\"reasoning_level\":\"low\"}")), started);
        assertEquals("xhigh", state.session.reasoningLevel);
    }
}
