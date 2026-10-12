package com.agentui.app;

import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Filesystem status is independent of managed worktree membership. */
final class DirectorySnapshot {
    final Map<String, Boolean> paths;

    DirectorySnapshot(Map<String, Boolean> paths) { this.paths = new HashMap<>(paths); }
    static DirectorySnapshot unknown() { return new DirectorySnapshot(new HashMap<>()); }
    static DirectorySnapshot from(JSONObject json) {
        Map<String, Boolean> paths = new HashMap<>();
        for (Iterator<String> keys = json.keys(); keys.hasNext();) {
            String path = keys.next();
            Object value = json.opt(path);
            if (value instanceof Boolean) paths.put(path, (Boolean) value);
        }
        return new DirectorySnapshot(paths);
    }
    boolean missing(String path) { return Boolean.FALSE.equals(paths.get(path)); }
    boolean covers(List<Project> projects, List<Worktree> worktrees, List<Session> sessions) {
        for (Project p : projects) if (!paths.containsKey(p.path)) return false;
        for (Worktree w : worktrees) if (!paths.containsKey(w.path)) return false;
        for (Session s : sessions) if (!paths.containsKey(s.workingDir)) return false;
        return true;
    }
}
