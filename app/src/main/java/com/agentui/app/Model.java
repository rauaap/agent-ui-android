package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A model one harness can run, as listed by {@code GET /models}.
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

    /**
     * One harness's catalog. A harness whose discovery failed has no models and
     * an {@code error} saying why; it can still run on its own default, and the
     * other harnesses are unaffected.
     */
    static final class Catalog {
        final List<Model> models;
        /** Why discovery failed, or null when it succeeded. */
        final String error;

        Catalog(List<Model> models, String error) {
            this.models = models;
            this.error = error;
        }
    }

    /** Parses the whole {@code GET /models} body, keyed by agent id. */
    static Map<String, Catalog> catalogs(JSONObject o) {
        Map<String, Catalog> out = new HashMap<>();
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String agent = keys.next();
            JSONObject c = o.optJSONObject(agent);
            if (c != null) out.put(agent, catalog(c));
        }
        return out;
    }

    static Catalog catalog(JSONObject o) {
        List<Model> models = new ArrayList<>();
        JSONArray arr = o.optJSONArray("models");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.isEmpty()) continue;
            String name = m.optString("name", "");
            models.add(new Model(id, name.isEmpty() ? id : name));
        }
        // error: null would read back from optString as the literal "null".
        String error = o.isNull("error") ? null : o.optString("error", "");
        if (error != null && error.isEmpty()) error = "Model discovery failed";
        return new Catalog(Collections.unmodifiableList(models), error);
    }

    /**
     * Why no session can be created for {@code agentId}, or null when its
     * catalog has a model to choose. {@code fetchError} is a failed
     * {@code GET /models}, which blocks every agent.
     */
    static String unavailable(Map<String, Catalog> catalogs, String fetchError, String agentId) {
        if (fetchError != null) return "Model list unavailable: " + fetchError;
        Catalog c = forAgent(catalogs, agentId);
        if (c == null) return "The server lists no models for this agent.";
        if (c.error != null) return "Model list unavailable: " + c.error;
        if (c.models.isEmpty()) return "The server found no models for this agent.";
        return null;
    }

    /**
     * The catalog for one agent, or null when the server's catalog does not
     * mention it (or there is no catalog at all).
     */
    static Catalog forAgent(Map<String, Catalog> catalogs, String agentId) {
        return catalogs == null ? null : catalogs.get(agentId);
    }

    /**
     * The label for a session's model: its catalog name when the harness still
     * lists it, else the id itself. Null for a session created before models
     * were selectable, which has none; one created before a catalog change
     * keeps its id.
     */
    static String label(Map<String, Catalog> catalogs, String agentId, String modelId) {
        if (modelId == null) return null;
        Catalog c = forAgent(catalogs, agentId);
        if (c != null) {
            for (Model m : c.models) if (m.id.equals(modelId)) return m.name;
        }
        return modelId;
    }
}
