package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InterAgentTest {
    private static final InterAgent.Names NAMES = id -> "42".equals(id) ? "api refactor" : null;

    private static InterAgent.Source source(String json) throws Exception {
        return InterAgent.source(new JSONObject(json));
    }

    @Test public void missingSourceIsTheUser() throws Exception {
        assertTrue(source("{type:'input',text:'hi'}").isUser());
        assertTrue(source("{type:'input',text:'hi',source:{type:'user'}}").isUser());
    }

    @Test public void agentSourceCarriesTheSendersId() throws Exception {
        InterAgent.Source s = source("{text:'hi',source:{type:'agent',session_id:42}}");
        assertEquals(InterAgent.Source.Kind.AGENT, s.kind);
        assertEquals("42", s.sessionId);
    }

    @Test public void malformedSourcesAreUnknownNeverTheUser() throws Exception {
        String[] values = {
                "{source:null}",
                "{source:'agent'}",
                "{source:{type:'robot'}}",
                "{source:{}}",
                "{source:{type:'agent'}}",
                "{source:{type:'agent',session_id:0}}",
                "{source:{type:'agent',session_id:-3}}",
                "{source:{type:'agent',session_id:'42'}}",
                "{source:{type:'agent',session_id:4.5}}",
        };
        for (String value : values) {
            assertEquals(value, InterAgent.Source.Kind.UNKNOWN, source(value).kind);
        }
    }

    @Test public void sessionToolsMatchOnSuffix() {
        assertEquals("message_session", InterAgent.sessionTool("mcp__agent_ui__message_session"));
        assertEquals("start_session", InterAgent.sessionTool("start_session"));
        assertEquals("read_session", InterAgent.sessionTool("mcp__agent_ui__read_session"));
        assertNull(InterAgent.sessionTool("Deploy"));
        assertNull(InterAgent.sessionTool("unread_session"));
    }

    @Test public void titlesDropThePrefixAndUnderscores() {
        assertEquals("MESSAGE SESSION", InterAgent.title("message_session"));
    }

    @Test public void messageSummaryNamesTheTarget() throws Exception {
        InterAgent.Summary s = InterAgent.summary("message_session",
                new JSONObject("{session_id:42,message:'hi'}"), NAMES);
        assertEquals("→ api refactor #42", s.text());
        assertFalse(s.unknownTarget);
    }

    @Test public void unknownTargetIsFlagged() throws Exception {
        InterAgent.Summary s = InterAgent.summary("message_session",
                new JSONObject("{session_id:7,message:'hi'}"), NAMES);
        assertEquals("→ #7 (unknown session)", s.text());
        assertEquals("#7 (unknown session)", s.target);
        assertTrue(s.unknownTarget);
    }

    @Test public void targetIsBareIdWhileNamesLoad() throws Exception {
        InterAgent.Summary s = InterAgent.summary("message_session",
                new JSONObject("{session_id:42,message:'hi'}"), id -> "");
        assertEquals("→ #42", s.text());
        assertFalse(s.unknownTarget);
    }

    @Test public void startSummaryOmitsMissingOptionalsButNotSandbox() throws Exception {
        assertEquals("\"db migration\" in /home/me/proj   (sandboxed)",
                InterAgent.summary("start_session",
                        new JSONObject("{name:'db migration',project_path:'/home/me/proj',message:'go'}"),
                        NAMES).text());
        assertEquals("\"db migration\" in /p   (claude-code · worktree #7 · unsandboxed)",
                InterAgent.summary("start_session",
                        new JSONObject("{name:'db migration',project_path:'/p',message:'go',"
                                + "agent:'claude-code',worktree_id:7,sandbox:false}"),
                        NAMES).text());
    }

    @Test public void readSummaryAlwaysShowsLimit() throws Exception {
        assertEquals("api refactor #42   (after 1830 · limit 200)",
                InterAgent.summary("read_session",
                        new JSONObject("{session_id:42,after:1830,limit:200}"), NAMES).text());
        assertEquals("api refactor #42   (limit 200)",
                InterAgent.summary("read_session", new JSONObject("{session_id:42}"), NAMES).text());
    }

    @Test public void onlyMessagingToolsHaveABodyPreview() throws Exception {
        JSONObject args = new JSONObject("{session_id:42,message:'hello'}");
        assertEquals("hello", InterAgent.message("message_session", args));
        assertEquals("", InterAgent.message("read_session", args));
    }
}
