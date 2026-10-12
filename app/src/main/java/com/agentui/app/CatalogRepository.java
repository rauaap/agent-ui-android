package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import okhttp3.HttpUrl;

/** Catalog/cache policy, separate from HTTP transport and activity refresh ownership. */
final class CatalogRepository {
    interface Source {
        void listProjects(Api.Cb<List<Project>> cb);
        void listWorktrees(String path, Api.Cb<List<Worktree>> cb);
        void listSessions(Api.Cb<List<Session>> cb);
        void directoryExistence(Api.Cb<DirectorySnapshot> cb);
        void listAgents(Api.Cb<List<Agent>> cb);
    }
    private static final ResourceCache CACHE = new ResourceCache();
    static { Api.setResponseObserver(CatalogRepository::observeResponse); }
    private final Source source;
    private final Supplier<String> identity;
    private final LongSupplier clock;

    CatalogRepository(Api api) {
        identity = api::serverIdentity;
        clock = android.os.SystemClock::elapsedRealtime;
        source = new Source() {
            public void listProjects(Api.Cb<List<Project>> cb) { api.listProjects(cb); }
            public void listWorktrees(String path, Api.Cb<List<Worktree>> cb) { api.listWorktrees(path, cb); }
            public void listSessions(Api.Cb<List<Session>> cb) { api.listSessions(cb); }
            public void directoryExistence(Api.Cb<DirectorySnapshot> cb) { api.directoryExistence(cb); }
            public void listAgents(Api.Cb<List<Agent>> cb) { api.listAgents(cb); }
        };
    }
    CatalogRepository(Source source, Supplier<String> identity, LongSupplier clock) {
        this.source = source; this.identity = identity; this.clock = clock;
    }
    String identity() { return identity.get(); }

    private interface Parser<T> { T parse(String body) throws Exception; }
    private <T> T cached(String resource, long maxAge, Parser<T> parser) {
        String body = CACHE.get(identity(), resource, clock.getAsLong(), maxAge);
        if (body == null) return null;
        try { return parser.parse(body); } catch (Exception ignored) { return null; }
    }
    private static <T> List<T> decodeList(String body, Function<JSONObject, T> decode) throws Exception {
        List<T> result = new ArrayList<>();
        JSONArray array = new JSONArray(body);
        for (int i = 0; i < array.length(); i++) result.add(decode.apply(array.getJSONObject(i)));
        return result;
    }
    List<Project> cachedProjects() { return cached("/projects", Long.MAX_VALUE, b -> decodeList(b, Project::from)); }
    List<Session> cachedSessions() { return cached("/sessions", Long.MAX_VALUE, b -> decodeList(b, Session::from)); }
    List<Worktree> cachedWorktrees(String path) {
        HttpUrl.Builder url = HttpUrl.get("http://cache.invalid/worktrees").newBuilder();
        if (path != null) url.addQueryParameter("project_path", path);
        String query = url.build().encodedQuery();
        return cached("/worktrees" + (query == null ? "" : "?" + query), Long.MAX_VALUE,
                b -> decodeList(b, Worktree::from));
    }
    DirectorySnapshot cachedExistence() {
        DirectorySnapshot snapshot = cached("/directory-existence", Long.MAX_VALUE,
                b -> DirectorySnapshot.from(new JSONObject(b)));
        return snapshot == null ? DirectorySnapshot.unknown() : snapshot;
    }
    void agents(Api.Cb<List<Agent>> cb) {
        List<Agent> agents = cached("/agents", 60_000, b -> Agent.list(new JSONArray(b)));
        if (agents != null) cb.onResult(agents);
        else source.listAgents(cb);
    }

    /** All Api instances notify this policy, including mutations from settings and forms. */
    static Consumer<String> observeResponse(String identity, String method, String resource) {
        return observeResponse(identity, method, resource, android.os.SystemClock::elapsedRealtime);
    }
    static Consumer<String> observeResponse(String identity, String method, String resource,
                                            LongSupplier clock) {
        long version = CACHE.version(identity);
        String path = resource.split("\\?", 2)[0];
        return body -> {
            if (method.equals("GET")) {
                if (path.equals("/projects") || path.equals("/sessions") || path.equals("/worktrees")
                        || path.equals("/directory-existence") || path.equals("/agents")) {
                    CACHE.putIfCurrent(identity, resource, body, clock.getAsLong(), version);
                }
            } else if (path.startsWith("/projects") || path.startsWith("/worktrees") || path.startsWith("/sessions")) {
                for (String catalog : new String[]{"/projects", "/sessions", "/worktrees"}) CACHE.invalidate(identity, catalog);
                if ((path.startsWith("/worktrees") || path.equals("/projects")) && !method.equals("PATCH")) {
                    CACHE.invalidate(identity, "/directory-existence");
                }
            }
        };
    }

    void projects(CatalogProgress progress, Api.Cb<CatalogSnapshot> cb) {
        load(batch -> { batch.sessions(); batch.projects(); }, progress, cb);
    }
    /** Null path means the unscoped/non-worktree session list. */
    void sessions(String worktreeProjectPath, CatalogProgress progress, Api.Cb<CatalogSnapshot> cb) {
        load(batch -> { batch.sessions(); if (worktreeProjectPath != null) batch.worktrees(worktreeProjectPath); }, progress, cb);
    }
    void chat(CatalogProgress progress, Api.Cb<CatalogSnapshot> cb) {
        load(Batch::sessions, progress, cb);
    }
    void worktrees(String projectPath, CatalogProgress progress, Api.Cb<CatalogSnapshot> cb) {
        load(batch -> batch.worktrees(projectPath), progress, cb);
    }
    void removal(String projectPath, CatalogProgress progress, Api.Cb<CatalogSnapshot> cb) {
        sessions(projectPath, progress, cb);
    }

    private void load(Consumer<Batch> plan, CatalogProgress progress, Api.Cb<CatalogSnapshot> cb) {
        new Batch(plan, progress, cb, true).start();
    }
    private final class Batch {
        final Consumer<Batch> plan;
        final CatalogProgress progress;
        final Api.Cb<CatalogSnapshot> cb;
        final boolean retry;
        final List<Runnable> requests = new ArrayList<>();
        List<Project> projects = new ArrayList<>();
        List<Session> sessions = new ArrayList<>();
        List<Worktree> worktrees = new ArrayList<>();
        int remaining;
        boolean failed, supported = true;
        Batch(Consumer<Batch> plan, CatalogProgress progress, Api.Cb<CatalogSnapshot> cb, boolean retry) {
            this.plan = plan; this.progress = progress; this.cb = cb; this.retry = retry;
        }
        void projects() { requests.add(() -> source.listProjects(result(values -> { projects = values; progress.projects(values); }))); }
        void sessions() { requests.add(() -> source.listSessions(result(values -> { sessions = values; progress.sessions(values); }))); }
        void worktrees(String path) {
            requests.add(() -> source.listWorktrees(path, new Api.StatusCb<List<Worktree>>() {
                public void onResult(List<Worktree> values) {
                    if (failed || !progress.isActive()) return;
                    worktrees = values; progress.worktrees(values); done();
                }
                public void onError(String message) { error(message); }
                public void onHttpError(int code, String message) {
                    if (code == 404) { supported = false; onResult(new ArrayList<>()); }
                    else httpError(code, message);
                }
            }));
        }
        <T> Api.StatusCb<T> result(Consumer<T> publish) {
            return new Api.StatusCb<T>() {
                public void onResult(T value) { if (!failed && progress.isActive()) { publish.accept(value); done(); } }
                public void onError(String message) { error(message); }
                public void onHttpError(int code, String message) { httpError(code, message); }
            };
        }
        void start() {
            plan.accept(this);
            remaining = requests.size();
            for (Runnable request : requests) if (progress.isActive()) request.run();
        }
        void error(String message) { if (!failed) { failed = true; cb.onError(message); } }
        void httpError(int code, String message) {
            if (failed) return;
            failed = true;
            if (cb instanceof Api.StatusCb) ((Api.StatusCb<CatalogSnapshot>) cb).onHttpError(code, message);
            else cb.onError(message);
        }
        void done() {
            if (--remaining != 0 || failed || !progress.isActive()) return;
            source.directoryExistence(new Api.StatusCb<DirectorySnapshot>() {
                public void onResult(DirectorySnapshot existence) {
                    if (!progress.isActive()) return;
                    if (retry && !existence.covers(projects, worktrees, sessions)) {
                        new Batch(plan, progress, cb, false).start();
                    } else cb.onResult(new CatalogSnapshot(projects, worktrees, sessions, existence, supported));
                }
                public void onError(String message) { error(message); }
                public void onHttpError(int code, String message) {
                    if (code == 404) cb.onResult(new CatalogSnapshot(projects, worktrees, sessions,
                            DirectorySnapshot.unknown(), supported));
                    else httpError(code, message);
                }
            });
        }
    }
}
