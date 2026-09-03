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
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin REST client for the agent backend. All callbacks are delivered on the
 * main thread. Mirrors the web front-end's fetch() calls:
 *   GET    /agents
 *   GET    /projects
 *   POST   /projects
 *   PATCH  /projects
 *   DELETE /projects
 *   GET    /worktrees
 *   POST   /worktrees
 *   DELETE /worktrees/{id}
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

    /**
     * Callback that also sees the HTTP status, for callers that branch on it —
     * a 404 from {@code GET /projects} means an older server, not a failure.
     */
    interface StatusCb<T> extends Cb<T> {
        void onHttpError(int code, String message);
    }

    /**
     * What {@code DELETE /projects} reports about the project's worktrees. The
     * project and its sessions are gone either way — a worktree git refused to
     * remove is information, not a failed request.
     */
    static final class ProjectDeletion {
        final int worktreesRemoved;
        /** One {@code "path: git's refusal"} line per worktree left behind. */
        final List<String> worktreeErrors;

        ProjectDeletion(int worktreesRemoved, List<String> worktreeErrors) {
            this.worktreesRemoved = worktreesRemoved;
            this.worktreeErrors = worktreeErrors;
        }
    }

    /** What {@code PATCH /projects} reports about the cascade it just ran. */
    static final class ProjectArchive {
        final Project project;
        /** Sessions the cascade archived, or restored. Legitimately 0. */
        final int sessionsAffected;

        ProjectArchive(Project project, int sessionsAffected) {
            this.project = project;
            this.sessionsAffected = sessionsAffected;
        }
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

    /**
     * The agents this server can run, in registration order, for the agent
     * picker. Server-wide rather than project-scoped, and effectively static —
     * it changes when the server is reconfigured, not while the app is open.
     *
     * <p>A 404 means a server predating the endpoint, whose agents are
     * {@link Agent#FALLBACK}; callers that want to tell that from an
     * unreachable server want {@link StatusCb}.
     */
    void listAgents(Cb<List<Agent>> cb) {
        Request req = new Request.Builder().url(prefs.httpBase() + "/agents").get().build();
        enqueue(req, cb, body -> {
            List<Agent> out = new ArrayList<>();
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) out.add(Agent.from(arr.getJSONObject(i)));
            return out;
        });
    }

    void listProjects(Cb<List<Project>> cb) {
        Request req = new Request.Builder().url(prefs.httpBase() + "/projects").get().build();
        enqueue(req, cb, body -> {
            List<Project> out = new ArrayList<>();
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) out.add(Project.from(arr.getJSONObject(i)));
            return out;
        });
    }

    /** Creates the project row and its directory; harmless if both exist. */
    void createProject(String path, String name, Cb<Project> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", path);
            payload.put("name", name);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/projects")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> Project.from(new JSONObject(body)));
    }

    /**
     * Archives or unarchives a project. Archiving cascades to every session in
     * it; unarchiving restores exactly the ones that cascade took, so a session
     * archived by hand beforehand stays archived — which is why the count comes
     * back from the server rather than being guessed from the project row.
     *
     * <p>A 409 means at least one session is busy and <em>nothing was written</em>;
     * its {@code detail} names them.
     */
    void setProjectArchived(String path, boolean archived, Cb<ProjectArchive> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", path);
            payload.put("archived", archived);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/projects")
                .patch(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> {
            JSONObject o = new JSONObject(body);
            return new ProjectArchive(Project.from(o), o.optInt("sessions_affected", 0));
        });
    }

    /**
     * Forgets a project and its sessions. The path travels in the body — a
     * filesystem path has no business in a URL segment — which is why this is
     * a DELETE with content rather than /projects/{path}.
     */
    void deleteProject(String path, Cb<ProjectDeletion> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", path);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/projects")
                .delete(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> {
            JSONObject o = new JSONObject(body);
            List<String> errors = new ArrayList<>();
            JSONArray arr = o.optJSONArray("worktree_errors");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                // Keyed by path: a worktree belongs to the project, not to any
                // one of the sessions that happened to run in it.
                errors.add(e.optString("path", "?") + ": " + e.optString("error", ""));
            }
            return new ProjectDeletion(o.optInt("worktrees_removed", 0), errors);
        });
    }

    /* ----------------------------------------------------------------- */
    /* worktrees                                                         */
    /* ----------------------------------------------------------------- */

    /**
     * The project's worktrees, newest first. The project is identified by path
     * in a query parameter, like every other project-scoped endpoint — an
     * unknown one is a 404.
     *
     * <p>A 404 is also what a server too old to have {@code /worktrees} gives,
     * so callers that use it to decide whether to offer worktrees at all want
     * {@link StatusCb}.
     */
    void listWorktrees(String projectPath, Cb<List<Worktree>> cb) {
        // Built rather than concatenated: the filter is a filesystem path, and
        // its slashes have to survive as query-string encoding.
        HttpUrl base = HttpUrl.parse(prefs.httpBase() + "/worktrees");
        if (base == null) {
            post(() -> cb.onError("Bad server address"));
            return;
        }
        HttpUrl url = base.newBuilder()
                .addQueryParameter("project_path", projectPath)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        enqueue(req, cb, body -> {
            List<Worktree> out = new ArrayList<>();
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) out.add(Worktree.from(arr.getJSONObject(i)));
            return out;
        });
    }

    /**
     * Runs {@code git worktree add -b <branch> <path>}, always cutting a new
     * branch off the project's current HEAD — attaching to an existing branch
     * has no endpoint.
     *
     * <p>{@code path} must be absolute; the server normalises it lexically, so
     * a naive join is enough. Nothing is created on disk when this fails, and
     * a 409 means the worktree is already there — worth {@link StatusCb} to
     * offer the existing one instead.
     */
    void createWorktree(String projectPath, String path, String branch, Cb<Worktree> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("project_path", projectPath);
            payload.put("path", path);
            payload.put("branch", branch);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/worktrees")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> Worktree.from(new JSONObject(body)));
    }

    /**
     * Removes the directory ({@code git worktree remove}, never
     * {@code --force}) and the row.
     *
     * <p>Either 409 — sessions still attached, or a dirty tree — leaves both in
     * place, so a caller must re-render from the state it had rather than
     * dropping the worktree optimistically. {@link StatusCb} separates those
     * from a real failure.
     */
    void deleteWorktree(String id, Cb<Void> cb) {
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/worktrees/" + id)
                .delete()
                .build();
        enqueue(req, cb, body -> null);
    }

    void listSessions(Cb<List<Session>> cb) {
        Request req = new Request.Builder().url(prefs.httpBase() + "/sessions").get().build();
        enqueue(req, cb, body -> {
            List<Session> out = new ArrayList<>();
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) out.add(Session.from(arr.getJSONObject(i)));
            return out;
        });
    }

    /**
     * Create a session under {@code projectPath}, attached to an existing
     * worktree of it — pass an empty or null {@code worktreeId} to run in the
     * project directory itself.
     *
     * <p>The worktree is created by its own endpoint first and merely
     * referenced here, so several sessions can share one and it outlives them
     * all. A stale id is a 404 and a worktree from another project a 400; in
     * both cases no session is created.
     *
     * <p>The path travels as both {@code project_path} and its deprecated
     * spelling {@code working_dir}: a server that knows the new name ignores
     * the old one, and one that doesn't ignores the new one.
     */
    void createSession(String name, String projectPath, String agent,
                       String worktreeId, Cb<Session> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", name);
            payload.put("project_path", projectPath);
            payload.put("working_dir", projectPath);
            payload.put("agent", agent);
            // Omitted rather than sent as null for the plain case: the field is
            // optional, and an absent one reads the same to every server.
            if (worktreeId != null && !worktreeId.isEmpty()) {
                payload.put("worktree_id", Json.wire(worktreeId));
            }
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

    /**
     * Archives or unarchives one session. Unarchiving also unarchives its
     * project if that was archived — a live session under an archived project
     * would have nowhere to show — so refresh the project list afterwards.
     *
     * <p>A 409 means the session is busy. {@code status} does not settle that
     * on its own: a shell command from bash mode runs outside the turn state
     * machine, so an idle-looking session can still refuse.
     */
    void setSessionArchived(String id, boolean archived, Cb<Session> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("archived", archived);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/sessions/" + id)
                .patch(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> Session.from(new JSONObject(body)));
    }

    void setAutoApprove(String id, boolean write, boolean command, Cb<Session> cb) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("auto_approve_write", write);
            payload.put("auto_approve_command", command);
        } catch (Exception ignored) {}
        Request req = new Request.Builder()
                .url(prefs.httpBase() + "/sessions/" + id)
                .patch(RequestBody.create(payload.toString(), JSON))
                .build();
        enqueue(req, cb, body -> Session.from(new JSONObject(body)));
    }

    /**
     * Deletes the session row and its history. Nothing on disk is touched: a
     * worktree the session ran in belongs to the project and stays, along with
     * any other session using it.
     */
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

    @SuppressWarnings("unchecked") // cb's type parameter is the caller's own T
    private <T> void enqueue(Request req, Cb<T> cb, Parser<T> parser) {
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                post(() -> cb.onError(friendly(e)));
            }

            @Override public void onResponse(Call call, Response response) {
                try (ResponseBody rb = response.body()) {
                    String body = rb != null ? rb.string() : "";
                    if (!response.isSuccessful()) {
                        final int code = response.code();
                        final String message = serverError(code, body);
                        post(() -> {
                            if (cb instanceof StatusCb) ((StatusCb<T>) cb).onHttpError(code, message);
                            else cb.onError(message);
                        });
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
            // The app's own errors put a sentence in `detail`. FastAPI's
            // validation errors put a list of objects there, and optString
            // would hand back the whole array as raw JSON — not something to
            // show a user.
            Object detail = o.opt("detail");
            if (detail instanceof String && !((String) detail).isEmpty()) return (String) detail;
        } catch (Exception ignored) {}
        // Session routes type their {id} as an int, so a malformed id is
        // rejected before the handler runs: 422, not the 404 that means the
        // session is gone. Either way it is our bug, not a missing session.
        if (code == 422) return "Server rejected the request (bad id)";
        return "Server error " + code;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
