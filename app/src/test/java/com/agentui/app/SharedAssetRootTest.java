package com.agentui.app;

import static org.junit.Assert.*;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SharedAssetRootTest {
    private List<SharedAssetRoot> roots() throws Exception {
        return SharedAssetRoot.list(new JSONArray("["
                + "{\"asset_root\":\"global\",\"path\":\"/notes\",\"project_id\":null,\"url\":\"/shared-assets/global/\"},"
                + "{\"asset_root\":\"project\",\"path\":\"/reports\",\"project_id\":42,\"url\":\"/shared-assets/project/\"},"
                + "{\"asset_root\":\"other\",\"path\":\"/other\",\"project_id\":7,\"url\":\"/shared-assets/other/\"}]"));
    }

    @Test public void scopesSeparateGlobalAndProjectRoots() throws Exception {
        List<SharedAssetRoot> entries = roots();
        assertEquals("global", SharedAssetRoot.forProject(entries, null).get(0).assetRoot);
        assertEquals(1, SharedAssetRoot.forProject(entries, "").size());
        assertEquals("project", SharedAssetRoot.forProject(entries, "42").get(0).assetRoot);
        assertEquals(1, SharedAssetRoot.forProject(entries, "42").size());
        assertTrue(SharedAssetRoot.forProject(entries, "99").isEmpty());
        assertEquals("42", entries.get(1).projectId);
        assertEquals("/shared-assets/project/", entries.get(1).url);
    }

    @Test public void createHasExplicitScopeAndLiteralPath() {
        JSONObject global = SharedAssetRoot.createPayload("notes", "/notes/$literal/~/", "");
        assertTrue(global.has("project_id"));
        assertTrue(global.isNull("project_id"));
        assertEquals("/notes/$literal/~/", global.optString("path"));
        JSONObject project = SharedAssetRoot.createPayload("reports", "/reports", "42");
        assertEquals(42, project.optInt("project_id"));
        assertTrue(project.opt("project_id") instanceof Number);
    }

    @Test public void editingRenamesWithoutChangingAssociation() {
        JSONObject edit = SharedAssetRoot.editPayload("new-name", "/normalized/path");
        assertEquals("new-name", edit.optString("asset_root"));
        assertEquals("/normalized/path", edit.optString("path"));
        assertFalse(edit.has("project_id"));
        assertEquals(2, edit.length());
        assertEquals("http://server:8080/shared-asset-roots/old-name",
                SharedAssetRoot.endpoint("http://server:8080", "old-name").toString());
        assertEquals("https://server/shared-asset-roots",
                SharedAssetRoot.endpoint("https://server", null).toString());
    }

    @Test public void usesNormalizedResponseRatherThanDraft() throws Exception {
        SharedAssetRoot root = SharedAssetRoot.from(new JSONObject(
                "{\"asset_root\":\"renamed\",\"path\":\"/notes\",\"project_id\":null,\"url\":\"/shared-assets/renamed/\"}"));
        assertEquals("renamed", root.assetRoot);
        assertEquals("/notes", root.path);
        assertEquals("", root.projectId);
        assertEquals("/shared-assets/renamed/", root.url);
    }

    @Test public void identifiersAndPathsFollowServerRules() {
        for (String value : new String[] {"notes", "Research_2026-01", "0"}) {
            assertTrue(value, SharedAssetRoot.validIdentifier(value));
        }
        for (String value : new String[] {"", "two words", "a/b", "../notes", "café", "notes.html"}) {
            assertFalse(value, SharedAssetRoot.validIdentifier(value));
        }
        assertTrue(SharedAssetRoot.validPath("/not-created-yet/reports"));
        assertTrue(SharedAssetRoot.validPath("/literal path/with spaces "));
        assertFalse(SharedAssetRoot.validPath("~/notes"));
        assertFalse(SharedAssetRoot.validPath("$HOME/notes"));
        assertFalse(SharedAssetRoot.validPath("relative/notes"));
        assertFalse(SharedAssetRoot.validPath("/notes\0"));
    }
}
