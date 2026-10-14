package com.agentui.app;

import java.util.List;
import java.util.function.Consumer;

/** Main-thread owner of cache/network ordering and lifecycle cancellation.
 * Views stay with the Activity; raw cache policy and transport stay with the repository. */
final class CatalogLoader {
    interface Listener extends CatalogProgress, Api.StatusCb<CatalogSnapshot> {
        default void cached(CatalogRepository.CachedCatalog cached) {}
        default void onResult(CatalogSnapshot snapshot) {}
        default void onError(String message) {}
        default void onHttpError(int code, String message) { onError(message); }
    }
    private enum Resource { PROJECTS, SESSIONS, WORKTREES, EXISTENCE }
    private interface Request { void load(CatalogProgress progress, Api.Cb<CatalogSnapshot> result); }
    private final CatalogRepository repository;
    private final RefreshController network = new RefreshController();
    private final RefreshController restoration = new RefreshController();
    private final long[] revisions = new long[Resource.values().length];
    private boolean liveSessionMetadata;
    private String identity;

    CatalogLoader(CatalogRepository repository) {
        this.repository = repository;
        identity = repository.identity();
    }

    void restoreProjects(Listener listener) {
        restore(repository::restoreProjects, listener);
        refreshProjects(true, listener);
    }
    void refreshProjects(boolean queueIfBusy, Listener listener) {
        refresh(queueIfBusy, repository::projects, listener);
    }
    void restoreSessions(String projectPath, Listener listener) {
        restore(cb -> repository.restoreSessions(projectPath, cb), listener);
        refreshSessions(projectPath, true, listener);
    }
    void refreshSessions(String projectPath, boolean queueIfBusy, Listener listener) {
        refresh(queueIfBusy, (progress, result) -> repository.sessions(projectPath, progress, result), listener);
    }
    void restoreWorktrees(String projectPath, Listener listener) {
        restore(cb -> repository.restoreWorktrees(projectPath, cb), listener);
        refreshWorktrees(projectPath, listener);
    }
    void refreshWorktrees(String projectPath, Listener listener) {
        refresh(false, (progress, result) -> repository.worktrees(projectPath, progress, result), listener);
    }
    void refreshAfterRemoval(String projectPath, Listener listener) {
        refresh(true, (progress, result) -> repository.removal(projectPath, progress, result), listener);
    }
    void restoreTranscriptMetadata(String projectPath, Listener listener) {
        restore(repository::restoreTranscriptMetadata, listener);
        refreshTranscriptMetadata(projectPath, listener);
    }
    void refreshTranscriptMetadata(String projectPath, Listener listener) {
        refresh(false, projectPath == null ? repository::projects : repository::chat, listener);
    }

    /** Socket metadata is newer than any pending cached session objects. Other
     * cached resources (projects, worktrees, existence) remain independently usable. */
    void sessionMetadataChanged() {
        liveSessionMetadata = true;
        changed(Resource.SESSIONS);
    }

    /** Suppress old results and queued work. A later restore starts a new attempt. */
    void cancel() { network.cancel(); restoration.cancel(); }

    private void selectServer() {
        if (identity.equals(repository.identity())) return;
        cancel();
        identity = repository.identity();
        liveSessionMetadata = false;
    }

    private void changed(Resource resource) { revisions[resource.ordinal()]++; }
    private boolean unchanged(long[] captured, Resource resource) {
        return captured[resource.ordinal()] == revisions[resource.ordinal()];
    }
    private void restore(Consumer<Api.Cb<CatalogRepository.CachedCatalog>> request, Listener listener) {
        selectServer();
        restoration.start(false, run -> {
            long[] captured = revisions.clone();
            request.accept(run.finish(new Api.Cb<CatalogRepository.CachedCatalog>() {
                public void onResult(CatalogRepository.CachedCatalog cached) {
                    CatalogRepository.CachedCatalog useful = new CatalogRepository.CachedCatalog(
                            unchanged(captured, Resource.PROJECTS) ? cached.projects : null,
                            !liveSessionMetadata && unchanged(captured, Resource.SESSIONS) ? cached.sessions : null,
                            unchanged(captured, Resource.WORKTREES) ? cached.worktrees : null,
                            unchanged(captured, Resource.EXISTENCE) ? cached.existence : null);
                    if (useful.hasData()) listener.cached(useful);
                }
                public void onError(String message) { /* Cache is optional; keep the network path. */ }
            }));
        });
    }
    private void refresh(boolean queueIfBusy, Request request, Listener listener) {
        selectServer();
        network.start(queueIfBusy, run -> request.load(run.progress(new CatalogProgress() {
            public void projects(List<Project> values) { changed(Resource.PROJECTS); listener.projects(values); }
            public void sessions(List<Session> values) {
                liveSessionMetadata = false;
                changed(Resource.SESSIONS);
                listener.sessions(values);
            }
            public void worktrees(List<Worktree> values) { changed(Resource.WORKTREES); listener.worktrees(values); }
        }), run.finish(new Api.StatusCb<CatalogSnapshot>() {
            public void onResult(CatalogSnapshot snapshot) {
                changed(Resource.EXISTENCE);
                listener.onResult(snapshot);
            }
            public void onError(String message) { listener.onError(message); }
            public void onHttpError(int code, String message) {
                // A legacy server hand-off must not be followed by cache delivery.
                if (code == 404) restoration.cancel();
                listener.onHttpError(code, message);
            }
        })));
    }
}
