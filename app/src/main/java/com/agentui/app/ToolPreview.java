package com.agentui.app;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Display text for a tool call whose arguments are still being generated, or
 * were cut off. The preview is the server's provisional parse: fields may be
 * missing, partial or change type between updates, so nothing here validates
 * or acts on it the way {@link CanonicalAction} does for a finished call.
 */
final class ToolPreview {
    private ToolPreview() {}

    /** Fields that identify a call at a glance, tried in order. */
    private static final String[] SUMMARY = {
            "command", "file_path", "path", "pattern", "query", "url", "description"};
    /** Fields whose growing value is the interesting part of the call. */
    private static final String[] BODY = {"content", "new_string", "new_text"};

    /** {@code MESSAGE SESSION} for a session tool, otherwise the native name. */
    static String title(String name) {
        String tool = InterAgent.sessionTool(name);
        return tool != null ? InterAgent.title(tool) : name.toUpperCase(Locale.ROOT);
    }

    static String summary(JSONObject preview) {
        String value = first(preview, SUMMARY);
        return value == null ? "" : value;
    }

    /**
     * Whether a session tool's target is still unknown. Fields arrive in any
     * order, so a missing {@code session_id} means composing, not invalid.
     */
    static boolean awaitingTarget(String tool, JSONObject preview) {
        if (InterAgent.START_SESSION.equals(tool)) return false;
        Object id = preview == null ? null : preview.opt("session_id");
        return !(id instanceof Number || (id instanceof String && !((String) id).isEmpty()));
    }

    /**
     * The expandable body: content being written, else the whole provisional
     * argument object, else the raw JSON received so far.
     */
    static String body(JSONObject preview, String raw) {
        String value = first(preview, BODY);
        if (value != null) return value;
        if (preview != null && preview.length() > 0) {
            try {
                return preview.toString(2);
            } catch (Exception e) {
                return preview.toString();
            }
        }
        return raw;
    }

    private static String first(JSONObject preview, String[] keys) {
        if (preview == null) return null;
        for (String key : keys) {
            Object value = preview.opt(key);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        }
        return null;
    }
}
