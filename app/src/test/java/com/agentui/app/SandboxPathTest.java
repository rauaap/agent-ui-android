package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SandboxPathTest {
    @Test public void absentProjectPathsAreEmptyButNotAdvertisedAsSupported() throws Exception {
        Project old = Project.from(new JSONObject("{\"path\":\"/project\"}"));
        assertTrue(old.sandboxPaths.isEmpty());
        assertFalse(old.hasSandboxPaths);
        Project current = Project.from(new JSONObject("{\"sandbox_paths\":[]}"));
        assertTrue(current.sandboxPaths.isEmpty());
        assertTrue(current.hasSandboxPaths);
    }

    @Test public void projectReadPreservesPathsAndDefaultsWriteToFalse() throws Exception {
        Project project = Project.from(new JSONObject("{\"sandbox_paths\":["
                + "{\"path\":\"~/.config/tool\"},"
                + "{\"path\":\"$HOME/data with spaces\",\"write\":true}]}"));
        assertEquals("~/.config/tool", project.sandboxPaths.get(0).path);
        assertFalse(project.sandboxPaths.get(0).write);
        assertTrue(project.sandboxPaths.get(1).write);
    }

    @Test public void roundTripDoesNotExpandTrimOrNormalize() throws Exception {
        List<SandboxPath> original = new ArrayList<>();
        for (String path : Arrays.asList("~/config", "~someone/config", "$HOME/config", "${HOME}/config",
                "/a/../b", "/path with spaces/file ", "$UNDEFINED/config", "/symlink/config")) {
            original.add(new SandboxPath(path, original.size() % 2 == 0));
        }
        List<SandboxPath> result = SandboxPath.from(new JSONArray(SandboxPath.toJson(original).toString()));
        assertEquals(original.size(), result.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).path, result.get(i).path);
            assertEquals(original.get(i).write, result.get(i).write);
        }
    }

    @Test public void exactOverridesSupportBothPermissionDirectionsAndReset() throws Exception {
        for (boolean serverWrite : new boolean[]{false, true}) {
            List<SandboxPath> defaults = Arrays.asList(new SandboxPath("~/config", serverWrite));
            List<SandboxPath> draft = new ArrayList<>();
            draft.add(new SandboxPath(defaults.get(0).path, !serverWrite));
            assertEquals(0, SandboxPath.indexOf(draft, defaults.get(0).path));
            JSONObject replacement = SandboxPath.replacement("/project", draft);
            assertEquals(!serverWrite, replacement.getJSONArray("sandbox_paths").getJSONObject(0).getBoolean("write"));
            draft.remove(0);
            assertEquals(-1, SandboxPath.indexOf(draft, defaults.get(0).path));
            assertEquals(serverWrite, defaults.get(0).write);
            assertEquals(0, SandboxPath.replacement("/project", draft).getJSONArray("sandbox_paths").length());
        }
    }

    @Test public void cannotGuessEquivalentServerPaths() {
        List<SandboxPath> paths = Arrays.asList(new SandboxPath("~/config", false));
        assertEquals(-1, SandboxPath.indexOf(paths, "$HOME/config"));
        assertEquals(-1, SandboxPath.indexOf(paths, "~/config/nested"));
    }

    @Test public void replacementIsExplicitWholeScopeAndDoesNotTouchArchiveOrOtherSettings() throws Exception {
        List<SandboxPath> paths = Arrays.asList(new SandboxPath("/config", false), new SandboxPath("/data", true));
        JSONObject server = SandboxPath.replacement(null, paths);
        assertEquals(1, server.length());
        assertEquals(2, server.getJSONArray("sandbox_paths").length());
        JSONObject project = SandboxPath.replacement("/project with spaces", paths);
        assertEquals(2, project.length());
        assertEquals("/project with spaces", project.getString("path"));
        assertFalse(project.has("archived"));
        assertEquals("[]", SandboxPath.replacement(null, new ArrayList<>()).getJSONArray("sandbox_paths").toString());
        assertEquals(2, paths.size());
    }
}
