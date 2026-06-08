package com.agentui.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent app settings. The server address is stored in SharedPreferences so
 * it survives app restarts and device reboots — set it once in Settings.
 */
final class Prefs {
    private static final String FILE = "agent_ui_prefs";
    private static final String KEY_HOST = "server_host";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_TLS  = "server_tls";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String host() { return sp.getString(KEY_HOST, ""); }
    int port() { return sp.getInt(KEY_PORT, 8080); }
    boolean tls() { return sp.getBoolean(KEY_TLS, false); }

    void save(String host, int port, boolean tls) {
        sp.edit()
                .putString(KEY_HOST, host.trim())
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_TLS, tls)
                .apply();
    }

    boolean isConfigured() {
        return !host().trim().isEmpty();
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
