package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The view-independent half of the server's message queue: which accepted
 * inputs are still waiting for a turn, and which have already been placed in
 * the transcript.
 *
 * <p>An ordinary input is accepted at once, even mid-turn, as an {@code input}
 * event with {@code delivery: "queued"}. It waits in a pending list, outside
 * the transcript, until an {@code inputs_shipped} event hands it to a harness
 * turn; only then does it become a transcript row, at the shipment boundary.
 * The {@code input_queue} snapshot sent after every replay is the authoritative
 * pending list. Inputs recorded before the server queued anything carry no
 * {@code delivery} and were transcript rows when they arrived.
 *
 * <p>State lives for one connection: every connection replays the transcript
 * from scratch, so {@link #clear} goes with it.
 */
final class MessageQueue {

    /** One input, by the scrollback id the server gave its acceptance. */
    static final class Message {
        final String id;
        final String text;
        final InterAgent.Source source;

        Message(String id, String text, InterAgent.Source source) {
            this.id = id;
            this.text = text;
            this.source = source;
        }
    }

    /** What to do with an {@code input} event. */
    enum Accepted {
        /** Newly pending: show it in the pending list, not the transcript. */
        QUEUED,
        /** A pre-queue record: a transcript row right where it is. */
        LEGACY,
        /** Already pending or already in the transcript: nothing at all. */
        DUPLICATE,
    }

    private final Map<String, Message> pending = new LinkedHashMap<>();
    private final Set<String> shown = new HashSet<>();

    void clear() {
        pending.clear();
        shown.clear();
    }

    /** Pending inputs, in acceptance order. */
    List<Message> pending() {
        return new ArrayList<>(pending.values());
    }

    /**
     * Take an {@code input} event. Composer history follows the result: a
     * user's {@link Accepted#QUEUED} or {@link Accepted#LEGACY} input is its
     * echo, and a duplicate must not be recorded twice.
     *
     * @throws IllegalArgumentException for a shape this client does not know
     */
    Accepted accept(JSONObject event) {
        if (!event.has("delivery")) {
            String id = optionalId(event);
            if (id != null && !shown.add(id)) return Accepted.DUPLICATE;
            return Accepted.LEGACY;
        }
        Message message = message(event, "queued");
        if (pending.containsKey(message.id) || shown.contains(message.id)) {
            return Accepted.DUPLICATE;
        }
        pending.put(message.id, message);
        return Accepted.QUEUED;
    }

    /**
     * Take an {@code inputs_shipped} event: its messages leave the pending
     * list, and those not yet in the transcript are returned to be appended
     * there, in shipment order. The event carries full contents, so a message
     * whose acceptance fell outside the replay window ships all the same.
     *
     * @throws IllegalArgumentException for a shape this client does not know;
     *         nothing changes then
     */
    List<Message> ship(JSONObject event) {
        List<Message> messages = messages(event, "shipped");
        List<Message> rows = new ArrayList<>();
        for (Message message : messages) {
            pending.remove(message.id);
            if (shown.add(message.id)) rows.add(message);
        }
        return rows;
    }

    /**
     * Replace the whole pending list with an {@code input_queue} snapshot.
     *
     * @throws IllegalArgumentException for a shape this client does not know;
     *         nothing changes then
     */
    void replace(JSONObject snapshot) {
        List<Message> messages = messages(snapshot, "queued");
        pending.clear();
        for (Message message : messages) pending.put(message.id, message);
    }

    private static List<Message> messages(JSONObject event, String delivery) {
        JSONArray array = event.optJSONArray("messages");
        if (array == null) throw new IllegalArgumentException("missing messages");
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) throw new IllegalArgumentException("message is not an object");
            messages.add(message(item, delivery));
        }
        return messages;
    }

    private static Message message(JSONObject item, String delivery) {
        if (!delivery.equals(item.opt("delivery"))) {
            throw new IllegalArgumentException("unexpected delivery " + item.opt("delivery"));
        }
        String id = optionalId(item);
        if (id == null) throw new IllegalArgumentException("missing message_id");
        Object text = item.opt("text");
        if (!(text instanceof String)) throw new IllegalArgumentException("missing text");
        return new Message(id, (String) text, InterAgent.source(item));
    }

    /** A positive JSON integer {@code message_id}, as a string; null when absent or malformed. */
    private static String optionalId(JSONObject item) {
        Object id = item.opt("message_id");
        if ((id instanceof Integer || id instanceof Long) && ((Number) id).longValue() > 0) {
            return Json.idOf(id);
        }
        return null;
    }
}
