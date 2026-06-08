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

    /** Pre-filled into the working-directory field when creating a session. */
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

    /** Whether completion notifications are enabled for this session (default off). */
    boolean notifyEnabled(String sessionId) {
        return sessionId != null && notifySessions().contains(sessionId);
    }

    void setNotify(String sessionId, boolean enabled) {
        if (sessionId == null) return;
        // SharedPreferences may hand back a shared instance, so copy before mutating.
        Set<String> set = new HashSet<>(notifySessions());
        if (enabled) set.add(sessionId);
        else set.remove(sessionId);
        sp.edit().putStringSet(KEY_NOTIFY, set).apply();
    }

    private Set<String> notifySessions() {
        return sp.getStringSet(KEY_NOTIFY, Collections.emptySet());
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
