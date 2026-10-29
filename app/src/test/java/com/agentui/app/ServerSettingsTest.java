package com.agentui.app;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ServerSettingsTest {
    private ServerSettings settings(String directory, Object agent) throws Exception {
        return ServerSettings.from(new JSONObject().put("worktree_path_template", "%P/%N-%B")
                .put("default_working_directory", directory)
                .put("default_agent", agent == null ? JSONObject.NULL : agent));
    }

    @Test public void defaultAgentNullUsesRegistryDefault() throws Exception {
        ServerSettings defaults = settings("/projects/", null);
        assertNull(defaults.agent);
        assertEquals("/projects/new", defaults.directoryFor("new"));
        assertEquals("/custom/new", settings("/custom", "pi").directoryFor("new"));
        assertEquals("pi", settings("/custom", "pi").agent);
    }

    @Test public void unchangedFieldsAreOmitted() throws Exception {
        ServerSettings defaults = settings("/projects/", "pi");
        assertEquals(0, defaults.changes(defaults.template, defaults.directory, defaults.agent).length());
        JSONObject patch = defaults.changes(defaults.template, "/other/", defaults.agent);
        assertEquals(1, patch.length());
        assertEquals("/other/", patch.getString("default_working_directory"));
        patch = defaults.changes(defaults.template, defaults.directory, null);
        assertEquals(1, patch.length());
        assertTrue(patch.has("default_agent"));
        assertTrue(patch.isNull("default_agent"));
        patch = defaults.changes("%P/%b", defaults.directory, "claude-code");
        assertEquals(2, patch.length());
        assertEquals("claude-code", patch.getString("default_agent"));
        assertFalse(patch.has("default_working_directory"));
    }

    @Test public void invalidDefaultsNeverFallBackToLocalValues() throws Exception {
        for (Object directory : new Object[] {JSONObject.NULL, false, 42, " "}) {
            JSONObject body = new JSONObject().put("worktree_path_template", "%P/%N-%B")
                    .put("default_working_directory", directory).put("default_agent", JSONObject.NULL);
            try { ServerSettings.from(body); fail("Accepted invalid directory"); }
            catch (JSONException expected) { }
        }
        for (Object agent : new Object[] {false, 42, " "}) {
            try { settings("/projects/", agent); fail("Accepted invalid agent"); }
            catch (JSONException expected) { }
        }
        try { ServerSettings.from(new JSONObject("{\"worktree_path_template\":\"%P/%N-%B\"}"));
            fail("Accepted missing defaults"); }
        catch (JSONException expected) { }
    }

    @Test public void readsServerTemplateWithoutInterpretingTokens() throws Exception {
        assertEquals("%P/%x-%b", ServerSettings.worktreeTemplate(
                new JSONObject("{\"worktree_path_template\":\"%P/%x-%b\",\"future\":true}")));
    }

    @Test public void patchTouchesOnlyTemplateAndLeavesNormalizationToServer() throws Exception {
        JSONObject patch = ServerSettings.templatePatch("  %P/%N-%B  ");
        assertEquals(1, patch.length());
        assertEquals("  %P/%N-%B  ", patch.getString("worktree_path_template"));
    }

    @Test public void invalidResponsesNeverFallBackToDefault() throws Exception {
        for (String body : new String[] {"{}", "{\"worktree_path_template\":null}",
                "{\"worktree_path_template\":false}", "{\"worktree_path_template\":123}",
                "{\"worktree_path_template\":\"  \"}"}) {
            try {
                ServerSettings.worktreeTemplate(new JSONObject(body));
                fail("Accepted invalid settings: " + body);
            } catch (JSONException expected) { }
        }
    }

    @Test public void freshResponsesCanChangeExpansion() throws Exception {
        String first = ServerSettings.worktreeTemplate(ServerSettings.templatePatch("%P/%N-%B"));
        String second = ServerSettings.worktreeTemplate(ServerSettings.templatePatch("%P/trees/%b"));
        assertEquals("/projects/app-feature-fix", WorktreePath.expand(first, "/projects/app", "feature/fix"));
        assertEquals("/projects/trees/feature/fix", WorktreePath.expand(second, "/projects/app", "feature/fix"));
    }
}
