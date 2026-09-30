package com.agentui.app;

import org.json.JSONException;
import org.json.JSONObject;

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
 */
final class Model {
    /** What {@code POST /sessions} takes as {@code model}. Opaque. */
    final String id;
    /** Display label, e.g. "Opus 5.5". */
    final String name;

    Model(String id, String name) {
        this.id = id;
        this.name = name;
    }

    static Model from(JSONObject o) throws JSONException {
        return new Model(o.getString("id"), o.getString("name"));
    }
}
