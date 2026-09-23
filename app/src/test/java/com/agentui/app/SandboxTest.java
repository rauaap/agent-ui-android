package com.agentui.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SandboxTest {
    private Session session(String extra) throws Exception {
        return Session.from(new JSONObject("{\"id\":7,\"agent\":\"pi\",\"status\":\"idle\"" + extra + "}"));
    }

    @Test public void absentIsUnknownAndFalseSurvivesParsing() throws Exception {
        assertNull(session("").sandbox);
        assertEquals(Boolean.FALSE, session(",\"sandbox\":false").sandbox);
        assertEquals(Boolean.TRUE, session(",\"sandbox\":true").sandbox);
    }

    @Test public void liveSettingsWinOverInflightRefresh() throws Exception {
        SessionState state = new SessionState();
        long started = SessionState.snapshot();
        state.settings(new JSONObject("{\"sandbox\":false,\"auto_approve_write\":true,\"auto_approve_command\":false}"));
        state.accept(session(",\"sandbox\":true"), started);
        assertEquals(Boolean.FALSE, state.session.sandbox);
        assertTrue(state.session.autoApproveWrite);
        assertFalse(state.session.autoApproveCommand);
        state.accept(session(",\"sandbox\":true"), SessionState.snapshot());
        assertEquals(Boolean.TRUE, state.session.sandbox);
    }

    @Test public void interAgentAutoApproveParsesAndFollowsLiveSettings() throws Exception {
        assertFalse(session("").autoApproveInterAgent);
        assertTrue(session(",\"auto_approve_inter_agent_communication\":true").autoApproveInterAgent);
        SessionState state = new SessionState();
        long started = SessionState.snapshot();
        state.settings(new JSONObject("{\"auto_approve_inter_agent_communication\":true}"));
        state.accept(session(""), started);
        assertTrue(state.session.autoApproveInterAgent);
        // An event from a server without the field leaves the value alone.
        state.settings(new JSONObject("{\"auto_approve_write\":true}"));
        assertTrue(state.session.autoApproveInterAgent);
    }

    @Test public void patchResponseAlsoProtectsAgainstOlderRefresh() throws Exception {
        SessionState state = new SessionState();
        long started = SessionState.snapshot();
        state.confirmed(session(",\"sandbox\":false"), started);
        state.accept(session(",\"sandbox\":true"), started);
        assertEquals(Boolean.FALSE, state.session.sandbox);
    }

    @Test public void onlyPiAndClaudeCodeSupportSandbox() {
        assertTrue(Agent.supportsSandbox("pi"));
        assertTrue(Agent.supportsSandbox("claude-code"));
        assertFalse(Agent.supportsSandbox("opencode"));
        assertFalse(Agent.supportsSandbox(null));
    }

    @Test public void controlRequiresConfirmedConnectedIdleSupportedAgent() throws Exception {
        for (String agent : new String[]{"pi", "claude-code"}) checkControlGuards(agent);
    }

    private void checkControlGuards(String agent) throws Exception {
        SessionState state = new SessionState();
        state.connection(true);
        assertFalse(state.canSaveSandbox());
        state.accept(Session.from(new JSONObject().put("id", 7).put("agent", agent)
                .put("sandbox", true)), SessionState.snapshot());
        assertTrue(state.canSaveSandbox());
        for (String status : new String[]{"running", "awaiting_approval", "awaiting_answers"}) {
            state.status(status);
            assertFalse(state.canSaveSandbox());
        }
        state.status("idle");
        state.saving = true;
        assertFalse(state.canSaveSandbox());
        state.saving = false;
        state.approvalSaving = true;
        assertFalse(state.canSaveSandbox());
        state.approvalSaving = false;
        state.connection(false);
        assertFalse(state.canSaveSandbox());
        state.connection(true);
        assertFalse(state.canSaveSandbox());
        state.accept(Session.from(new JSONObject("{\"id\":7,\"agent\":\"opencode\",\"sandbox\":true}")), SessionState.snapshot());
        assertFalse(state.canSaveSandbox());
        assertEquals(Boolean.TRUE, state.session.sandbox);
        state.accept(Session.from(new JSONObject().put("id", 7).put("agent", agent)), SessionState.snapshot());
        assertFalse(state.canSaveSandbox());
        assertNull(state.session.sandbox);
        state.accept(Session.from(new JSONObject().put("id", 7).put("agent", agent)
                .put("sandbox", false)), SessionState.snapshot());
        assertTrue(state.canSaveSandbox());
        assertEquals(Boolean.FALSE, state.session.sandbox);
    }
}
