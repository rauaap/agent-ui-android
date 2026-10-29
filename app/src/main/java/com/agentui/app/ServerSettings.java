package com.agentui.app;

import org.json.JSONException;
import org.json.JSONObject;

/** Wire format only: no local persistence, defaults, or token interpretation. */
final class ServerSettings {
    final String template;
    final String directory;
    final String agent;

    private ServerSettings(String template, String directory, String agent) {
        this.template = template;
        this.directory = directory;
        this.agent = agent;
    }

    static ServerSettings from(JSONObject settings) throws JSONException {
        Object directory = settings.get("default_working_directory");
        Object agent = settings.get("default_agent");
        if (!(directory instanceof String) || ((String) directory).trim().isEmpty()
                || (agent != JSONObject.NULL && (!(agent instanceof String)
                || ((String) agent).trim().isEmpty()))) {
            throw new JSONException("Invalid server defaults");
        }
        return new ServerSettings(worktreeTemplate(settings), (String) directory,
                agent == JSONObject.NULL ? null : (String) agent);
    }

    /** Only edited fields are sent, so another client's unrelated edits survive. */
    JSONObject changes(String template, String directory, String agent) {
        JSONObject patch = new JSONObject();
        try {
            if (!this.template.equals(template)) patch.put("worktree_path_template", template);
            if (!this.directory.equals(directory)) patch.put("default_working_directory", directory);
            if (!java.util.Objects.equals(this.agent, agent))
                patch.put("default_agent", agent == null ? JSONObject.NULL : agent);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return patch;
    }

    String directoryFor(String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    static String worktreeTemplate(JSONObject settings) throws JSONException {
        Object value = settings.get("worktree_path_template");
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new JSONException("Invalid worktree path template in server settings");
        }
        return (String) value;
    }

    static JSONObject templatePatch(String template) {
        JSONObject body = new JSONObject();
        try { body.put("worktree_path_template", template); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return body;
    }
}
