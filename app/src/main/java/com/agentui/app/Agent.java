package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An agent the backend can run, with its model catalog, as listed by
 * {@code GET /agents}.
 *
 * <p>The list is the server's own registry — the {@code id} is what
 * {@code POST /sessions} takes as {@code agent}, the {@code name} is a label to
 * show, and one entry is flagged as the default to preselect. Both fields are
 * derived server-side from the same registry that validates a session, so the
 * list can neither advertise an agent the server would reject nor omit one it
 * would accept; the client's job is to render it and send an id back, not to
 * know what agents exist.
 *
 * <p>Each agent carries the models its harness offered when the server started.
 * A harness whose discovery failed has no models and a {@code modelsError}
 * saying why; the other agents are unaffected.
 */
final class Agent {
    /** What {@code POST /sessions} takes as {@code agent}. Opaque. */
    final String id;
    /** Display label, e.g. "Claude Code". */
    final String name;
    /** Whether this is the one to preselect; exactly one entry carries it. */
    final boolean isDefault;
    /** The harness's catalog, in the server's order. */
    final List<Model> models;
    /** Why model discovery failed, or null when it succeeded. */
    final String modelsError;

    Agent(String id, String name, boolean isDefault, List<Model> models, String modelsError) {
        this.id = id;
        this.name = name;
        this.isDefault = isDefault;
        this.models = models;
        this.modelsError = modelsError;
    }

    /** The catalog entry for a model id, or null when this agent does not list it. */
    Model model(String modelId) {
        for (Model m : models) if (m.id.equals(modelId)) return m;
        return null;
    }

    /** Parses the whole {@code GET /agents} body, keeping the server's order. */
    static List<Agent> list(JSONArray arr) throws JSONException {
        List<Agent> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(from(arr.getJSONObject(i)));
        return out;
    }

    static Agent from(JSONObject o) throws JSONException {
        List<Model> models = new ArrayList<>();
        JSONArray arr = o.getJSONArray("models");
        for (int i = 0; i < arr.length(); i++) models.add(Model.from(arr.getJSONObject(i)));
        // models_error: null would read back from getString as the literal "null".
        String error = o.isNull("models_error") ? null : o.getString("models_error");
        return new Agent(o.getString("id"), o.getString("name"), o.getBoolean("default"),
                Collections.unmodifiableList(models), error);
    }

    /** The entry for an id, or null when the server does not list it. */
    static Agent find(List<Agent> known, String id) {
        for (Agent a : known) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    /**
     * The label for an agent id, falling back to the id itself. An unknown id
     * is ordinary rather than an error: a session created against a server that
     * has since dropped an adapter still has to render.
     */
    static String label(List<Agent> known, String id) {
        Agent a = find(known, id);
        return a != null ? a.name : id;
    }

    /**
     * The label for a session's model: its catalog name when the session's
     * agent still lists it, else the id itself. Null for a session created
     * before models were selectable, which has none; one created before a
     * catalog change keeps its id.
     */
    static String modelLabel(List<Agent> known, String agentId, String modelId) {
        if (modelId == null) return null;
        Agent a = find(known, agentId);
        Model m = a == null ? null : a.model(modelId);
        return m != null ? m.name : modelId;
    }

    /** Why no session can be created with this agent, or null when it has a model to choose. */
    String unavailable() {
        if (modelsError != null) return "Model list unavailable: " + modelsError;
        if (models.isEmpty()) return "The server found no models for this agent.";
        return null;
    }

    /**
     * Index of the entry to preselect — the user's preference when it is still
     * available, otherwise the default advertised by the server.
     */
    static int defaultIndex(List<Agent> known, String preferredId) {
        if (preferredId != null && !preferredId.isEmpty()) {
            for (int i = 0; i < known.size(); i++) {
                if (known.get(i).id.equals(preferredId)) return i;
            }
        }
        return defaultIndex(known);
    }

    /** Server default, or the first registered agent when none is flagged. */
    static int defaultIndex(List<Agent> known) {
        for (int i = 0; i < known.size(); i++) {
            if (known.get(i).isDefault) return i;
        }
        return 0;
    }
}
