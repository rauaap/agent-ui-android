package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;

/** A directory registration, never a filesystem operation or sandbox permission. */
final class SharedAssetRoot {
    final String assetRoot;
    final String path;
    /** Empty means global; nonempty ids follow the app's opaque-id convention. */
    final String projectId;
    final String url;

    SharedAssetRoot(String assetRoot, String path, String projectId, String url) {
        this.assetRoot = assetRoot;
        this.path = path;
        this.projectId = projectId;
        this.url = url;
    }

    static SharedAssetRoot from(JSONObject value) throws JSONException {
        return new SharedAssetRoot(value.getString("asset_root"), value.getString("path"),
                Json.id(value, "project_id"), value.getString("url"));
    }

    static List<SharedAssetRoot> list(JSONArray values) throws JSONException {
        List<SharedAssetRoot> roots = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) roots.add(from(values.getJSONObject(i)));
        return roots;
    }

    static List<SharedAssetRoot> forProject(List<SharedAssetRoot> roots, String projectId) {
        List<SharedAssetRoot> scoped = new ArrayList<>();
        String id = projectId == null ? "" : projectId;
        for (SharedAssetRoot root : roots) if (root.projectId.equals(id)) scoped.add(root);
        return scoped;
    }

    static boolean validIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]+");
    }

    static boolean validPath(String value) {
        return value != null && value.startsWith("/") && value.indexOf('\0') < 0;
    }

    /** PATCH intentionally omits association; editing one scope cannot move a root. */
    static JSONObject editPayload(String name, String path) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("asset_root", name);
            payload.put("path", path);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return payload;
    }

    static JSONObject createPayload(String name, String path, String projectId) {
        JSONObject payload = editPayload(name, path);
        try {
            payload.put("project_id", projectId == null || projectId.isEmpty()
                    ? JSONObject.NULL : Json.wire(projectId));
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return payload;
    }

    static HttpUrl endpoint(String server, String identifier) {
        HttpUrl.Builder url = HttpUrl.get(server).newBuilder().addPathSegment("shared-asset-roots");
        if (identifier != null) url.addPathSegment(identifier);
        return url.build();
    }
}
