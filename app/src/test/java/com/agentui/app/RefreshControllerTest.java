package com.agentui.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RefreshControllerTest {
    static class Result implements Api.StatusCb<String> {
        final List<String> events = new ArrayList<>();
        public void onResult(String value) { events.add(value); }
        public void onError(String message) { events.add("error:" + message); }
        public void onHttpError(int code, String message) { events.add("http:" + code); }
    }
    @Test public void pollsDoNotOverlapAndAnActionQueuesOneLatestRefresh() {
        RefreshController controller = new RefreshController(); Result result = new Result();
        List<Api.StatusCb<String>> requests = new ArrayList<>();
        controller.start(false, run -> requests.add(run.finish(result)));
        controller.start(false, run -> fail("overlapping poll"));
        controller.start(true, run -> fail("superseded queued action"));
        controller.start(true, run -> requests.add(run.finish(result)));
        assertEquals(1, requests.size());
        requests.get(0).onResult("first");
        assertEquals(2, requests.size());
        requests.get(1).onResult("queued");
        assertEquals(List.of("first", "queued"), result.events);
    }
    @Test public void cancellationSuppressesEarlyAndFinalCallbacksAndDropsQueuedActions() {
        RefreshController controller = new RefreshController(); Result result = new Result();
        List<RefreshController.Run> runs = new ArrayList<>();
        controller.start(false, runs::add);
        Api.Cb<String> callback = runs.get(0).finish(result);
        controller.start(true, run -> fail("cancelled queued action"));
        controller.cancel();
        runs.get(0).deliver(() -> fail("late progress"));
        callback.onResult("late");
        assertTrue(result.events.isEmpty());
        controller.start(false, runs::add);
        assertEquals(2, runs.size());
    }
    @Test public void oldCompletionCannotFinishTheResumedRefresh() {
        RefreshController controller = new RefreshController(); Result result = new Result();
        List<RefreshController.Run> runs = new ArrayList<>();
        controller.start(false, runs::add);
        Api.Cb<String> old = runs.get(0).finish(result);
        controller.cancel(); controller.start(false, runs::add);
        old.onResult("old");
        assertTrue(runs.get(1).isCurrent());
        runs.get(1).finish(result).onResult("new");
        assertEquals(List.of("new"), result.events);
    }
    @Test public void errorsAndHttpStatusesReleaseTheGuardAndForwardExactlyOnce() {
        RefreshController controller = new RefreshController(); Result result = new Result();
        List<Api.StatusCb<String>> requests = new ArrayList<>();
        controller.start(false, run -> requests.add(run.finish(result)));
        controller.start(true, run -> requests.add(run.finish(result)));
        requests.get(0).onError("offline");
        assertEquals(2, requests.size());
        requests.get(0).onResult("duplicate");
        requests.get(1).onHttpError(404, "missing");
        assertEquals(List.of("error:offline", "http:404"), result.events);
        controller.start(false, run -> requests.add(run.finish(result)));
        assertEquals(3, requests.size());
    }
    @Test public void callbackExceptionsStillReleaseTheGuard() {
        RefreshController controller = new RefreshController();
        List<Api.Cb<String>> requests = new ArrayList<>();
        controller.start(false, run -> requests.add(run.finish(new Api.Cb<String>() {
            public void onResult(String ignored) { throw new IllegalStateException("render failed"); }
            public void onError(String ignored) {}
        })));
        try { requests.get(0).onResult("value"); fail("expected exception"); }
        catch (IllegalStateException expected) { assertEquals("render failed", expected.getMessage()); }
        controller.start(false, run -> assertTrue(run.isCurrent()));
    }
}
