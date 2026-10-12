package com.agentui.app;

import java.util.List;
import java.util.function.Consumer;

/** One refresh per owner, one queued action refresh, and no publication after cancellation. */
final class RefreshController {
    private Run current;
    private Consumer<Run> pending;

    void start(boolean queueIfBusy, Consumer<Run> task) {
        if (current != null) {
            if (queueIfBusy) pending = task;
            return;
        }
        Run run = new Run();
        current = run;
        try { task.accept(run); }
        catch (RuntimeException e) { run.complete(); throw e; }
    }

    /** Invalidates callbacks; transport may still finish and warm its cache. */
    void cancel() { current = null; pending = null; }

    final class Run {
        boolean isCurrent() { return current == this; }
        void deliver(Runnable callback) { if (isCurrent()) callback.run(); }
        private void complete() {
            if (!isCurrent()) return;
            current = null;
            Consumer<Run> next = pending;
            pending = null;
            if (next != null) start(false, next);
        }
        <T> Api.StatusCb<T> finish(Api.Cb<T> callback) {
            return new Api.StatusCb<T>() {
                public void onResult(T value) {
                    if (!isCurrent()) return;
                    try { callback.onResult(value); } finally { complete(); }
                }
                public void onError(String message) {
                    if (!isCurrent()) return;
                    try { callback.onError(message); } finally { complete(); }
                }
                public void onHttpError(int code, String message) {
                    if (!isCurrent()) return;
                    try {
                        if (callback instanceof Api.StatusCb) ((Api.StatusCb<T>) callback).onHttpError(code, message);
                        else callback.onError(message);
                    } finally { complete(); }
                }
            };
        }
        CatalogProgress progress(CatalogProgress callback) {
            return new CatalogProgress() {
                public boolean isActive() { return isCurrent(); }
                public void projects(List<Project> values) { deliver(() -> callback.projects(values)); }
                public void sessions(List<Session> values) { deliver(() -> callback.sessions(values)); }
                public void worktrees(List<Worktree> values) { deliver(() -> callback.worktrees(values)); }
            };
        }
    }
}
