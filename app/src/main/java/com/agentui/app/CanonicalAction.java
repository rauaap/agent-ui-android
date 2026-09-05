package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** A validated action from the provider-neutral transcript protocol. */
final class CanonicalAction {
    private final JSONObject value;
    private final String kind;

    private CanonicalAction(JSONObject value, String kind) {
        this.value = value;
        this.kind = kind;
    }

    static CanonicalAction parse(JSONObject value) {
        if (value == null) return null;
        String kind = requiredString(value, "kind", false);
        if (kind == null) return null;
        boolean valid;
        switch (kind) {
            case "command":
                valid = fields(value, "kind", "command", "description", "timeout_ms", "shell")
                        && requiredString(value, "command", false) != null
                        && optionalString(value, "description")
                        && optionalNumber(value, "timeout_ms", false, true)
                        && optionalString(value, "shell");
                break;
            case "read":
                valid = fields(value, "kind", "path", "offset", "limit")
                        && requiredString(value, "path", false) != null
                        && optionalNumber(value, "offset", true, false)
                        && optionalNumber(value, "limit", true, false);
                break;
            case "edit":
                valid = fields(value, "kind", "path", "edits")
                        && requiredString(value, "path", false) != null
                        && validEdits(value.optJSONArray("edits"));
                break;
            case "write":
                valid = fields(value, "kind", "path", "content")
                        && requiredString(value, "path", false) != null
                        && requiredString(value, "content", true) != null;
                break;
            case "search":
                valid = fields(value, "kind", "mode", "query", "path", "glob", "limit")
                        && oneOf(requiredString(value, "mode", false), "content", "files")
                        && requiredString(value, "query", false) != null
                        && optionalString(value, "path") && optionalString(value, "glob")
                        && optionalNumber(value, "limit", true, false);
                break;
            case "list":
                valid = fields(value, "kind", "path", "limit")
                        && optionalString(value, "path")
                        && optionalNumber(value, "limit", true, false);
                break;
            case "web":
                String operation = requiredString(value, "operation", false);
                valid = fields(value, "kind", "operation", "query", "url", "prompt")
                        && oneOf(operation, "search", "fetch")
                        && optionalString(value, "prompt")
                        && ("search".equals(operation)
                            ? requiredString(value, "query", false) != null && !value.has("url")
                            : requiredString(value, "url", false) != null && !value.has("query"));
                break;
            case "task":
                valid = fields(value, "kind", "description", "prompt", "agent")
                        && requiredString(value, "description", false) != null
                        && optionalString(value, "prompt") && optionalString(value, "agent");
                break;
            case "other":
                valid = fields(value, "kind", "name", "arguments")
                        && requiredString(value, "name", false) != null
                        && value.optJSONObject("arguments") != null;
                break;
            default:
                valid = false;
        }
        return valid ? new CanonicalAction(value, kind) : null;
    }

    static boolean validOptions(JSONArray options) {
        if (options == null) return false;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null || !fields(option, "id", "name", "kind")
                    || requiredString(option, "id", false) == null
                    || requiredString(option, "name", false) == null
                    || !oneOf(requiredString(option, "kind", false),
                        "allow_once", "allow_always", "reject_once", "reject_always")) return false;
        }
        return true;
    }

    JSONObject json() { return value; }
    String kind() { return kind; }

    String title() {
        if ("other".equals(kind)) return value.optString("name", "tool");
        return kind;
    }

    String summary() {
        switch (kind) {
            case "command": return value.optString("command", "");
            case "read": case "edit": case "write": return value.optString("path", "");
            case "search": return value.optString("query", "");
            case "list": return value.optString("path", "Current directory");
            case "web": return "search".equals(value.optString("operation"))
                    ? value.optString("query", "") : value.optString("url", "");
            case "task": return value.optString("description", "");
            case "other": return value.optString("name", "tool");
            default: return "";
        }
    }

    String command() {
        return "command".equals(kind) ? value.optString("command", "") : "";
    }

    JSONObject detail() {
        return "other".equals(kind) ? value.optJSONObject("arguments") : value;
    }

    private static boolean validEdits(JSONArray edits) {
        if (edits == null || edits.length() == 0) return false;
        for (int i = 0; i < edits.length(); i++) {
            JSONObject edit = edits.optJSONObject(i);
            if (edit == null || !fields(edit, "old_text", "new_text", "replace_all")
                    || requiredString(edit, "old_text", false) == null
                    || requiredString(edit, "new_text", true) == null
                    || (edit.has("replace_all") && !(edit.opt("replace_all") instanceof Boolean))) {
                return false;
            }
        }
        return true;
    }

    private static boolean fields(JSONObject value, String... allowed) {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!names.contains(keys.next())) return false;
        return true;
    }

    private static String requiredString(JSONObject value, String key, boolean emptyAllowed) {
        Object raw = value.opt(key);
        if (!(raw instanceof String)) return null;
        String text = (String) raw;
        return emptyAllowed || !text.isEmpty() ? text : null;
    }

    private static boolean optionalString(JSONObject value, String key) {
        return !value.has(key) || requiredString(value, key, false) != null;
    }

    private static boolean optionalNumber(JSONObject value, String key, boolean integer, boolean nonNegative) {
        if (!value.has(key)) return true;
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) return false;
        double number = ((Number) raw).doubleValue();
        return Double.isFinite(number) && (!integer || number == Math.rint(number))
                && (!nonNegative || number >= 0);
    }

    private static boolean oneOf(String value, String... choices) {
        if (value == null) return false;
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }
}
