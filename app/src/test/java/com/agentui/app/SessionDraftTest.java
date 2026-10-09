package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SessionDraftTest {
    private static final class MemoryStore implements SessionDraft.Store {
        final Map<String, String> values = new HashMap<>();
        public String get(String key) { return values.get(key); }
        public void put(String key, String text) { values.put(key, text); }
        public void remove(String key) { values.remove(key); }
    }

    @Test public void reopeningRestoresExactComposerText() {
        MemoryStore store = new MemoryStore();
        for (String text : new String[]{"  prompt\nunfinished  ", "!git status\n", "\\!literal", " \n "}) {
            SessionDraft open = new SessionDraft("http://host:8080", "3", store);
            open.save(text);
            assertEquals(text, new SessionDraft("http://host:8080", "3", store).restore());
        }
    }

    @Test public void draftsAreIsolatedBySessionServerPortAndScheme() {
        MemoryStore store = new MemoryStore();
        new SessionDraft("http://host:8080", "3", store).save("one");
        for (String server : new String[]{"http://other:8080", "http://host:8081", "https://host:8080"}) {
            assertEquals("", new SessionDraft(server, "3", store).restore());
        }
        assertEquals("", new SessionDraft("http://host:8080", "4", store).restore());
        new SessionDraft("http://host:8080", "4", store).save("two");
        assertEquals("one", new SessionDraft("http://host:8080", "3", store).restore());
    }

    @Test public void emptyComposerRemovesDraftAndLaterLifecycleSaveKeepsItCleared() {
        MemoryStore store = new MemoryStore();
        SessionDraft draft = new SessionDraft("http://host:8080", "3", store);
        assertEquals("", draft.restore());
        draft.save("unfinished");
        draft.save(""); // successful send or a composer cleared before exit
        draft.save(""); // subsequent stop/destroy
        assertTrue(store.values.isEmpty());
        assertEquals("", new SessionDraft("http://host:8080", "3", store).restore());
    }

    @Test public void missingSessionDoesNotReadOrWriteASharedDraft() {
        MemoryStore store = new MemoryStore();
        for (String id : new String[]{null, ""}) {
            SessionDraft draft = new SessionDraft("http://host:8080", id, store);
            draft.save("text");
            assertEquals("", draft.restore());
        }
        assertTrue(store.values.isEmpty());
    }

    @Test public void scopeSeparatorsCannotCollide() {
        MemoryStore store = new MemoryStore();
        new SessionDraft("http://host:8080", "3:4", store).save("first");
        assertEquals("", new SessionDraft("http://host:8080:3", "4", store).restore());
    }
}
