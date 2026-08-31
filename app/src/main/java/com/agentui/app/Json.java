package com.agentui.app;

import org.json.JSONObject;

/**
 * Reading server ids out of JSON.
 *
 * <p>The server mints {@code projects.id} and {@code sessions.id} as JSON
 * <em>numbers</em> (they were uuid strings before a migration that renumbered
 * every row). This app holds every id as an opaque {@link String} — an id is
 * identity, not arithmetic, so nothing here ever parses one back into a number,
 * compares two with {@code <}, or slices one for display.
 *
 * <p>That conversion used to happen by accident: {@code optString} coerces a
 * JSON number to its string form, so {@code 1} arrived as {@code "1"} with
 * nothing in the code saying so. This makes it deliberate, and pins the
 * formatting — {@code String.valueOf} on a {@code Double} yields {@code "1.0"},
 * an id that matches no session on the server.
 *
 * <p>Strings pass through untouched, which is what keeps the app talking to a
 * pre-migration server whose ids are still uuids.
 *
 * <p>Only ids the <em>database</em> mints go through here. {@code request_id},
 * {@code agent_session_id} and the {@code id} on an approval option are minted
 * by the agent or the approval protocol, are strings on every server, and stay
 * on plain {@code optString}.
 */
final class Json {
    private Json() {}

    /** The id at {@code key}, as a string; empty when absent or null. */
    static String id(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return idOf(o.opt(key));
    }

    /**
     * The string form of one already-decoded id value. Split out from
     * {@link #id} so the conversion is testable off-device, where org.json is
     * not available.
     */
    static String idOf(Object value) {
        if (value == null) return "";
        if (value instanceof Number) {
            Number n = (Number) value;
            // Integer, Long and BigInteger already stringify exactly; a
            // floating-point id has to go via longValue() to avoid the ".0".
            return (n instanceof Double || n instanceof Float)
                    ? String.valueOf(n.longValue())
                    : n.toString();
        }
        return String.valueOf(value);
    }
}
