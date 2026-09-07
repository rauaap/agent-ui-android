package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MessageHistoryTest {

    @Test
    public void upWalksBackwardAndDownRestoresTheDraft() {
        MessageHistory history = new MessageHistory();
        history.add("first");
        history.add("second");

        assertEquals("second", history.previous("unfinished"));
        assertEquals("first", history.previous("ignored while browsing"));
        assertEquals("first", history.previous(""));
        assertEquals("second", history.next());
        assertEquals("unfinished", history.next());
        assertNull(history.next());
    }

    @Test
    public void typingResetsTraversalAndPreservesTheEditedDraft() {
        MessageHistory history = new MessageHistory();
        history.add("one");
        history.add("two");

        assertEquals("two", history.previous("draft"));
        history.resetNavigation();
        assertEquals("two", history.previous("edited"));
        assertEquals("edited", history.next());
    }

    @Test
    public void promptsAndCommandsRetainComposerSemantics() {
        assertEquals("hello", MessageHistory.promptEntry("hello"));
        assertEquals("\\!literal prompt", MessageHistory.promptEntry("!literal prompt"));
        assertEquals("!git status", MessageHistory.commandEntry("git status"));
    }

    @Test
    public void clearingHistoryAlsoEndsNavigation() {
        MessageHistory history = new MessageHistory();
        history.add("old");
        assertEquals("old", history.previous("draft"));

        history.clear();
        assertNull(history.previous("new draft"));
        assertNull(history.next());
    }
}
