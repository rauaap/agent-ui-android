package com.agentui.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Activity-owned cards: reuse by stable ID, bind fresh data, and forget removed rows. */
final class KeyedCards<T, C> {
    private Map<String, C> cards = new LinkedHashMap<>();

    List<C> bind(List<T> items, Function<T, String> id,
                 Function<T, C> create, BiConsumer<C, T> bind) {
        Map<String, C> next = new LinkedHashMap<>();
        List<C> ordered = new ArrayList<>();
        for (T item : items) {
            String key = id.apply(item);
            if (next.containsKey(key)) throw new IllegalArgumentException("Duplicate card ID: " + key);
            C card = cards.get(key);
            if (card == null) card = create.apply(item);
            bind.accept(card, item);
            next.put(key, card);
            ordered.add(card);
        }
        cards = next;
        return ordered;
    }

    void clear() { cards.clear(); }
}
