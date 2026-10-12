package com.agentui.app;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded process cache of raw responses: readers decode their own mutable models. */
final class ResourceCache {
    private static final int LIMIT = 64;
    private static final class Entry {
        final String body;
        final long time;
        Entry(String body, long time) { this.body = body; this.time = time; }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String identity;
    private long version;
    synchronized long version(String identity) { select(identity); return version; }
    synchronized void putIfCurrent(String identity, String resource, String body, long now, long expectedVersion) {
        if (!identity.equals(this.identity) || version != expectedVersion) return;
        put(identity, resource, body, now);
    }
    synchronized void put(String identity, String resource, String body, long now) {
        select(identity);
        entries.remove(resource);
        entries.put(resource, new Entry(body, now));
        if (entries.size() > LIMIT) entries.remove(entries.keySet().iterator().next());
    }
    synchronized String get(String identity, String resource, long now, long maxAge) {
        select(identity);
        Entry entry = entries.get(resource);
        return entry == null || now - entry.time > maxAge ? null : entry.body;
    }
    synchronized void invalidate(String identity, String prefix) {
        select(identity);
        entries.keySet().removeIf(key -> key.startsWith(prefix));
        version++; // Reject any pre-mutation response still in flight.
    }
    private void select(String identity) {
        if (!identity.equals(this.identity)) {
            entries.clear();
            this.identity = identity;
            version++;
        }
    }
}
