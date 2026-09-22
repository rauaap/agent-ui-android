package com.agentui.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The view-independent half of inter-agent messaging: who sent an input, and
 * how the three session tools are titled and summarised. Shared wording with
 * the desktop client lives in agent-ui-desktop's docs/inter-agent-ui.md.
 */
final class InterAgent {
    private InterAgent() {}

    static final String MESSAGE_SESSION = "message_session";
    static final String START_SESSION = "start_session";
    static final String READ_SESSION = "read_session";

    /**
     * Resolves a session id to its current name: null when no such session
     * exists, empty while that isn't known yet.
     */
    interface Names {
        String name(String sessionId);
    }

    /** Who an {@code input} event came from. */
    static final class Source {
        enum Kind { USER, AGENT, UNKNOWN }

        static final Source USER = new Source(Kind.USER, "");
        static final Source UNKNOWN = new Source(Kind.UNKNOWN, "");

        final Kind kind;
        /** The sending session's id, for {@link Kind#AGENT} only. */
        final String sessionId;

        private Source(Kind kind, String sessionId) {
            this.kind = kind;
            this.sessionId = sessionId;
        }

        boolean isUser() { return kind == Kind.USER; }
    }

    /**
     * Read {@code source} off an input event. A missing field is an older
     * record typed by the user; any shape not understood here is unknown, and
     * never passed off as the user's own message.
     */
    static Source source(JSONObject event) {
        if (!event.has("source")) return Source.USER;
        JSONObject source = event.optJSONObject("source");
        if (source == null) return Source.UNKNOWN;
        Object type = source.opt("type");
        if ("user".equals(type)) return Source.USER;
        if ("agent".equals(type)) {
            Object id = source.opt("session_id");
            // A positive JSON integer; a float or a numeric string is malformed.
            if ((id instanceof Integer || id instanceof Long) && ((Number) id).longValue() > 0) {
                return new Source(Source.Kind.AGENT, Json.idOf(id));
            }
        }
        return Source.UNKNOWN;
    }

    /**
     * The session tool a canonical {@code other} action invokes, or null. Claude
     * reaches it through MCP ({@code mcp__agent_ui__message_session}) and pi by
     * its bare name, so only the suffix is matched.
     */
    static String sessionTool(CanonicalAction action) {
        if (action == null || !"other".equals(action.kind())) return null;
        return sessionTool(action.json().optString("name", ""));
    }

    static String sessionTool(String name) {
        for (String tool : new String[] {MESSAGE_SESSION, START_SESSION, READ_SESSION}) {
            if (name.equals(tool) || name.endsWith("__" + tool)) return tool;
        }
        return null;
    }

    /** {@code MESSAGE SESSION} for {@code message_session}. */
    static String title(String tool) {
        return tool.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    /** The message a tool carries to its target, or empty for none. */
    static String message(String tool, JSONObject args) {
        if (args == null || READ_SESSION.equals(tool)) return "";
        Object message = args.opt("message");
        return message instanceof String ? (String) message : "";
    }

    /**
     * A card summary. {@link #target} is kept apart so it can be painted as a
     * warning when {@link #unknownTarget}; {@link #text} is the whole line.
     */
    static final class Summary {
        final String before;
        final String target;
        final boolean unknownTarget;
        final String after;

        Summary(String before, String target, boolean unknownTarget, String after) {
            this.before = before;
            this.target = target;
            this.unknownTarget = unknownTarget;
            this.after = after;
        }

        String text() { return before + target + after; }
    }

    static Summary summary(String tool, JSONObject args, Names names) {
        if (args == null) args = new JSONObject();
        switch (tool) {
            case MESSAGE_SESSION: {
                String id = Json.id(args, "session_id");
                return new Summary("→ ", target(id, names), unknown(id, names), "");
            }
            case START_SESSION: {
                List<String> parts = new ArrayList<>();
                String agent = string(args, "agent");
                if (!agent.isEmpty()) parts.add(agent);
                String worktree = Json.id(args, "worktree_id");
                if (!worktree.isEmpty()) parts.add("worktree #" + worktree);
                // Sandboxed unless explicitly turned off, matching the server.
                parts.add(Boolean.FALSE.equals(args.opt("sandbox")) ? "unsandboxed" : "sandboxed");
                String text = "\"" + string(args, "name") + "\" in " + string(args, "project_path")
                        + "   (" + String.join(" · ", parts) + ")";
                return new Summary(text, "", false, "");
            }
            case READ_SESSION: {
                String id = Json.id(args, "session_id");
                List<String> parts = new ArrayList<>();
                if (args.has("after") && !args.isNull("after")) {
                    parts.add("after " + Json.id(args, "after"));
                }
                parts.add("limit " + (args.has("limit") && !args.isNull("limit")
                        ? Json.id(args, "limit") : "200"));
                return new Summary("", target(id, names), unknown(id, names),
                        "   (" + String.join(" · ", parts) + ")");
            }
            default:
                return new Summary(tool, "", false, "");
        }
    }

    /** {@code api refactor #42}, {@code #42 (unknown session)}, or {@code #42} while loading. */
    private static String target(String id, Names names) {
        String name = unknown(id, names) ? null : names.name(id);
        if (name == null) return "#" + id + " (unknown session)";
        return name.isEmpty() ? "#" + id : name + " #" + id;
    }

    private static boolean unknown(String id, Names names) {
        return id.isEmpty() || names.name(id) == null;
    }

    private static String string(JSONObject o, String key) {
        Object value = o.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
