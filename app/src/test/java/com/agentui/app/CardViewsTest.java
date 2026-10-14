package com.agentui.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class CardViewsTest {
    private static final class Container implements CardViews.Rows<Object> {
        final List<Object> children = new ArrayList<>();
        int removals, additions;
        Container(Object... rows) { children.addAll(Arrays.asList(rows)); }
        public int size() { return children.size(); }
        public Object get(int i) { return children.get(i); }
        public void remove(int i) { removals++; children.remove(i); }
        public void add(int i, Object row) {
            assertFalse("Cannot attach an already attached row", children.contains(row));
            additions++;
            children.add(i, row);
        }
    }

    @Test public void ordinaryRefreshDoesNotDetachAnyCardOrHeader() {
        Object header = new Object(), a = new Object(), b = new Object();
        Container rows = new Container(header, a, b);
        CardViews.reconcile(rows, Arrays.asList(header, a, b));
        assertEquals(0, rows.removals);
        assertEquals(0, rows.additions);
    }

    @Test public void insertionDoesNotDetachExistingCards() {
        Object a = new Object(), b = new Object(), added = new Object();
        Container rows = new Container(a, b);
        CardViews.reconcile(rows, Arrays.asList(added, a, b));
        assertEquals(Arrays.asList(added, a, b), rows.children);
        assertEquals(0, rows.removals);
        assertEquals(1, rows.additions);
    }

    @Test public void deletionRemovesOnlyTheDeletedCard() {
        Object a = new Object(), deleted = new Object(), b = new Object();
        Container rows = new Container(a, deleted, b);
        CardViews.reconcile(rows, Arrays.asList(a, b));
        assertEquals(Arrays.asList(a, b), rows.children);
        assertEquals(1, rows.removals);
        assertEquals(0, rows.additions);
    }

    @Test public void reorderMovesOnlyTheNecessaryRow() {
        Object a = new Object(), b = new Object(), c = new Object();
        Container rows = new Container(a, b, c);
        CardViews.reconcile(rows, Arrays.asList(c, a, b));
        assertEquals(Arrays.asList(c, a, b), rows.children);
        assertEquals(1, rows.removals);
        assertEquals(1, rows.additions);
    }

    @Test public void emptyAndErrorRowsAreReplacedWhenContentArrives() {
        Object empty = new Object(), card = new Object();
        Container rows = new Container(empty);
        CardViews.reconcile(rows, Arrays.asList(card));
        assertEquals(Arrays.asList(card), rows.children);
        CardViews.reconcile(rows, Collections.emptyList());
        assertTrue(rows.children.isEmpty());
    }

    @Test public void mixedMembershipAndOrderChangesProduceExactlyTheRequestedRows() {
        Object a = new Object(), b = new Object(), c = new Object(), d = new Object();
        Container rows = new Container(a, b, c);
        CardViews.reconcile(rows, Arrays.asList(c, d, a));
        assertEquals(Arrays.asList(c, d, a), rows.children);
    }
}
