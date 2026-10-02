package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MessageQueueTest {

    private static JSONObject json(String value) throws Exception {
        return new JSONObject(value);
    }

    private static JSONObject queued(int id, String text) throws Exception {
        return json("{type:'input',message_id:" + id + ",text:'" + text
                + "',source:{type:'user'},delivery:'queued'}");
    }

    private static JSONObject queuedFromAgent(int id, String text, int sender) throws Exception {
        return json("{type:'input',message_id:" + id + ",text:'" + text
                + "',source:{type:'agent',session_id:" + sender + "},delivery:'queued'}");
    }

    private static String item(int id, String text, String delivery) {
        return "{message_id:" + id + ",text:'" + text + "',source:{type:'user'},delivery:'" + delivery + "'}";
    }

    private static JSONObject shipped(String... items) throws Exception {
        return json("{type:'inputs_shipped',messages:[" + String.join(",", items) + "]}");
    }

    private static JSONObject snapshot(String... items) throws Exception {
        return json("{type:'input_queue',messages:[" + String.join(",", items) + "]}");
    }

    private static List<String> ids(List<MessageQueue.Message> messages) {
        List<String> ids = new ArrayList<>();
        for (MessageQueue.Message m : messages) ids.add(m.id);
        return ids;
    }

    @Test public void busyAcceptanceIsPendingNotTranscript() throws Exception {
        MessageQueue q = new MessageQueue();
        assertEquals(MessageQueue.Accepted.QUEUED, q.accept(queued(5, "Check Android too.")));
        assertEquals(List.of("5"), ids(q.pending()));
        assertEquals("Check Android too.", q.pending().get(0).text);
    }

    @Test public void repeatedAcceptanceIsADuplicateSoHistoryRecordsOnce() throws Exception {
        MessageQueue q = new MessageQueue();
        assertEquals(MessageQueue.Accepted.QUEUED, q.accept(queued(5, "a")));
        assertEquals(MessageQueue.Accepted.DUPLICATE, q.accept(queued(5, "a")));
        q.ship(shipped(item(5, "a", "shipped")));
        assertEquals(MessageQueue.Accepted.DUPLICATE, q.accept(queued(5, "a")));
    }

    @Test public void shipmentAppendsEachMessageInArrayOrderWithAttribution() throws Exception {
        MessageQueue q = new MessageQueue();
        q.accept(queued(5, "first"));
        q.accept(queuedFromAgent(6, "second", 7));
        q.accept(queued(8, "later"));
        List<MessageQueue.Message> rows = q.ship(shipped(
                item(5, "first", "shipped"),
                "{message_id:6,text:'second',source:{type:'agent',session_id:7},delivery:'shipped'}"));
        assertEquals(List.of("5", "6"), ids(rows));
        assertTrue(rows.get(0).source.isUser());
        assertEquals(InterAgent.Source.Kind.AGENT, rows.get(1).source.kind);
        assertEquals("7", rows.get(1).source.sessionId);
        // Accepted after the batch was claimed: still waiting.
        assertEquals(List.of("8"), ids(q.pending()));
    }

    @Test public void acceptanceOutsideTheReplayWindowStillShips() throws Exception {
        MessageQueue q = new MessageQueue();
        List<MessageQueue.Message> rows = q.ship(shipped(item(3, "old", "shipped")));
        assertEquals(List.of("3"), ids(rows));
        assertEquals("old", rows.get(0).text);
        assertTrue(q.pending().isEmpty());
    }

    @Test public void aShipmentSeenTwiceAddsNoRows() throws Exception {
        MessageQueue q = new MessageQueue();
        q.ship(shipped(item(3, "a", "shipped")));
        assertTrue(q.ship(shipped(item(3, "a", "shipped"))).isEmpty());
    }

    @Test public void snapshotReplacesRatherThanMerges() throws Exception {
        MessageQueue q = new MessageQueue();
        q.accept(queued(5, "stale"));
        q.accept(queued(6, "kept"));
        // 2 was accepted before the replay window began.
        q.replace(snapshot(item(2, "older", "queued"), item(6, "kept", "queued")));
        assertEquals(List.of("2", "6"), ids(q.pending()));
        q.replace(snapshot());
        assertTrue(q.pending().isEmpty());
    }

    @Test public void reconnectReplayRebuildsWithoutDuplicates() throws Exception {
        MessageQueue q = new MessageQueue();
        List<MessageQueue.Message> transcript = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            // Every connection starts over and replays the same stream.
            q.clear();
            transcript.clear();
            assertEquals(MessageQueue.Accepted.QUEUED, q.accept(queued(5, "a")));
            assertEquals(MessageQueue.Accepted.QUEUED, q.accept(queued(6, "b")));
            transcript.addAll(q.ship(shipped(item(5, "a", "shipped"))));
            q.replace(snapshot(item(6, "b", "queued")));
        }
        assertEquals(List.of("5"), ids(transcript));
        assertEquals(List.of("6"), ids(q.pending()));
    }

    @Test public void onlyShipmentsAndSnapshotsClearPending() throws Exception {
        // Stop, failure and restart change status, not the queue: pending
        // inputs stay visible until the server ships or replaces them.
        MessageQueue q = new MessageQueue();
        q.accept(queued(5, "a"));
        q.ship(shipped());
        assertEquals(List.of("5"), ids(q.pending()));
    }

    @Test public void inputsWithoutDeliveryPredateTheQueue() throws Exception {
        MessageQueue q = new MessageQueue();
        assertEquals(MessageQueue.Accepted.LEGACY, q.accept(json("{type:'input',text:'hi'}")));
        assertEquals(MessageQueue.Accepted.LEGACY,
                q.accept(json("{type:'input',message_id:4,text:'hi'}")));
        assertEquals(MessageQueue.Accepted.DUPLICATE,
                q.accept(json("{type:'input',message_id:4,text:'hi'}")));
        assertTrue(q.pending().isEmpty());
    }

    @Test public void malformedEventsAreRefusedWithoutPartialChanges() throws Exception {
        MessageQueue q = new MessageQueue();
        q.accept(queued(5, "a"));
        String[] inputs = {
                "{type:'input',text:'x',delivery:'queued'}",
                "{type:'input',message_id:'9',text:'x',delivery:'queued'}",
                "{type:'input',message_id:9,delivery:'queued'}",
                "{type:'input',message_id:9,text:'x',delivery:'shipped'}",
                "{type:'input',message_id:9,text:'x',delivery:'later'}",
        };
        for (String input : inputs) {
            try {
                q.accept(json(input));
                fail(input);
            } catch (IllegalArgumentException expected) {}
        }
        JSONObject[] batches = {
                json("{type:'inputs_shipped'}"),
                shipped(item(5, "a", "shipped"), item(9, "x", "queued")),
                json("{type:'input_queue',messages:[1]}"),
        };
        for (JSONObject batch : batches) {
            try {
                if ("input_queue".equals(batch.optString("type"))) q.replace(batch);
                else q.ship(batch);
                fail(batch.toString());
            } catch (IllegalArgumentException expected) {}
        }
        assertEquals(List.of("5"), ids(q.pending()));
    }
}
