package com.agentui.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class CatalogRepositoryTest {
    static class Source implements CatalogRepository.Source {
        final List<String> calls = new ArrayList<>();
        final Project project = new Project("p", "/app", "App", 0, 0, "", "", true);
        final Worktree worktree = new Worktree("w", "p", "/worktree", "fix", 0, "");
        final Session session = new Session("s", "S", "p", "/worktree", "w", "pi", "idle", "", "archived", false, false);
        Api.Cb<List<Project>> projects;
        Api.Cb<List<Worktree>> worktrees;
        Api.Cb<List<Session>> sessions;
        Api.Cb<DirectorySnapshot> existence;
        final CatalogRepository repository = new CatalogRepository(this, () -> "source", () -> 0L);
        public void listAgents(Api.Cb<List<Agent>> cb) { calls.add("agents"); cb.onResult(List.of()); }
        public void listProjects(Api.Cb<List<Project>> cb) { calls.add("projects"); projects = cb; }
        public void listWorktrees(String path, Api.Cb<List<Worktree>> cb) {
            assertEquals("/app", path); calls.add("worktrees"); worktrees = cb;
        }
        public void listSessions(Api.Cb<List<Session>> cb) { calls.add("sessions"); sessions = cb; }
        public void directoryExistence(Api.Cb<DirectorySnapshot> cb) { calls.add("existence"); existence = cb; }
        void completeCatalogs() {
            if (sessions != null) sessions.onResult(List.of(session));
            if (worktrees != null) worktrees.onResult(List.of(worktree));
            if (projects != null) projects.onResult(List.of(project));
        }
        void completeExistence() {
            existence.onResult(new DirectorySnapshot(Map.of("/app", true, "/worktree", false)));
        }
    }
    static class Result implements Api.Cb<CatalogSnapshot>, CatalogProgress {
        CatalogSnapshot result;
        List<Session> earlySessions;
        String error;
        int publications;
        public void sessions(List<Session> value) { earlySessions = value; }
        public void onResult(CatalogSnapshot value) { result = value; publications++; }
        public void onError(String error) { this.error = error; }
    }
    @Test public void sessionCardsWaitOnlyForSessionsNotOtherResources() {
        Source source = new Source(); Result result = new Result();
        source.repository.sessions("/app", result, result);
        assertEquals(List.of("sessions", "worktrees"), source.calls);
        assertNull(source.projects);
        source.sessions.onResult(List.of(source.session));
        assertEquals(List.of(source.session), result.earlySessions);
        assertNull(source.existence); assertNull(result.result);
        source.worktrees.onResult(List.of(source.worktree));
        assertEquals(List.of("sessions", "worktrees", "existence"), source.calls);
        source.completeExistence();
        assertEquals(1, result.publications);
        assertTrue(result.result.existence.missing("/worktree"));
    }
    @Test public void projectAndSearchScreensDoNotFetchWorktrees() {
        Source source = new Source(); Result result = new Result();
        source.repository.projects(result, result);
        assertEquals(List.of("sessions", "projects"), source.calls);
        source.completeCatalogs(); source.completeExistence();
        assertEquals(List.of("sessions", "projects", "existence"), source.calls);
    }
    @Test public void worktreeListDoesNotFetchSessionsUntilDeletionNeedsThem() {
        Source source = new Source(); Result result = new Result();
        source.repository.worktrees("/app", result, result);
        assertEquals(List.of("worktrees"), source.calls);
        source.completeCatalogs(); source.completeExistence();
        assertEquals(List.of("worktrees", "existence"), source.calls);
        Source deletion = new Source(); Result removed = new Result();
        deletion.repository.removal("/app", removed, removed);
        assertEquals(List.of("sessions", "worktrees"), deletion.calls);
    }
    @Test public void normalChatAndUnscopedSessionsNeverFetchProjectsOrWorktrees() {
        Source source = new Source(); Result result = new Result();
        source.repository.chat(result, result);
        assertEquals(List.of("sessions"), source.calls);
        source.completeCatalogs(); source.completeExistence();
        assertEquals(List.of("sessions", "existence"), source.calls);
        Source unscoped = new Source(); Result list = new Result();
        unscoped.repository.sessions(null, list, list);
        assertEquals(List.of("sessions"), unscoped.calls);
    }
    @Test public void concurrentNewPathRetriesOnlyNeededCatalogs() {
        Source source = new Source(); Result result = new Result();
        source.repository.sessions("/app", result, result);
        source.completeCatalogs(); source.existence.onResult(DirectorySnapshot.unknown());
        assertNull(result.result);
        assertEquals(List.of("sessions", "worktrees", "existence", "sessions", "worktrees"), source.calls);
        source.completeCatalogs(); source.completeExistence();
        assertEquals(1, result.publications);
    }
    @Test public void persistentConcurrentMutationStaysUnknownWithBoundedRetry() {
        Source source = new Source(); Result result = new Result();
        source.repository.sessions("/app", result, result);
        source.completeCatalogs(); source.existence.onResult(DirectorySnapshot.unknown());
        source.completeCatalogs(); source.existence.onResult(DirectorySnapshot.unknown());
        assertFalse(result.result.existence.missing("/worktree"));
        assertEquals(6, source.calls.size());
    }
    @Test public void failedExistenceDoesNotDiscardAlreadyRenderedSessions() {
        Source source = new Source(); Result result = new Result();
        source.repository.sessions("/app", result, result);
        source.completeCatalogs(); source.existence.onError("offline");
        assertNull(result.result); assertEquals("offline", result.error);
        assertEquals(List.of(source.session), result.earlySessions);
    }
    @Test public void nextPollDiscoversRemovalAndRecreationWithoutAnEvent() {
        Source source = new Source(); Result removed = new Result();
        source.session.worktreeId = "";
        source.repository.sessions("/app", removed, removed);
        source.sessions.onResult(List.of(source.session)); source.worktrees.onResult(List.of());
        source.completeExistence();
        assertTrue(removed.result.worktrees.isEmpty());
        Session preserved = removed.result.sessions.get(0);
        assertEquals("s", preserved.id); assertEquals("archived", preserved.archivedAt);
        assertEquals("/worktree", preserved.workingDir);
        assertTrue(preserved.isFormerWorktree("/app"));
        assertTrue(removed.result.existence.missing(preserved.workingDir));
        Result recreated = new Result();
        source.repository.sessions("/app", recreated, recreated);
        source.sessions.onResult(List.of(source.session)); source.worktrees.onResult(List.of());
        source.existence.onResult(new DirectorySnapshot(Map.of("/worktree", true)));
        assertTrue(recreated.result.sessions.get(0).isFormerWorktree("/app"));
        assertFalse(recreated.result.existence.missing(preserved.workingDir));
    }
    @Test public void cancellationPreventsExistenceAndRetryFollowupRequests() {
        Source source = new Source(); Result result = new Result(); RefreshController controller = new RefreshController();
        controller.start(false, run -> source.repository.sessions("/app", run.progress(result), run.finish(result)));
        controller.cancel(); source.completeCatalogs();
        assertNull(result.earlySessions); assertNull(source.existence);

        Source next = new Source();
        controller.start(false, run -> next.repository.sessions("/app", run.progress(result), run.finish(result)));
        next.completeCatalogs(); controller.cancel();
        next.existence.onResult(DirectorySnapshot.unknown());
        assertEquals(3, next.calls.size()); assertNull(result.result);
    }
    @Test public void cachePolicyLivesInTheRepositoryAndReadersAreIndependent() {
        Source source = new Source();
        String identity = "cache-policy";
        CatalogRepository repository = new CatalogRepository(source, () -> identity, () -> 100L);
        CatalogRepository.observeResponse(identity, "GET", "/sessions", () -> 100L).accept(
                "[{\"id\":1,\"working_dir\":\"/worktree\",\"worktree_id\":null}]");
        CatalogRepository.observeResponse(identity, "GET", "/directory-existence", () -> 100L)
                .accept("{\"/worktree\":false}");
        Session first = repository.cachedSessions().get(0);
        first.workingDir = "/changed-by-another-screen";
        assertEquals("/worktree", repository.cachedSessions().get(0).workingDir);
        assertTrue(repository.cachedExistence().missing("/worktree"));

        CatalogRepository.observeResponse(identity, "PATCH", "/sessions/1", () -> 100L).accept("{}");
        assertNull(repository.cachedSessions());
        assertTrue(repository.cachedExistence().missing("/worktree"));
        CatalogRepository.observeResponse(identity, "DELETE", "/worktrees/2", () -> 100L).accept("{}");
        assertFalse(repository.cachedExistence().missing("/worktree"));
        assertFalse(repository.cachedExistence().paths.containsKey("/worktree"));
    }
    @Test public void repositoryCachesStableAgentsOnlyUntilTheirFreshnessDeadline() {
        Source source = new Source(); String identity = "cached-agents"; long[] now = {100L};
        CatalogRepository repository = new CatalogRepository(source, () -> identity, () -> now[0]);
        CatalogRepository.observeResponse(identity, "GET", "/agents", () -> now[0]).accept("[]");
        Api.Cb<List<Agent>> result = new Api.Cb<List<Agent>>() {
            public void onResult(List<Agent> value) { assertTrue(value.isEmpty()); }
            public void onError(String message) { fail(message); }
        };
        repository.agents(result); assertTrue(source.calls.isEmpty());
        now[0] += 60_001L;
        repository.agents(result); assertEquals(List.of("agents"), source.calls);
    }
    @Test public void repositoryRejectsPreMutationResponsesAndKeepsProjectScopesSeparate() {
        Source source = new Source(); String identity = "cache-race";
        CatalogRepository repository = new CatalogRepository(source, () -> identity, () -> 100L);
        java.util.function.Consumer<String> stale = CatalogRepository.observeResponse(identity, "GET", "/sessions", () -> 100L);
        CatalogRepository.observeResponse(identity, "DELETE", "/worktrees/1", () -> 100L).accept("{}");
        stale.accept("[]"); assertNull(repository.cachedSessions());
        CatalogRepository.observeResponse(identity, "GET", "/worktrees?project_path=%2Fapp", () -> 100L).accept("[]");
        assertNotNull(repository.cachedWorktrees("/app"));
        assertNull(repository.cachedWorktrees("/other"));
    }
    @Test public void unsupportedWorktreesAndExistenceRemainUnknown() {
        Source source = new Source(); Result result = new Result();
        source.repository.sessions("/app", result, result);
        source.sessions.onResult(List.of(source.session));
        ((Api.StatusCb<List<Worktree>>) source.worktrees).onHttpError(404, "unsupported");
        ((Api.StatusCb<DirectorySnapshot>) source.existence).onHttpError(404, "unsupported");
        assertFalse(result.result.worktreesSupported);
        assertFalse(result.result.existence.missing("/worktree"));
    }
}
