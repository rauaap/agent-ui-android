package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Exact IPv4 + TCP port pairs; the server validates destination policy. */
final class SandboxNetworkDestination {
    final String ip;
    final int port;

    SandboxNetworkDestination(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    static List<SandboxNetworkDestination> from(JSONArray array) throws JSONException {
        List<SandboxNetworkDestination> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            entries.add(new SandboxNetworkDestination(entry.getString("ip"), entry.getInt("port")));
        }
        return entries;
    }

    static JSONArray toJson(List<SandboxNetworkDestination> entries) {
        JSONArray array = new JSONArray();
        for (SandboxNetworkDestination destination : entries) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("ip", destination.ip);
                entry.put("port", destination.port);
            } catch (JSONException e) { throw new IllegalArgumentException(e); }
            array.put(entry);
        }
        return array;
    }

    /** Required array, including when clearing the entire scope's list. */
    static JSONObject replacement(List<SandboxNetworkDestination> entries) {
        JSONObject payload = new JSONObject();
        try { payload.put("sandbox_network_allowlist", toJson(entries)); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return payload;
    }

    static JSONObject replacement(String projectPath, List<SandboxNetworkDestination> entries) {
        JSONObject payload = replacement(entries);
        try { if (projectPath != null) payload.put("path", projectPath); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return payload;
    }

    static boolean isIpv4Literal(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.startsWith("0"))) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    static int parsePort(String value) {
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Enter an integer TCP port (1–65535)");
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Enter an integer TCP port (1–65535)");
        return port;
    }
}
