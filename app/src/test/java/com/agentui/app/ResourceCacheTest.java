package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResourceCacheTest {
    @Test public void navigationCanReusePreviouslyFetchedResources() {
        ResourceCache cache = new ResourceCache();
        cache.put("server/token", "/sessions", "sessions", 100);
        cache.put("server/token", "/directory-existence", "existence", 110);
        assertEquals("sessions", cache.get("server/token", "/sessions", 200, Long.MAX_VALUE));
        assertEquals("existence", cache.get("server/token", "/directory-existence", 200, Long.MAX_VALUE));
        assertNull(cache.get("server/token", "/projects", 200, Long.MAX_VALUE));
    }
    @Test public void serverAndAuthenticationChangesCannotReuseAnotherServersValues() {
        ResourceCache cache = new ResourceCache();
        cache.put("server/token1", "/sessions", "old", 0);
        assertNull(cache.get("server/token2", "/sessions", 1, Long.MAX_VALUE));
        cache.put("server/token2", "/sessions", "new", 2);
        assertNull(cache.get("other-server/token2", "/sessions", 3, Long.MAX_VALUE));
    }
    @Test public void stableResourcesExpireAndMutationsInvalidateCatalogs() {
        ResourceCache cache = new ResourceCache();
        cache.put("s", "/agents", "agents", 100);
        assertEquals("agents", cache.get("s", "/agents", 60_100, 60_000));
        assertNull(cache.get("s", "/agents", 60_101, 60_000));
        cache.put("s", "/worktrees?project_path=app", "worktrees", 100);
        cache.invalidate("s", "/worktrees");
        assertNull(cache.get("s", "/worktrees?project_path=app", 200, Long.MAX_VALUE));
        assertEquals("agents", cache.get("s", "/agents", 200, 60_000));
    }
    @Test public void readersDecodeIndependentObjects() throws Exception {
        ResourceCache cache = new ResourceCache();
        cache.put("s", "/directory-existence", "{\"/missing\":false}", 0);
        JSONObject first = new JSONObject(cache.get("s", "/directory-existence", 1, Long.MAX_VALUE));
        first.put("/missing", true);
        DirectorySnapshot nextScreen = DirectorySnapshot.from(new JSONObject(
                cache.get("s", "/directory-existence", 1, Long.MAX_VALUE)));
        assertTrue(nextScreen.missing("/missing"));
    }
    @Test public void staleInflightResponsesCannotRefillInvalidatedCaches() {
        ResourceCache cache = new ResourceCache();
        long beforeMutation = cache.version("s");
        cache.invalidate("s", "/sessions");
        cache.putIfCurrent("s", "/sessions", "old", 100, beforeMutation);
        assertNull(cache.get("s", "/sessions", 101, Long.MAX_VALUE));
        cache.putIfCurrent("s", "/sessions", "new", 102, cache.version("s"));
        assertEquals("new", cache.get("s", "/sessions", 103, Long.MAX_VALUE));
    }
    @Test public void projectScopesAreSeparateAndCacheIsBounded() {
        ResourceCache cache = new ResourceCache();
        for (int i = 0; i < 65; i++) cache.put("s", "/worktrees?project_path=" + i, "w" + i, i);
        assertNull(cache.get("s", "/worktrees?project_path=0", 100, Long.MAX_VALUE));
        assertEquals("w64", cache.get("s", "/worktrees?project_path=64", 100, Long.MAX_VALUE));
    }
}
