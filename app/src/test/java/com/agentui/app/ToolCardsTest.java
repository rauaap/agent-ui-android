package com.agentui.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The transcript is modelled as its rows' labels, with a tool card's tag
 * alongside, and approvals are placed the way SessionActivity places them:
 * over the matching tool card, or appended when there is none.
 */
public class ToolCardsTest {
    private final List<String> rows = new ArrayList<>();
    private final List<Object> tags = new ArrayList<>();

    private void tool(String callId) {
        rows.add("tool " + callId);
        tags.add(callId == null ? null : new ToolCards.Tag(callId));
    }

    private void output(String text) {
        rows.add("output " + text);
        tags.add(null);
    }

    private void approval(String callId, boolean auto) {
        int index = ToolCards.indexOf(tags::get, tags.size(), callId);
        String row = (auto ? "auto " : "approval ") + callId;
        if (index < 0) {
            rows.add(row);
            tags.add(null);
        } else {
            rows.set(index, row);
            tags.set(index, null);
        }
    }

    private void assertRows(String... expected) {
        assertEquals(Arrays.asList(expected), rows);
    }

    @Test public void interleavedParallelApprovalsEachReplaceTheirCard() {
        tool("api");
        approval("api", false);
        tool("desktop");
        tool("android");
        approval("desktop", false);
        approval("android", false);
        assertRows("approval api", "approval desktop", "approval android");
    }

    @Test public void autoApprovedParallelCallsReplaceTheirCards() {
        tool("a");
        tool("b");
        tool("c");
        approval("a", true);
        approval("b", true);
        approval("c", true);
        assertRows("auto a", "auto b", "auto c");
    }

    @Test public void outputBetweenCallAndApprovalStillMerges() {
        tool("a");
        output("thinking");
        approval("a", false);
        assertRows("approval a", "output thinking");
    }

    @Test public void approvalWithoutMatchingCardIsAppended() {
        tool("a");
        approval("b", false);
        assertRows("tool a", "approval b");
    }

    @Test public void cardWithoutCallIdNeverMatches() {
        tool(null);
        approval(null, false);
        approval("", false);
        assertRows("tool null", "approval null", "approval ");
    }

    @Test public void onlyToolCardTagsMatch() {
        rows.add("other");
        tags.add("a");
        approval("a", false);
        assertRows("other", "approval a");
    }
}
