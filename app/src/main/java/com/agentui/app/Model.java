package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A model one harness can run, as listed under its agent by {@code GET /agents}.
 *
 * <p>The server discovers each harness's catalog once, at startup, and never
 * refreshes it. The {@code id} is what {@code POST /sessions} takes as
 * {@code model} and is opaque — a Claude model name and a Pi
 * {@code provider/model} look nothing alike, and the client must not care. A
 * session's model is fixed at creation: there is no switching one mid-session.
 *
 * <p>Every session is created with an explicit model. When an agent has no
 * usable catalog the app blocks creation and shows why. It never leaves the
 * choice to the harness, because the fix belongs on the server.
 *
 * <p>A model's reasoning levels are the harness's own vocabulary, in its own
 * order. There is no default level: a session created without one leaves the
 * choice to the harness, which the app shows as "Default".
 */
final class Model {
    /** What {@code POST /sessions} takes as {@code model}. Opaque. */
    final String id;
    /** Display label, e.g. "Opus 5.5". */
    final String name;
    /** What {@code reasoning_level} may be set to; empty when there is no choice. */
    final List<String> reasoningLevels;

    Model(String id, String name, List<String> reasoningLevels) {
        this.id = id;
        this.name = name;
        this.reasoningLevels = reasoningLevels;
    }

    static Model from(JSONObject o) throws JSONException {
        List<String> levels = new ArrayList<>();
        JSONArray arr = o.getJSONArray("reasoning_levels");
        for (int i = 0; i < arr.length(); i++) levels.add(arr.getString(i));
        return new Model(o.getString("id"), o.getString("name"),
                Collections.unmodifiableList(levels));
    }

    /** How a session's {@code reasoning_level} reads; null leaves it to the harness. */
    static String reasoningLabel(String level) {
        return level == null ? "Default" : level;
    }
}
