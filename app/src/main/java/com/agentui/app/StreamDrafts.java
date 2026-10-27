package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model output still being generated: one draft per assistant content
 * block, keyed by the server's opaque {@code stream_id}. Drafts are transient
 * socket state, kept apart from durable scrollback. Deltas are applied in
 * order and only at the next revision; anything else means this client no
 * longer matches the server and must resubscribe for its snapshot.
 */
final class StreamDrafts {
    enum Outcome { APPLIED, STALE, DESYNC }

    static final class Draft {
        final String streamId;
        final String turnId;
        final int ordinal;
        final boolean tool;
        final String callId; // tool drafts only
        final String name;   // tool drafts only
        long revision;
        /** Text for a text draft, raw argument JSON for a tool draft. */
        final StringBuilder content = new StringBuilder();
        /** Provisional parsed arguments, replaced wholesale on every update. */
        JSONObject preview;

        private Draft(String streamId, String turnId, int ordinal, long revision, boolean tool,
                      String callId, String name, String content, JSONObject preview) {
            this.streamId = streamId;
            this.turnId = turnId;
            this.ordinal = ordinal;
            this.revision = revision;
            this.tool = tool;
            this.callId = callId;
            this.name = name;
            this.content.append(content);
            this.preview = preview;
        }
    }

    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    /** Turns in first-seen order: ordinals only order drafts within one turn. */
    private final List<String> turns = new ArrayList<>();

    /** A draft object from a start, snapshot or interruption frame. */
    static Draft parse(JSONObject value) {
        if (value == null) throw new IllegalArgumentException("draft is not an object");
        String kind = string(value, "kind");
        boolean tool;
        if ("text".equals(kind)) {
            fields(value, "stream_id", "turn_id", "ordinal", "revision", "kind", "text");
            tool = false;
        } else if ("tool".equals(kind)) {
            fields(value, "stream_id", "turn_id", "ordinal", "revision", "kind",
                    "call_id", "name", "arguments_json", "arguments_preview");
            tool = true;
        } else {
            throw new IllegalArgumentException("unknown draft kind");
        }
        long ordinal = integer(value, "ordinal");
        if (ordinal > Integer.MAX_VALUE) throw new IllegalArgumentException("ordinal out of range");
        return new Draft(id(value, "stream_id"), id(value, "turn_id"), (int) ordinal,
                integer(value, "revision"), tool,
                tool ? id(value, "call_id") : null, tool ? id(value, "name") : null,
                tool ? string(value, "arguments_json") : string(value, "text"),
                tool ? object(value, "arguments_preview") : null);
    }

    /** {@code stream_start}. A repeat of a known draft is stale unless it is newer. */
    Outcome start(JSONObject frame) {
        fields(frame, "type", "draft");
        Draft draft = parse(frame.optJSONObject("draft"));
        Draft known = drafts.get(draft.streamId);
        if (known != null) return draft.revision <= known.revision ? Outcome.STALE : Outcome.DESYNC;
        put(draft);
        return Outcome.APPLIED;
    }

    /** {@code stream_delta}: append the native fragment, replace the preview. */
    Outcome delta(JSONObject frame) {
        String streamId = id(frame, "stream_id");
        long revision = integer(frame, "revision");
        String delta = string(frame, "delta");
        Draft draft = drafts.get(streamId);
        if (draft == null) return Outcome.DESYNC;
        if (draft.tool) fields(frame, "type", "stream_id", "revision", "delta", "arguments_preview");
        else fields(frame, "type", "stream_id", "revision", "delta");
        JSONObject preview = draft.tool ? object(frame, "arguments_preview") : null;
        if (revision <= draft.revision) return Outcome.STALE;
        if (revision != draft.revision + 1) return Outcome.DESYNC;
        draft.revision = revision;
        draft.content.append(delta);
        if (draft.tool) draft.preview = preview;
        return Outcome.APPLIED;
    }

    /**
     * {@code stream_snapshot}: the server's complete set of current drafts.
     * Validated in full before anything is replaced.
     */
    List<Draft> replace(JSONObject frame) {
        fields(frame, "type", "drafts");
        JSONArray list = frame.optJSONArray("drafts");
        if (list == null) throw new IllegalArgumentException("drafts is not an array");
        List<Draft> next = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            Draft draft = parse(list.optJSONObject(i));
            if (!ids.add(draft.streamId)) throw new IllegalArgumentException("duplicate stream_id");
            next.add(draft);
        }
        clear();
        for (Draft draft : next) put(draft);
        return ordered();
    }

    /** Forget a draft on its final, interrupted or removal frame; null if unknown. */
    Draft retire(String streamId) {
        return streamId == null ? null : drafts.remove(streamId);
    }

    Draft get(String streamId) {
        return drafts.get(streamId);
    }

    boolean isEmpty() {
        return drafts.isEmpty();
    }

    void clear() {
        drafts.clear();
        turns.clear();
    }

    /** Source order: turn by turn, then ordinal. */
    List<Draft> ordered() {
        List<Draft> out = new ArrayList<>(drafts.values());
        out.sort((a, b) -> {
            int turn = Integer.compare(turns.indexOf(a.turnId), turns.indexOf(b.turnId));
            return turn != 0 ? turn : Integer.compare(a.ordinal, b.ordinal);
        });
        return out;
    }

    private void put(Draft draft) {
        if (!turns.contains(draft.turnId)) turns.add(draft.turnId);
        drafts.put(draft.streamId, draft);
    }

    /**
     * Where a generated block belongs in its turn. Rows of one turn keep
     * source order however their blocks complete; a merged text row covers
     * the ordinals {@code first..last}.
     */
    static final class Position {
        final String streamId;
        final String turnId;
        int first;
        int last;

        Position(String streamId, String turnId, int ordinal) {
            this.streamId = streamId;
            this.turnId = turnId;
            this.first = ordinal;
            this.last = ordinal;
        }
    }

    static Position position(Draft draft) {
        return new Position(draft.streamId, draft.turnId, draft.ordinal);
    }

    /**
     * Where a durable final event belongs: null for an event that never
     * streamed (all history before this protocol), or an exception when the
     * streaming metadata it carries is malformed.
     */
    static Position finalPosition(JSONObject event) {
        boolean any = event.has("stream_id") || event.has("turn_id") || event.has("ordinal");
        if (!any) return null;
        String streamId = id(event, "stream_id");
        String turnId = id(event, "turn_id");
        long ordinal = integer(event, "ordinal");
        if (ordinal > Integer.MAX_VALUE) throw new IllegalArgumentException("ordinal out of range");
        return new Position(streamId, turnId, (int) ordinal);
    }

    /**
     * The row index for a block at {@code position}, given the positions of
     * the current rows (null for rows that never streamed): ahead of the first
     * row of the same turn that comes later in source order, else at the end.
     */
    static int insertionIndex(List<Position> rows, Position position) {
        for (int i = 0; i < rows.size(); i++) {
            Position row = rows.get(i);
            if (row != null && row.turnId.equals(position.turnId) && row.first > position.last) return i;
        }
        return rows.size();
    }

    /** Whether {@code next} directly follows {@code before} in the same turn. */
    static boolean adjacent(Position before, Position next) {
        return before != null && next != null && before.turnId.equals(next.turnId)
                && before.last + 1 == next.first;
    }

    /** {@code stream_remove}: the empty draft to drop without a durable row. */
    static String removal(JSONObject frame) {
        fields(frame, "type", "stream_id");
        return id(frame, "stream_id");
    }

    /** A durable {@code stream_interrupted} event's preserved draft. */
    static Draft interrupted(JSONObject event) {
        fields(event, "type", "draft", "reason", "event_id");
        if (!validReason(event.opt("reason"))) throw new IllegalArgumentException("unknown reason");
        return parse(event.optJSONObject("draft"));
    }

    /** The interruption reasons the server documents. */
    static boolean validReason(Object reason) {
        return "cancelled".equals(reason) || "error".equals(reason)
                || "retry".equals(reason) || "incomplete".equals(reason);
    }

    private static void fields(JSONObject value, String... allowed) {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!names.contains(key)) throw new IllegalArgumentException("unexpected field " + key);
        }
        for (String name : allowed) {
            if (!"type".equals(name) && !value.has(name)) {
                throw new IllegalArgumentException("missing " + name);
            }
        }
    }

    private static String string(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException(key + " is not a string");
        return (String) raw;
    }

    private static String id(JSONObject value, String key) {
        String text = string(value, key);
        if (text.isEmpty()) throw new IllegalArgumentException(key + " is empty");
        return text;
    }

    private static long integer(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0) {
            throw new IllegalArgumentException(key + " is not a non-negative integer");
        }
        return ((Number) raw).longValue();
    }

    private static JSONObject object(JSONObject value, String key) {
        Object raw = value.opt(key);
        if (!(raw instanceof JSONObject)) throw new IllegalArgumentException(key + " is not an object");
        return (JSONObject) raw;
    }
}
