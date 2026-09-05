package com.agentui.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * An agent the backend can run, as listed by {@code GET /agents}.
 *
 * <p>The list is the server's own registry — the {@code id} is what
 * {@code POST /sessions} takes as {@code agent}, the {@code name} is a label to
 * show, and one entry is flagged as the default to preselect. Both fields are
 * derived server-side from the same registry that validates a session, so the
 * list can neither advertise an agent the server would reject nor omit one it
 * would accept; the client's job is to render it and send an id back, not to
 * know what agents exist.
 */
final class Agent {
    /** What {@code POST /sessions} takes as {@code agent}. Opaque. */
    final String id;
    /** Display label, e.g. "Claude Code". */
    final String name;
    /** Whether this is the one to preselect; exactly one entry carries it. */
    final boolean isDefault;

    Agent(String id, String name, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.isDefault = isDefault;
    }

    static Agent from(JSONObject o) {
        // Agent ids are minted by the server's registry, not its database, so
        // they are strings on every server and stay on plain optString.
        String id = o.optString("id", "");
        String name = o.optString("name", "");
        return new Agent(id, name.isEmpty() ? id : name, o.optBoolean("default", false));
    }

    /**
     * What to offer against a server with no {@code /agents} — one predating the
     * endpoint, which is also one that only ever had these two. Doubles as the
     * label table before the real list has landed.
     */
    static final List<Agent> FALLBACK = Arrays.asList(
            new Agent("claude-code", "Claude Code", true),
            new Agent("opencode", "OpenCode", false));

    /**
     * The label for an agent id, falling back to the id itself. An unknown id
     * is ordinary rather than an error: a session created against a server that
     * has since dropped an adapter still has to render.
     */
    static String label(List<Agent> known, String id) {
        for (Agent a : known) {
            if (a.id.equals(id)) return a.name;
        }
        return id;
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
