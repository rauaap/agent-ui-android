package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class StreamDraftsTest {
    private static JSONObject text(String id, int ordinal, long revision, String text) throws Exception {
        return new JSONObject().put("stream_id", id).put("turn_id", "t1").put("ordinal", ordinal)
                .put("revision", revision).put("kind", "text").put("text", text);
    }

    private static JSONObject tool(String id, int ordinal, long revision, String raw, JSONObject preview)
            throws Exception {
        return new JSONObject().put("stream_id", id).put("turn_id", "t1").put("ordinal", ordinal)
                .put("revision", revision).put("kind", "tool").put("call_id", "c-" + id)
                .put("name", "mcp__agent_ui__message_session")
                .put("arguments_json", raw).put("arguments_preview", preview);
    }

    private static JSONObject start(JSONObject draft) throws Exception {
        return new JSONObject().put("type", "stream_start").put("draft", draft);
    }

    private static JSONObject delta(String id, long revision, String delta) throws Exception {
        return new JSONObject().put("type", "stream_delta").put("stream_id", id)
                .put("revision", revision).put("delta", delta);
    }

    private static JSONObject snapshot(JSONObject... drafts) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject d : drafts) list.put(d);
        return new JSONObject().put("type", "stream_snapshot").put("drafts", list);
    }

    private static List<String> ids(List<StreamDrafts.Draft> drafts) {
        List<String> out = new ArrayList<>();
        for (StreamDrafts.Draft d : drafts) out.add(d.streamId);
        return out;
    }

    @Test public void textDeltasAppendInRevisionOrder() throws Exception {
        StreamDrafts s = new StreamDrafts();
        assertEquals(StreamDrafts.Outcome.APPLIED, s.start(start(text("s1", 0, 0, ""))));
        assertEquals(StreamDrafts.Outcome.APPLIED, s.delta(delta("s1", 1, "Hello")));
        assertEquals(StreamDrafts.Outcome.APPLIED, s.delta(delta("s1", 2, " world")));
        assertEquals("Hello world", s.get("s1").content.toString());
        assertEquals(2, s.get("s1").revision);
    }

    @Test public void startKeepsInitialContent() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(text("s1", 0, 0, "Already")));
        s.delta(delta("s1", 1, " here"));
        assertEquals("Already here", s.get("s1").content.toString());
    }

    @Test public void toolPreviewIsReplacedNotMerged() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(tool("s2", 1, 0, "", new JSONObject())));
        s.delta(delta("s2", 1, "{\"session_id\":42,\"message\":\"Hel")
                .put("arguments_preview", new JSONObject().put("session_id", 42).put("message", "Hel")));
        s.delta(delta("s2", 2, "lo\"}")
                .put("arguments_preview", new JSONObject().put("message", "Hello")));
        StreamDrafts.Draft d = s.get("s2");
        assertEquals("{\"session_id\":42,\"message\":\"Hello\"}", d.content.toString());
        assertEquals("Hello", d.preview.getString("message"));
        assertFalse(d.preview.has("session_id"));
        assertEquals("c-s2", d.callId);
    }

    @Test public void duplicateAndOlderRevisionsAreIgnored() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(text("s1", 0, 0, "")));
        s.delta(delta("s1", 1, "a"));
        assertEquals(StreamDrafts.Outcome.STALE, s.delta(delta("s1", 1, "a")));
        assertEquals(StreamDrafts.Outcome.STALE, s.start(start(text("s1", 0, 0, ""))));
        assertEquals("a", s.get("s1").content.toString());
    }

    @Test public void gapsAndUnknownDraftsDesynchronize() throws Exception {
        StreamDrafts s = new StreamDrafts();
        assertEquals(StreamDrafts.Outcome.DESYNC, s.delta(delta("nope", 1, "x")));
        s.start(start(text("s1", 0, 0, "")));
        assertEquals(StreamDrafts.Outcome.DESYNC, s.delta(delta("s1", 2, "x")));
        assertEquals("", s.get("s1").content.toString());
    }

    @Test public void snapshotReplacesEverythingAndContinuesFromItsRevision() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(text("stale", 0, 0, "old")));
        List<StreamDrafts.Draft> restored = s.replace(snapshot(
                tool("s2", 1, 2, "{\"m", new JSONObject()), text("s1", 0, 7, "Hello so far")));
        assertEquals(List.of("s1", "s2"), ids(restored));
        assertNull(s.get("stale"));
        assertEquals(StreamDrafts.Outcome.STALE, s.delta(delta("s1", 7, "x")));
        assertEquals(StreamDrafts.Outcome.APPLIED, s.delta(delta("s1", 8, "!")));
        assertEquals("Hello so far!", s.get("s1").content.toString());
        assertTrue(s.replace(snapshot()).isEmpty());
        assertTrue(s.isEmpty());
    }

    @Test public void invalidSnapshotChangesNothing() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(text("s1", 0, 0, "kept")));
        try {
            s.replace(snapshot(text("a", 0, 0, ""), text("a", 1, 0, "")));
            fail();
        } catch (IllegalArgumentException expected) {}
        try {
            s.replace(snapshot(text("b", 0, 0, "").put("extra", 1)));
            fail();
        } catch (IllegalArgumentException expected) {}
        assertEquals("kept", s.get("s1").content.toString());
    }

    @Test public void malformedFramesAreRejected() throws Exception {
        StreamDrafts s = new StreamDrafts();
        String[] bad = {
            "{\"type\":\"stream_start\",\"draft\":{\"stream_id\":\"\",\"turn_id\":\"t\",\"ordinal\":0,\"revision\":0,\"kind\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"stream_start\",\"draft\":{\"stream_id\":\"x\",\"turn_id\":\"t\",\"ordinal\":-1,\"revision\":0,\"kind\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"stream_start\",\"draft\":{\"stream_id\":\"x\",\"turn_id\":\"t\",\"ordinal\":0,\"revision\":0,\"kind\":\"thinking\",\"text\":\"\"}}",
            "{\"type\":\"stream_start\",\"draft\":{\"stream_id\":\"x\",\"turn_id\":\"t\",\"ordinal\":0,\"revision\":0,\"kind\":\"tool\",\"call_id\":\"c\",\"name\":\"n\",\"arguments_json\":\"\"}}",
            "{\"type\":\"stream_start\",\"draft\":{\"stream_id\":\"x\",\"turn_id\":\"t\",\"ordinal\":1.5,\"revision\":0,\"kind\":\"text\",\"text\":\"\"}}",
        };
        for (String frame : bad) {
            try {
                s.start(new JSONObject(frame));
                fail(frame);
            } catch (IllegalArgumentException expected) {}
        }
        s.start(start(text("s1", 0, 0, "")));
        try {
            s.delta(delta("s1", 1, "x").put("arguments_preview", new JSONObject()));
            fail("text drafts take no preview");
        } catch (IllegalArgumentException expected) {}
        s.start(start(tool("s2", 1, 0, "", new JSONObject())));
        try {
            s.delta(delta("s2", 1, "x"));
            fail("tool updates carry a preview");
        } catch (IllegalArgumentException expected) {}
        assertEquals(0, s.get("s2").revision);
    }

    @Test public void orderedBySourceOrderNotArrival() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(text("b", 2, 0, "")));
        s.start(start(text("a", 0, 0, "")));
        s.start(start(text("c", 1, 0, "")));
        assertEquals(List.of("a", "c", "b"), ids(s.ordered()));
        assertNotNull(s.retire("c"));
        assertNull(s.retire("c"));
        assertEquals(List.of("a", "b"), ids(s.ordered()));
    }

    @Test public void invalidSnapshotIsAtomic() throws Exception {
        StreamDrafts s = new StreamDrafts();
        s.start(start(text("s1", 0, 0, "kept")));
        try {
            s.replace(snapshot(text("ok", 0, 1, "fine"), text("bad", 1, 0, "").put("kind", "thinking")));
            fail();
        } catch (IllegalArgumentException expected) {}
        assertNull(s.get("ok"));
        assertEquals(List.of("s1"), ids(s.ordered()));
    }

    @Test public void finalMetadataIsOptionalButStrict() throws Exception {
        assertNull(StreamDrafts.finalPosition(new JSONObject().put("type", "output").put("text", "x")));
        StreamDrafts.Position p = StreamDrafts.finalPosition(new JSONObject().put("type", "output")
                .put("stream_id", "s1").put("turn_id", "t1").put("ordinal", 3).put("event_id", 3));
        assertEquals("s1", p.streamId);
        assertEquals("t1", p.turnId);
        assertEquals(3, p.first);
        assertEquals(3, p.last);
        try {
            StreamDrafts.finalPosition(new JSONObject().put("stream_id", "s1"));
            fail("partial metadata");
        } catch (IllegalArgumentException expected) {}
        try {
            StreamDrafts.finalPosition(new JSONObject().put("stream_id", 5).put("turn_id", "t").put("ordinal", 0));
            fail("non-string id");
        } catch (IllegalArgumentException expected) {}
    }

    /**
     * The transcript as SessionActivity places generated rows: a final takes
     * its draft's slot when the draft is shown, else its source position; a
     * row that never streamed has no position and is appended.
     */
    private static final class Transcript {
        final List<String> labels = new ArrayList<>();
        final List<StreamDrafts.Position> positions = new ArrayList<>();

        void plain(String label) {
            labels.add(label);
            positions.add(null);
        }

        void draft(String turn, int ordinal) {
            StreamDrafts.Position p = new StreamDrafts.Position("s" + turn + ordinal, turn, ordinal);
            int index = StreamDrafts.insertionIndex(positions, p);
            labels.add(index, "draft " + turn + ordinal);
            positions.add(index, p);
        }

        void complete(String turn, int ordinal) {
            StreamDrafts.Position p = new StreamDrafts.Position("s" + turn + ordinal, turn, ordinal);
            int index = labels.indexOf("draft " + turn + ordinal);
            if (index >= 0) {
                labels.remove(index);
                positions.remove(index);
            } else {
                index = StreamDrafts.insertionIndex(positions, p);
            }
            labels.add(index, "final " + turn + ordinal);
            positions.add(index, p);
        }
    }

    @Test public void completedBlockStaysBehindAnEarlierActiveDraft() {
        Transcript t = new Transcript();
        t.plain("input");
        t.draft("t1", 0);
        // Ordinal 1 completes while ordinal 0 is still generating, with no
        // draft of its own shown (e.g. it started and finished between frames
        // this client resubscribed across).
        t.complete("t1", 1);
        assertEquals(List.of("input", "draft t10", "final t11"), t.labels);
        t.complete("t1", 0);
        assertEquals(List.of("input", "final t10", "final t11"), t.labels);
    }

    @Test public void concurrentDraftsFinalizeInPlaceInEitherOrder() {
        Transcript t = new Transcript();
        t.draft("t1", 0);
        t.draft("t1", 2);
        t.draft("t1", 1); // a start arriving late still takes its source slot
        assertEquals(List.of("draft t10", "draft t11", "draft t12"), t.labels);
        t.complete("t1", 2);
        t.complete("t1", 0);
        t.complete("t1", 1);
        assertEquals(List.of("final t10", "final t11", "final t12"), t.labels);
    }

    @Test public void historyReplayRestoresSourceOrderWithinATurnOnly() {
        Transcript t = new Transcript();
        t.complete("t0", 4);   // an earlier turn
        t.plain("input");
        t.complete("t1", 1);   // replay is completion (event id) order
        t.plain("approval");   // never streamed: stays where it arrived
        t.complete("t1", 0);
        t.complete("t1", 2);
        assertEquals(List.of("final t04", "input", "final t10", "final t11", "approval", "final t12"),
                t.labels);
    }

    @Test public void mergedTextRowsCoverTheirOrdinalRange() {
        StreamDrafts.Position merged = new StreamDrafts.Position("a", "t1", 0);
        StreamDrafts.Position next = new StreamDrafts.Position("b", "t1", 1);
        assertTrue(StreamDrafts.adjacent(merged, next));
        merged.last = 1;
        StreamDrafts.Position later = new StreamDrafts.Position("c", "t1", 2);
        assertTrue(StreamDrafts.adjacent(merged, later));
        assertFalse(StreamDrafts.adjacent(merged, new StreamDrafts.Position("d", "t1", 3)));
        assertFalse(StreamDrafts.adjacent(merged, new StreamDrafts.Position("e", "t2", 2)));
        assertFalse(StreamDrafts.adjacent(null, later));
        List<StreamDrafts.Position> rows = new ArrayList<>();
        rows.add(merged);
        assertEquals(1, StreamDrafts.insertionIndex(rows, later));
        rows.set(0, later);
        assertEquals(0, StreamDrafts.insertionIndex(rows, next));
    }

    @Test public void interruptionsAndRemovals() throws Exception {
        JSONObject event = new JSONObject().put("type", "stream_interrupted")
                .put("draft", tool("s2", 1, 5, "{\"message\":\"unfin", new JSONObject().put("message", "unfin")))
                .put("reason", "cancelled").put("event_id", 125);
        StreamDrafts.Draft d = StreamDrafts.interrupted(event);
        assertTrue(d.tool);
        assertEquals("{\"message\":\"unfin", d.content.toString());
        try {
            StreamDrafts.interrupted(new JSONObject(event.toString()).put("reason", "crashed"));
            fail();
        } catch (IllegalArgumentException expected) {}
        assertEquals("s3", StreamDrafts.removal(new JSONObject().put("type", "stream_remove").put("stream_id", "s3")));
        try {
            StreamDrafts.removal(new JSONObject().put("type", "stream_remove"));
            fail();
        } catch (IllegalArgumentException expected) {}
    }
}
