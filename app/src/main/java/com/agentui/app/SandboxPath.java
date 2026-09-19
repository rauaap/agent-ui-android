package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Original server path strings: never expand, trim, or canonicalize on Android. */
final class SandboxPath {
    final String path;
    final boolean write;

    SandboxPath(String path, boolean write) {
        this.path = path;
        this.write = write;
    }

    static List<SandboxPath> from(JSONArray array) throws JSONException {
        List<SandboxPath> paths = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            paths.add(new SandboxPath(entry.getString("path"), entry.optBoolean("write", false)));
        }
        return paths;
    }

    static JSONArray toJson(List<SandboxPath> paths) {
        JSONArray array = new JSONArray();
        for (SandboxPath path : paths) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("path", path.path);
                entry.put("write", path.write);
            } catch (JSONException e) { throw new IllegalArgumentException(e); }
            array.put(entry);
        }
        return array;
    }

    /** Exact spelling only; equivalent expanded destinations are known only to the server. */
    static int indexOf(List<SandboxPath> paths, String path) {
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).path.equals(path)) return i;
        }
        return -1;
    }

    static JSONObject replacement(String projectPath, List<SandboxPath> paths) {
        JSONObject payload = new JSONObject();
        try {
            if (projectPath != null) payload.put("path", projectPath);
            payload.put("sandbox_paths", toJson(paths));
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return payload;
    }
}
