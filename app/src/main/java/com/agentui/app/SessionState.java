package com.agentui.app;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Main-thread session metadata, scoped by server and numeric session id. */
final class SessionState {
    private static final Map<String, SessionState> states = new HashMap<>();
    static SessionState get(Api api, String id) {
        String key = api.prefs().httpBase() + "/" + id;
        SessionState state = states.get(key);
        if (state == null) { state = new SessionState(); states.put(key, state); }
        return state;
    }
    Session session;
    boolean connected;
    boolean loaded;
    boolean saving;
    boolean approvalSaving;
    private static long clock;
    static long snapshot() { return clock; }
    long settingsRevision;
    long statusRevision;
    private JSONObject liveSettings;
    private String liveStatus;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }
    void changed() { for (Runnable r : new ArrayList<>(listeners)) r.run(); }
    void connection(boolean value) {
        connected = value;
        loaded = false;
        changed();
    }
    void settings(JSONObject msg) {
        liveSettings = msg;
        settingsRevision = ++clock;
        if (session != null) applySettings(session, msg);
        changed();
    }
    void status(String status) {
        liveStatus = status;
        statusRevision = ++clock;
        if (session != null) session.status = status;
        changed();
    }
    void accept(Session value, long started) {
        if (settingsRevision > started && liveSettings != null) applySettings(value, liveSettings);
        if (statusRevision > started && liveStatus != null) value.status = liveStatus;
        session = value;
        loaded = true;
        changed();
    }
    void confirmed(Session value, long started) {
        accept(value, started);
        JSONObject settings = new JSONObject();
        try {
            settings.put("auto_approve_write", session.autoApproveWrite);
            settings.put("auto_approve_command", session.autoApproveCommand);
            settings.put("auto_approve_inter_agent_communication", session.autoApproveInterAgent);
            settings.put("sandbox", session.sandbox);
        } catch (Exception ignored) {}
        settings(settings);
    }
    static void applySettings(Session session, JSONObject msg) {
        session.autoApproveWrite = msg.optBoolean("auto_approve_write", session.autoApproveWrite);
        session.autoApproveCommand = msg.optBoolean("auto_approve_command", session.autoApproveCommand);
        session.autoApproveInterAgent = msg.optBoolean("auto_approve_inter_agent_communication",
                session.autoApproveInterAgent);
        if (msg.opt("sandbox") instanceof Boolean) session.sandbox = (Boolean) msg.opt("sandbox");
    }
    boolean canSaveSandbox() {
        return connected && loaded && !saving && !approvalSaving && session != null
                && Agent.supportsSandbox(session.agent) && session.sandbox != null
                && "idle".equals(session.status);
    }
}
