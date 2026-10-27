package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ToolPreviewTest {
    @Test public void titlesNormalizeSessionTools() {
        assertEquals("MESSAGE SESSION", ToolPreview.title("mcp__agent_ui__message_session"));
        assertEquals("MESSAGE SESSION", ToolPreview.title("message_session"));
        assertEquals("WRITE", ToolPreview.title("Write"));
    }

    @Test public void summaryUsesTheFirstIdentifyingField() throws Exception {
        assertEquals("ls -la", ToolPreview.summary(new JSONObject().put("command", "ls -la")));
        assertEquals("/a.txt", ToolPreview.summary(new JSONObject().put("file_path", "/a.txt").put("content", "x")));
        assertEquals("", ToolPreview.summary(new JSONObject().put("command", 5)));
        assertEquals("", ToolPreview.summary(null));
    }

    @Test public void missingTargetIsComposing() throws Exception {
        assertTrue(ToolPreview.awaitingTarget("message_session", new JSONObject().put("message", "hi")));
        assertTrue(ToolPreview.awaitingTarget("message_session", new JSONObject().put("session_id", "")));
        assertFalse(ToolPreview.awaitingTarget("message_session", new JSONObject().put("session_id", 42)));
        assertFalse(ToolPreview.awaitingTarget("start_session", new JSONObject()));
    }

    @Test public void bodyPrefersGrowingContentThenPreviewThenRaw() throws Exception {
        assertEquals("line 1\nli", ToolPreview.body(
                new JSONObject().put("file_path", "/a").put("content", "line 1\nli"), "raw"));
        String pretty = ToolPreview.body(new JSONObject().put("command", "ls"), "raw");
        assertTrue(pretty.contains("\"command\": \"ls\""));
        assertEquals("{\"comm", ToolPreview.body(new JSONObject(), "{\"comm"));
    }
}
