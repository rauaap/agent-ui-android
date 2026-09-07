package com.agentui.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Shell-style composer history, independent of Android views. Entries use
 * composer syntax: commands begin with {@code !}, while prompts whose text
 * begins with {@code !} are escaped so recalling them cannot change their
 * meaning when they are sent again.
 */
final class MessageHistory {

    private final List<String> entries = new ArrayList<>();
    private Integer index;
    private String draft = "";

    /** Convert a replayed prompt to text that is safe to recall in the composer. */
    static String promptEntry(String text) {
        String value = text == null ? "" : text;
        return value.startsWith("!") ? "\\" + value : value;
    }

    /** Convert a replayed command to composer syntax. */
    static String commandEntry(String command) {
        return "!" + (command == null ? "" : command);
    }

    void clear() {
        entries.clear();
        resetNavigation();
    }

    void add(String entry) {
        entries.add(entry == null ? "" : entry);
        resetNavigation();
    }

    /** Typing after a recall starts a fresh traversal. */
    void resetNavigation() {
        index = null;
        draft = "";
    }

    /** Walk toward older entries, preserving the initial unsent draft. */
    String previous(String current) {
        if (entries.isEmpty()) return null;
        if (index == null) {
            draft = current == null ? "" : current;
            index = entries.size();
        }
        if (index > 0) index--;
        return entries.get(index);
    }

    /** Walk toward newer entries, restoring the draft past the newest one. */
    String next() {
        if (index == null) return null;
        if (index < entries.size() - 1) {
            index++;
            return entries.get(index);
        }
        String savedDraft = draft;
        resetNavigation();
        return savedDraft;
    }
}
