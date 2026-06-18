package com.agentui.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin REST client for the agent backend. All callbacks are delivered on the
 * main thread. Mirrors the web front-end's fetch() calls:
 *   GET    /sessions
 *   POST   /sessions
 *   PATCH  /sessions/{id}
 *   DELETE /sessions/{id}
 *   POST   /sessions/{id}/stop
 */
final class Api {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    interface Cb<T> {
        void onResult(T value);
        void onError(String message);
    }

    private final OkHttpClient client;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    Api(Context ctx) {
        this.prefs = new Prefs(ctx);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    OkHttpClient http() { return client; }
    Prefs prefs() { return prefs; }

    void listSessions(Cb<List<Session>> cb) {
        Request req = new Request.Builder().url(prefs.httpBase() + "/sessions").get().build();
        enqueue(req, cb, body -> {
            List<Session> out = new ArrayList<>();
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) out.add(Session.from(arr.getJSONObject(i)));
            return out;
        });
    }

    void createSession(String name, String workingDir, String agent, Cb<Session> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
            payload.put("working_dir", workingDir);
            payload.put("agent", agent);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/sessions")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> Session.from(new JSONObject(body)));
    }

    void renameSession(String id, String name, Cb<Session> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/sessions/" + id)
                .patch(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> Session.from(new JSONObject(body)));
    }

    void deleteSession(String id, Cb<Void> cb) {
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/sessions/" + id)
                .delete()
                .build();
        enqueue(req, cb, body -> null);
    }

    void stopSession(String id, Cb<Void> cb) {
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/sessions/" + id + "/stop")
                .post(RequestBody.create(new byte[0], null))
                .build();
        enqueue(req, cb, body -> null);
    }

    /* ----------------------------------------------------------------- */

    private interface Parser<T> { T parse(String body) throws Exception; }

    private <T> void enqueue(Request req, Cb<T> cb, Parser<T> parser) {
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                post(() -> cb.onError(friendly(e)));
            }

            @Override public void onResponse(Call call, Response response) {
                try (ResponseBody rb = response.body()) {
                    String body = rb != null ? rb.string() : "";
                    if (!response.isSuccessful()) {
                        post(() -> cb.onError(serverError(response.code(), body)));
                        return;
                    }
                    final T value = parser.parse(body);
                    post(() -> cb.onResult(value));
                } catch (Exception e) {
                    post(() -> cb.onError(friendly(e)));
                }
            }
        });
    }

    private void post(Runnable r) { main.post(r); }

    private static String serverError(int code, String body) {
        try {
            JSONObject o = new JSONObject(body);
            String detail = o.optString("detail", "");
            if (!detail.isEmpty()) return detail;
        } catch (Exception ignored) {}
        return "Server error " + code;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
