package com.agentui.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistent app settings. The server address is stored in SharedPreferences so
 * it survives app restarts and device reboots — set it once in Settings.
 */
final class Prefs {
    private static final String FILE = "agent_ui_prefs";
    private static final String KEY_HOST = "server_host";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_TLS  = "server_tls";
    private static final String KEY_NOTIFY = "notify_sessions";
    private static final String KEY_DEFAULT_DIR = "default_working_dir";

    private static final String DEFAULT_DIR = "/projects/";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String host() { return sp.getString(KEY_HOST, ""); }
    int port() { return sp.getInt(KEY_PORT, 8080); }
    boolean tls() { return sp.getBoolean(KEY_TLS, false); }

    /**
     * Pre-filled when starting a new project. Also the fallback path for new
     * sessions when the session list is unscoped — i.e. talking to a server
     * old enough to have no {@code /projects} endpoint.
     */
    String defaultDir() { return sp.getString(KEY_DEFAULT_DIR, DEFAULT_DIR); }

    void save(String host, int port, boolean tls, String defaultDir) {
        sp.edit()
                .putString(KEY_HOST, host.trim())
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_TLS, tls)
                .putString(KEY_DEFAULT_DIR, defaultDir.trim())
                .apply();
    }

    boolean isConfigured() {
        return !host().trim().isEmpty();
    }

    /* ----------------------------------------------------------------- */
    /* per-session notification opt-in                                   */
    /* ----------------------------------------------------------------- */

    /**
     * Whether completion notifications are enabled for this session (default
     * off).
     *
     * <p>Membership, not resolution: an id in the set that no longer exists
     * server-side simply never matches, so the opt-in fails closed. The set
     * survived a server migration that renumbered every session, which is
     * exactly the case this has to be safe for.
     */
    boolean notifyEnabled(String sessionId) {
        return sessionId != null && notifySessions().contains(sessionId);
    }

    void setNotify(String sessionId, boolean enabled) {
        if (sessionId == null) return;
        // SharedPreferences may hand back a shared instance, so copy before mutating.
        Set<String> set = new HashSet<>(notifySessions());
        if (enabled) set.add(sessionId);
        else set.remove(sessionId);
        sp.edit().putStringSet(notifyKey(), set).apply();
    }

    private Set<String> notifySessions() {
        return sp.getStringSet(notifyKey(), Collections.emptySet());
    }

    /**
     * The opt-in set is per server. Ids are small integers now, so the same id
     * names a different session on a different backend — one global set would
     * have a saved "3" silently switching notifications on for whatever session
     * 3 happens to be after the address changes. Within one server ids are
     * never reused, so a stale entry there is inert.
     */
    private String notifyKey() {
        return KEY_NOTIFY + ":" + authority();
    }

    /** e.g. "192.168.1.50:8080" */
    String authority() {
        return host().trim() + ":" + port();
    }

    /** Base for REST calls, e.g. "http://192.168.1.50:8080" */
    String httpBase() {
        return (tls() ? "https://" : "http://") + authority();
    }

    /** Base for WebSocket calls, e.g. "ws://192.168.1.50:8080" */
    String wsBase() {
        return (tls() ? "wss://" : "ws://") + authority();
    }
}
