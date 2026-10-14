package com.agentui.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
    private static final java.util.concurrent.Executor DECODER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "catalog-cache-decoder");
                thread.setDaemon(true);
                return thread;
            });
    private final java.util.concurrent.Executor decoder;
    private final java.util.concurrent.Executor delivery;
    private final Source source;
    private final Supplier<String> identity;
    private final LongSupplier clock;

    CatalogRepository(Api api) {
        decoder = DECODER;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        delivery = task -> main.post(task);
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
    CatalogRepository(Source source, Supplier<String> identity, LongSupplier clock,
                      java.util.concurrent.Executor decoder, java.util.concurrent.Executor delivery) {
        this.source = source; this.identity = identity; this.clock = clock;
        this.decoder = decoder; this.delivery = delivery;
    }
    String identity() { return identity.get(); }

    private interface Parser<T> { T parse(String body) throws Exception; }
    private String cachedBody(String resource, long maxAge) {
        return CACHE.get(identity(), resource, clock.getAsLong(), maxAge);
    }
    private static <T> T decode(String body, Parser<T> parser) {
        if (body == null) return null;
        try { return parser.parse(body); } catch (Exception ignored) { return null; }
    }

    /** Capture raw responses on the owner thread; only decoding runs in the worker.
     * CatalogLoader owns lifecycle cancellation and per-resource freshness. */
    private <T> void decodeAsync(Supplier<T> parse, Api.Cb<T> cb) {
        String server = identity();
        long version = CACHE.version(server);
        decoder.execute(() -> {
            T value = parse.get();
            delivery.execute(() -> {
                if (!server.equals(identity()) || version != CACHE.version(server)) {
                    cb.onError("Cached catalog changed");
                    return;
                }
                cb.onResult(value);
            });
        });
    }
    /** Partial restoration: null means absent, malformed, or superseded. Consumers
     * merge only non-null resources; an empty list is still valid cached data. */
    static final class CachedCatalog {
        final List<Project> projects;
        final List<Session> sessions;
        final List<Worktree> worktrees;
        final DirectorySnapshot existence;
        CachedCatalog(List<Project> projects, List<Session> sessions, List<Worktree> worktrees,
                      DirectorySnapshot existence) {
            this.projects = projects; this.sessions = sessions; this.worktrees = worktrees;
            this.existence = existence;
        }
        boolean hasData() {
            return projects != null || sessions != null || worktrees != null || existence != null;
        }
    }

    void restoreProjects(Api.Cb<CachedCatalog> cb) { restore(CachePlan.PROJECTS, null, cb); }
    void restoreSessions(String projectPath, Api.Cb<CachedCatalog> cb) { restore(CachePlan.SESSIONS, projectPath, cb); }
    void restoreWorktrees(String projectPath, Api.Cb<CachedCatalog> cb) { restore(CachePlan.WORKTREES, projectPath, cb); }
    void restoreTranscriptMetadata(Api.Cb<CachedCatalog> cb) { restore(CachePlan.TRANSCRIPT, null, cb); }

    private enum CachePlan { PROJECTS, SESSIONS, WORKTREES, TRANSCRIPT }

    /** Missing/malformed resources stay null, distinct from cached empty/unknown data. */
    private void restore(CachePlan plan, String worktreePath, Api.Cb<CachedCatalog> cb) {
        String projectBody = plan == CachePlan.PROJECTS || plan == CachePlan.TRANSCRIPT
                ? cachedBody("/projects", Long.MAX_VALUE) : null;
        String sessionBody = plan != CachePlan.WORKTREES ? cachedBody("/sessions", Long.MAX_VALUE) : null;
        String worktreeBody = null;
        if (worktreePath != null) {
            HttpUrl url = HttpUrl.get("http://cache.invalid/worktrees").newBuilder()
                    .addQueryParameter("project_path", worktreePath).build();
            worktreeBody = cachedBody("/worktrees?" + url.encodedQuery(), Long.MAX_VALUE);
        }
        String trees = worktreeBody;
        String existenceBody = cachedBody("/directory-existence", Long.MAX_VALUE);
        decodeAsync(() -> new CachedCatalog(
                decode(projectBody, CatalogJson::projects),
                decode(sessionBody, CatalogJson::sessions),
                decode(trees, CatalogJson::worktrees),
                decode(existenceBody, CatalogJson::existence)), cb);
    }

    void agents(Api.Cb<List<Agent>> cb) {
        String body = cachedBody("/agents", 60_000);
        if (body == null) { source.listAgents(cb); return; }
        decodeAsync(() -> decode(body, CatalogJson::agents), new Api.Cb<List<Agent>>() {
            public void onResult(List<Agent> agents) {
                if (agents != null) cb.onResult(agents);
                else source.listAgents(cb);
            }
            public void onError(String message) { cb.onError(message); }
        });
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
