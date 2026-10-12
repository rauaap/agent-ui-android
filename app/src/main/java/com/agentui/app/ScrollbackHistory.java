package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Connection-local persisted-event cursor and chronological rendering log. */
final class ScrollbackHistory {
    final List<JSONObject> events = new ArrayList<>();
    private final Set<Long> ids = new HashSet<>();
    long oldest;
    int generation;
    boolean loading;
    boolean hasMore = true;
    boolean replayComplete;

    void reset() {
        generation++;
        events.clear();
        ids.clear();
        oldest = 0;
        loading = false;
        hasMore = true;
        replayComplete = false;
    }

    boolean accept(JSONObject event) {
        long id = event.optLong("event_id", 0);
        if (id > 0) {
            if (!ids.add(id)) return false;
            oldest = oldest == 0 ? id : Math.min(oldest, id);
        }
        events.add(event);
        return true;
    }

    boolean canLoad() {
        return replayComplete && !loading && hasMore && oldest > 0;
    }

    /** Validate the entire page before changing state. REST IDs override payload IDs. */
    boolean prepend(JSONObject page) throws JSONException {
        JSONArray rows = page.getJSONArray("messages");
        List<JSONObject> older = new ArrayList<>();
        Set<Long> pageIds = new HashSet<>();
        long cursor = page.getLong("next_cursor");
        boolean more = page.getBoolean("has_more");
        long previous = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            long id = row.getLong("id");
            if (id <= previous || id >= oldest) throw new JSONException("Invalid history ordering");
            previous = id;
            JSONObject event = new JSONObject(row.getJSONObject("payload").toString());
            String type = row.getString("type");
            event.put("type", type);
            event.put("event_id", id);
            if ("input".equals(type)) event.put("message_id", id);
            if (!ids.contains(id) && pageIds.add(id)) older.add(event);
        }
        long expected = rows.length() == 0 ? oldest : rows.getJSONObject(0).getLong("id");
        if (cursor != expected) throw new JSONException("Invalid history cursor");
        events.addAll(0, older);
        ids.addAll(pageIds);
        oldest = cursor;
        hasMore = more && rows.length() > 0;
        return !older.isEmpty();
    }
}
