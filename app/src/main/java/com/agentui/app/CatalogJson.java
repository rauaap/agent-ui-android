package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The same wire decoders for HTTP responses and cached raw JSON. */
final class CatalogJson {
    private CatalogJson() {}

    private static <T> List<T> list(String body, Function<JSONObject, T> decode) throws Exception {
        JSONArray array = new JSONArray(body);
        List<T> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(decode.apply(array.getJSONObject(i)));
        return values;
    }
    static List<Project> projects(String body) throws Exception { return list(body, Project::from); }
    static List<Session> sessions(String body) throws Exception { return list(body, Session::from); }
    static List<Worktree> worktrees(String body) throws Exception { return list(body, Worktree::from); }
    static List<Agent> agents(String body) throws Exception { return Agent.list(new JSONArray(body)); }
    static DirectorySnapshot existence(String body) throws Exception {
        return DirectorySnapshot.from(new JSONObject(body));
    }
}
