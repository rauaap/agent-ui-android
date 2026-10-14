package com.agentui.app;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CatalogLoaderTest {
    static class Listener implements CatalogLoader.Listener {
        CatalogRepository.CachedCatalog cached;
        List<Session> sessions;
        List<Project> projects;
        List<Worktree> worktrees;
        DirectorySnapshot existence;
        int cachePublications, finalPublications;
        String error;
        public void cached(CatalogRepository.CachedCatalog value) {
            cached = value; cachePublications++;
            if (value.sessions != null) sessions = value.sessions;
            if (value.projects != null) projects = value.projects;
            if (value.worktrees != null) worktrees = value.worktrees;
            if (value.existence != null) existence = value.existence;
        }
        public void sessions(List<Session> value) { sessions = value; }
        public void projects(List<Project> value) { projects = value; }
        public void worktrees(List<Worktree> value) { worktrees = value; }
        public void onResult(CatalogSnapshot value) { existence = value.existence; finalPublications++; }
        public void onError(String message) { error = message; }
    }
    static class Fixture {
        final CachedCatalogAsyncTest.Queue worker = new CachedCatalogAsyncTest.Queue();
        final CachedCatalogAsyncTest.Queue main = new CachedCatalogAsyncTest.Queue();
        final CatalogRepositoryTest.Source source = new CatalogRepositoryTest.Source();
        final String server;
        final CatalogLoader loader;
        Fixture(String server) {
            this.server = server;
            loader = new CatalogLoader(new CatalogRepository(source, () -> server, () -> 100L, worker, main));
        }
        void seed() {
            CachedCatalogAsyncTest.seed(server, "/projects",
                    "[{\"id\":\"p\",\"path\":\"/app\",\"name\":\"Cached\",\"sandbox_network_allowlist\":[]}]");
            CachedCatalogAsyncTest.seed(server, "/sessions",
                    "[{\"id\":\"s\",\"name\":\"Cached\",\"working_dir\":\"/worktree\"}]");
            CachedCatalogAsyncTest.seed(server, "/worktrees?project_path=%2Fapp", "[]");
            CachedCatalogAsyncTest.seed(server, "/directory-existence", "{\"/worktree\":false}");
        }
        void deliverCache() { worker.next(); main.next(); }
    }

    @Test public void cacheFirstShowsContentThenNetworkUpdatesItWithoutWaitingForCache() {
        Fixture f = new Fixture("loader-cache-first"); f.seed(); Listener view = new Listener();
        f.loader.restoreProjects(view);
        assertEquals(List.of("sessions", "projects"), f.source.calls);
        assertNull(view.sessions);
        f.deliverCache();
        assertEquals("Cached", view.sessions.get(0).name);
        f.source.completeCatalogs(); f.source.completeExistence();
        assertSame(f.source.session, view.sessions.get(0));
        assertSame(f.source.project, view.projects.get(0));
        assertEquals(1, view.finalPublications);
    }

    @Test public void freshSessionsDoNotSuppressCachedProjectsOrExistence() {
        Fixture f = new Fixture("loader-partial-projects"); f.seed(); Listener view = new Listener();
        f.loader.restoreProjects(view);
        f.source.sessions.onResult(List.of(f.source.session));
        f.deliverCache();
        assertNull(view.cached.sessions);
        assertSame(f.source.session, view.sessions.get(0));
        assertEquals("Cached", view.projects.get(0).name);
        assertTrue(view.existence.missing("/worktree"));
    }

    @Test public void freshWorktreesDoNotSuppressCachedSessionsOrExistence() {
        Fixture f = new Fixture("loader-partial-sessions"); f.seed(); Listener view = new Listener();
        f.loader.restoreSessions("/app", view);
        f.source.worktrees.onResult(List.of(f.source.worktree));
        f.deliverCache();
        assertNull(view.cached.worktrees);
        assertSame(f.source.worktree, view.worktrees.get(0));
        assertEquals("Cached", view.sessions.get(0).name);
        assertTrue(view.existence.missing("/worktree"));
        assertNull(view.projects);
    }

    @Test public void fullyFreshNetworkResultLeavesNothingForLateCacheToReplace() {
        Fixture f = new Fixture("loader-network-first"); f.seed(); Listener view = new Listener();
        f.loader.restoreProjects(view);
        f.source.completeCatalogs(); f.source.completeExistence();
        DirectorySnapshot freshExistence = view.existence;
        f.deliverCache();
        assertEquals(0, view.cachePublications);
        assertSame(freshExistence, view.existence);
        assertSame(f.source.project, view.projects.get(0));
    }

    @Test public void leavingCancelsBothCacheAndNetworkPublications() {
        Fixture f = new Fixture("loader-leave"); f.seed(); Listener view = new Listener();
        f.loader.restoreProjects(view);
        f.worker.next();
        f.loader.cancel();
        f.main.next(); f.source.completeCatalogs();
        assertEquals(0, view.cachePublications);
        assertNull(view.sessions); assertNull(view.projects);
        assertNull(f.source.existence);
    }

    @Test public void failedNetworkStillAllowsPendingCacheFallback() {
        Fixture f = new Fixture("loader-offline"); f.seed(); Listener view = new Listener();
        f.loader.restoreProjects(view);
        f.source.sessions.onError("offline");
        f.deliverCache();
        assertEquals("offline", view.error);
        assertEquals("Cached", view.projects.get(0).name);
    }

    @Test public void transcriptRestorationCanRetryAfterLeavingBeforeInitialization() {
        Fixture f = new Fixture("loader-metadata-retry"); f.seed(); Listener view = new Listener();
        f.loader.restoreTranscriptMetadata("/app", view);
        f.loader.cancel();
        f.loader.restoreTranscriptMetadata("/app", view);
        // Old decoding may complete, but only the new foreground attempt is published.
        f.deliverCache(); assertEquals(0, view.cachePublications);
        f.deliverCache(); assertEquals(1, view.cachePublications);
        assertEquals("s", view.sessions.get(0).id);
        assertEquals(List.of("sessions", "sessions"), f.source.calls);
        // Once metadata is initialized, the Activity chooses refresh instead of restore.
        f.loader.cancel();
        f.loader.refreshTranscriptMetadata("/app", view);
        assertTrue(f.worker.tasks.isEmpty());
    }

    @Test public void socketMetadataBlocksCachedSessionsButNotLocationResourcesAcrossRetry() {
        Fixture f = new Fixture("loader-socket"); f.seed(); Listener view = new Listener();
        f.loader.restoreTranscriptMetadata("/app", view);
        f.loader.sessionMetadataChanged();
        f.deliverCache();
        assertNull(view.cached.sessions);
        assertNotNull(view.cached.projects);
        assertTrue(view.existence.missing("/worktree"));
        f.loader.cancel();
        f.loader.restoreTranscriptMetadata("/app", view);
        f.deliverCache();
        assertNull(view.cached.sessions);
        assertNotNull(view.cached.projects);
        f.source.sessions.onResult(List.of(f.source.session));
        assertSame(f.source.session, view.sessions.get(0));
    }

    @Test public void queuedActionRefreshAndPollingStillHaveOneNetworkOwner() {
        Fixture f = new Fixture("loader-queue"); Listener view = new Listener();
        f.loader.refreshProjects(false, view);
        f.loader.refreshProjects(false, view);
        f.loader.refreshProjects(true, view);
        f.loader.refreshProjects(true, view);
        assertEquals(2, f.source.calls.size());
        f.source.completeCatalogs(); f.source.completeExistence();
        assertEquals(List.of("sessions", "projects", "existence", "sessions", "projects"), f.source.calls);
    }

    @Test public void serverChangeCancelsOldWorkAndDoesNotCarrySocketFreshnessIntoNewCache() {
        CachedCatalogAsyncTest.Queue worker = new CachedCatalogAsyncTest.Queue();
        CachedCatalogAsyncTest.Queue main = new CachedCatalogAsyncTest.Queue();
        CatalogRepositoryTest.Source source = new CatalogRepositoryTest.Source();
        String[] server = {"loader-server-a"};
        CatalogLoader loader = new CatalogLoader(new CatalogRepository(source, () -> server[0],
                () -> 100L, worker, main));
        CachedCatalogAsyncTest.seed(server[0], "/sessions", "[]");
        Listener oldView = new Listener();
        loader.restoreTranscriptMetadata("/app", oldView);
        Api.Cb<List<Session>> oldResponse = source.sessions;
        loader.sessionMetadataChanged();
        server[0] = "loader-server-b";
        CachedCatalogAsyncTest.seed(server[0], "/sessions", "[{\"id\":\"new-server\"}]");
        Listener newView = new Listener();
        loader.restoreTranscriptMetadata("/app", newView);
        oldResponse.onResult(List.of(source.session));
        worker.next(); main.next();
        worker.next(); main.next();
        assertNull(oldView.sessions);
        assertEquals(0, oldView.cachePublications);
        assertEquals("new-server", newView.sessions.get(0).id);
    }

    @Test public void namedOperationsKeepTranscriptAndWorktreeRequestsScoped() {
        Fixture chat = new Fixture("loader-chat-scope");
        chat.loader.restoreTranscriptMetadata(null, new Listener());
        assertEquals(List.of("sessions", "projects"), chat.source.calls);
        Fixture trees = new Fixture("loader-tree-scope");
        trees.loader.restoreWorktrees("/app", new Listener());
        assertEquals(List.of("worktrees"), trees.source.calls);
    }
}
