package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ScrollbackHistoryTest {
    private JSONObject event(long id, String type) throws Exception {
        return new JSONObject().put("event_id", id).put("type", type);
    }

    private JSONObject row(long id, String type) throws Exception {
        return new JSONObject().put("id", id).put("type", type)
                .put("payload", new JSONObject().put("text", "hello"));
    }

    private JSONObject page(JSONArray rows, long cursor, boolean more) throws Exception {
        return new JSONObject().put("messages", rows).put("next_cursor", cursor).put("has_more", more);
    }

    @Test public void tracksInvisibleEventsAndDeduplicatesLiveReplay() throws Exception {
        ScrollbackHistory h = new ScrollbackHistory();
        assertTrue(h.accept(event(901, "approval_response")));
        assertTrue(h.accept(event(1400, "output")));
        assertFalse(h.accept(event(901, "approval_response")));
        assertEquals(901, h.oldest);
        assertFalse(h.canLoad());
        h.replayComplete = true;
        assertTrue(h.canLoad());
        h.loading = true;
        assertFalse(h.canLoad());
    }

    @Test public void prependsChronologicallyAndNormalizesInputIds() throws Exception {
        ScrollbackHistory h = new ScrollbackHistory();
        h.accept(event(900, "output"));
        assertTrue(h.prepend(page(new JSONArray().put(row(13, "input"))
                .put(row(450, "inputs_shipped")), 13, true)));
        assertEquals(13, h.oldest);
        assertEquals(3, h.events.size());
        assertEquals("input", h.events.get(0).getString("type"));
        assertEquals(13, h.events.get(0).getLong("message_id"));
        assertEquals(13, h.events.get(0).getLong("event_id"));
        assertEquals(900, h.events.get(2).getLong("event_id"));
        assertFalse(h.accept(event(450, "inputs_shipped")));
        assertTrue(h.prepend(page(new JSONArray().put(row(2, "output")), 2, false)));
        h.replayComplete = true;
        assertFalse(h.canLoad());
    }

    @Test public void emptyPageStopsAndKeepsCursor() throws Exception {
        ScrollbackHistory h = new ScrollbackHistory();
        h.accept(event(900, "output"));
        assertFalse(h.prepend(page(new JSONArray(), 900, false)));
        assertEquals(900, h.oldest);
        assertFalse(h.hasMore);
    }

    @Test public void resetInvalidatesRequestsAndEmptyReplayCannotLoad() throws Exception {
        ScrollbackHistory h = new ScrollbackHistory();
        h.accept(event(90, "output"));
        h.loading = true;
        int generation = h.generation;
        h.reset();
        assertNotEquals(generation, h.generation);
        assertFalse(h.loading);
        assertTrue(h.events.isEmpty());
        h.replayComplete = true;
        assertFalse(h.canLoad());
        assertTrue(h.accept(event(90, "output")));
    }

    @Test public void malformedPageDoesNotPartiallyMutateHistory() throws Exception {
        ScrollbackHistory h = new ScrollbackHistory();
        h.accept(event(90, "output"));
        try {
            h.prepend(page(new JSONArray().put(row(2, "input")).put(row(95, "output")), 2, true));
            fail("expected invalid ordering");
        } catch (JSONException expected) {
            assertEquals(90, h.oldest);
            assertEquals(1, h.events.size());
        }
    }

    @Test public void nonPersistedFramesDoNotChangeCursor() throws Exception {
        ScrollbackHistory h = new ScrollbackHistory();
        h.accept(event(100, "output"));
        h.accept(new JSONObject().put("type", "done"));
        assertEquals(100, h.oldest);
        assertEquals(2, h.events.size());
    }
}
