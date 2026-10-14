package com.agentui.app;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import static org.junit.Assert.*;

public class CachedCatalogAsyncTest {
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void next() { tasks.remove().run(); }
    }
    static final class Result<T> implements Api.Cb<T> {
        T value;
        String error;
        int publications;
        public void onResult(T value) { this.value = value; publications++; }
        public void onError(String message) { error = message; }
    }
    static void seed(String server, String resource, String body) {
        CatalogRepository.observeResponse(server, "GET", resource, () -> 100L).accept(body);
    }

    @Test public void allCachedCatalogsDecodeOnWorkerAndPublishOnlyOnDeliveryExecutor() {
        Queue worker = new Queue(), main = new Queue();
        CatalogRepository repository = new CatalogRepository(new CatalogRepositoryTest.Source(),
                () -> "async-catalogs", () -> 100L, worker, main);
        seed("async-catalogs", "/projects", "[{\"id\":1,\"path\":\"/app\",\"sandbox_network_allowlist\":[]}]");
        seed("async-catalogs", "/sessions", "[{\"id\":2,\"working_dir\":\"/tree\"}]");
        seed("async-catalogs", "/worktrees?project_path=%2Fapp", "[]");
        seed("async-catalogs", "/directory-existence", "{\"/tree\":false}");
        Result<CatalogRepository.CachedCatalog> result = new Result<>();
        repository.restoreSessions("/app", result);
        assertEquals(1, worker.tasks.size());
        assertTrue(main.tasks.isEmpty());
        assertNull(result.value);
        worker.next();
        assertNull(result.value);
        assertEquals(1, main.tasks.size());
        main.next();
        assertNull(result.value.projects);
        assertEquals("2", result.value.sessions.get(0).id);
        assertTrue(result.value.worktrees.isEmpty());
        assertTrue(result.value.existence.missing("/tree"));
        repository.restoreProjects(result);
        worker.next(); main.next();
        assertEquals("1", result.value.projects.get(0).id);
        assertNull(result.value.worktrees);
    }

    @Test public void missingAndMalformedCacheStillDeliverUsableUnknownSnapshot() {
        Queue worker = new Queue(), main = new Queue();
        CatalogRepository repository = new CatalogRepository(new CatalogRepositoryTest.Source(),
                () -> "async-malformed", () -> 100L, worker, main);
        seed("async-malformed", "/sessions", "not json");
        seed("async-malformed", "/directory-existence", "not json");
        Result<CatalogRepository.CachedCatalog> result = new Result<>();
        repository.restoreProjects(result);
        worker.next(); main.next();
        assertNull(result.value.projects);
        assertNull(result.value.sessions);
        assertNull(result.value.worktrees);
        assertNull(result.value.existence);
    }

    @Test public void cancellationAfterDecodingPreventsCacheFromReplacingFreshContent() {
        Queue worker = new Queue(), main = new Queue();
        CatalogRepository repository = new CatalogRepository(new CatalogRepositoryTest.Source(),
                () -> "async-cancel", () -> 100L, worker, main);
        seed("async-cancel", "/sessions", "[]");
        RefreshController owner = new RefreshController();
        Result<CatalogRepository.CachedCatalog> result = new Result<>();
        owner.start(false, run -> repository.restoreSessions(null, run.finish(result)));
        worker.next();
        // Screens cancel restoration on pause and on the first fresh catalog/socket result.
        owner.cancel(); main.next();
        assertEquals(0, result.publications);
        assertNull(result.error);
    }

    @Test public void serverSwitchBeforeDeliveryRejectsOldModels() {
        Queue worker = new Queue(), main = new Queue();
        String[] server = {"async-server-a"};
        CatalogRepository repository = new CatalogRepository(new CatalogRepositoryTest.Source(),
                () -> server[0], () -> 100L, worker, main);
        seed(server[0], "/sessions", "[]");
        Result<CatalogRepository.CachedCatalog> result = new Result<>();
        repository.restoreSessions(null, result);
        worker.next(); server[0] = "async-server-b"; main.next();
        assertNull(result.value);
        assertNotNull(result.error);
    }

    @Test public void mutationDuringDecodeRejectsCapturedCache() {
        Queue worker = new Queue(), main = new Queue();
        CatalogRepository repository = new CatalogRepository(new CatalogRepositoryTest.Source(),
                () -> "async-mutation", () -> 100L, worker, main);
        seed("async-mutation", "/sessions", "[]");
        Result<CatalogRepository.CachedCatalog> result = new Result<>();
        repository.restoreSessions(null, result);
        CatalogRepository.observeResponse("async-mutation", "PATCH", "/sessions/1", () -> 100L).accept("{}");
        worker.next(); main.next();
        assertNull(result.value);
        assertNotNull(result.error);
    }

    @Test public void agentsCacheHitAlsoUsesWorkerAndDeliveryExecutors() {
        Queue worker = new Queue(), main = new Queue();
        CatalogRepositoryTest.Source source = new CatalogRepositoryTest.Source();
        CatalogRepository repository = new CatalogRepository(source,
                () -> "async-agents", () -> 100L, worker, main);
        seed("async-agents", "/agents", "[]");
        Result<List<Agent>> result = new Result<>();
        repository.agents(result);
        assertNull(result.value); assertTrue(source.calls.isEmpty());
        worker.next(); assertNull(result.value);
        main.next(); assertTrue(result.value.isEmpty());
        assertTrue(source.calls.isEmpty());
    }

    @Test public void malformedAgentsFallBackToNetworkOnDeliveryThread() {
        Queue worker = new Queue(), main = new Queue();
        CatalogRepositoryTest.Source source = new CatalogRepositoryTest.Source();
        CatalogRepository repository = new CatalogRepository(source,
                () -> "async-bad-agents", () -> 100L, worker, main);
        seed("async-bad-agents", "/agents", "bad json");
        Result<List<Agent>> result = new Result<>();
        repository.agents(result); worker.next();
        assertTrue(source.calls.isEmpty());
        main.next(); assertEquals(List.of("agents"), source.calls);
        assertTrue(result.value.isEmpty());
    }
}
