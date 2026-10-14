package com.agentui.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class KeyedCardsTest {
    private static final class Item {
        final String id, label;
        Item(String id, String label) { this.id = id; this.label = label; }
    }
    private static final class Card {
        Item current;
        int binds;
        void bind(Item item) { current = item; binds++; }
        String clickLabel() { return current.label; }
    }
    private final KeyedCards<Item, Card> cards = new KeyedCards<>();
    private int created;

    private List<Card> bind(Item... items) {
        return cards.bind(Arrays.asList(items), item -> item.id,
                item -> { created++; return new Card(); }, Card::bind);
    }

    @Test public void refreshRetainsIdentityAndBindsLatestActionData() {
        Card first = bind(new Item("a", "old"), new Item("b", "second")).get(0);
        Item fresh = new Item("a", "new");
        List<Card> updated = bind(fresh, new Item("b", "second"));
        assertSame(first, updated.get(0));
        assertSame(fresh, first.current);
        assertEquals("new", first.clickLabel());
        assertEquals(2, first.binds);
        assertEquals(2, created);
    }

    @Test public void reorderingAndInsertionReuseSurvivingCards() {
        List<Card> old = bind(new Item("a", "A"), new Item("b", "B"));
        List<Card> updated = bind(new Item("b", "B"), new Item("c", "C"), new Item("a", "A"));
        assertSame(old.get(1), updated.get(0));
        assertSame(old.get(0), updated.get(2));
        assertEquals(3, created);
    }

    @Test public void removedRowsAreForgottenAndReaddedWithFreshCards() {
        Card old = bind(new Item("a", "A")).get(0);
        assertEquals(Collections.emptyList(), bind());
        assertNotSame(old, bind(new Item("a", "A")).get(0));
    }

    @Test public void identityResetCannotReuseAnotherServersCard() {
        Card old = bind(new Item("a", "A")).get(0);
        cards.clear();
        assertNotSame(old, bind(new Item("a", "other server")).get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateIdsAreRejected() {
        bind(new Item("a", "A"), new Item("a", "duplicate"));
    }
}
