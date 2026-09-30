package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/** Unit tests for a catalog model entry and a session's model id. */
public class ModelTest {

    @Test
    public void idsAreOpaque() throws Exception {
        Model m = Model.from(new JSONObject("{\"id\":\"openai-codex/gpt-5.5\",\"name\":\"gpt-5.5\"}"));
        assertEquals("openai-codex/gpt-5.5", m.id);
        assertEquals("gpt-5.5", m.name);
    }

    @Test
    public void sessionModelIsNullOnlyForLegacySessions() throws Exception {
        assertNull(Session.from(new JSONObject("{\"id\":1}")).model);
        assertNull(Session.from(new JSONObject("{\"id\":1,\"model\":null}")).model);
        assertEquals("openai-codex/gpt-5.5", Session.from(new JSONObject(
                "{\"id\":1,\"model\":\"openai-codex/gpt-5.5\"}")).model);
    }
}
