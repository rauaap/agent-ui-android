package com.agentui.app;

import java.util.function.IntFunction;

/**
 * Pairs an approval with the tool card of the same invocation. Tool cards are
 * tagged with their call id, and the transcript itself is the index: a card
 * that leaves the transcript (a reset, a reconnect replay) can't be matched,
 * so there is nothing to go stale.
 */
final class ToolCards {
    private ToolCards() {}

    /** The view tag marking a transcript row as the tool card of one call. */
    static final class Tag {
        final String callId;

        Tag(String callId) {
            this.callId = callId;
        }
    }

    /**
     * The index of the tool card for {@code callId} among {@code count} rows
     * whose tags {@code tagAt} returns, or -1. A call id names one invocation,
     * so a match anywhere counts; the scan runs bottom-up because that's where
     * it almost always is. A missing call id never matches.
     */
    static int indexOf(IntFunction<Object> tagAt, int count, String callId) {
        if (callId == null || callId.isEmpty()) return -1;
        for (int i = count - 1; i >= 0; i--) {
            Object tag = tagAt.apply(i);
            if (tag instanceof Tag && callId.equals(((Tag) tag).callId)) return i;
        }
        return -1;
    }
}
