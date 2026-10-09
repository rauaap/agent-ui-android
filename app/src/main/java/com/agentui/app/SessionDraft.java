package com.agentui.app;

/** Persistent composer text, scoped to the server and session captured at opening. */
final class SessionDraft {
    interface Store {
        String get(String key);
        void put(String key, String text);
        void remove(String key);
    }

    private final Store store;
    private final String key;

    SessionDraft(String server, String sessionId, Store store) {
        this.store = store;
        // Length-prefix the server so neither URL nor opaque session-id punctuation
        // can cause two scopes to share a key. Missing ids must never share a draft.
        key = sessionId == null || sessionId.isEmpty() ? null
                : "composer_draft:" + server.length() + ":" + server + ":" + sessionId;
    }

    String restore() {
        if (key == null) return "";
        String text = store.get(key);
        return text == null ? "" : text;
    }

    void save(String text) {
        if (key == null) return;
        // Keep whitespace, newlines, and ! / \! prefixes exactly as typed.
        if (text.isEmpty()) store.remove(key);
        else store.put(key, text);
    }
}
