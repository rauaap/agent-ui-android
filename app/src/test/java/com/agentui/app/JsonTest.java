package com.agentui.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigInteger;

/**
 * Unit tests for id decoding. The server sends ids as JSON numbers and the app
 * holds them as strings; what matters is that the string is one the server
 * would recognise back — an id that gains a ".0" matches nothing, and fails
 * silently when it does.
 *
 * <p>These exercise {@link Json#idOf} rather than {@link Json#id}: org.json is
 * a stub in JVM unit tests, so a real {@code JSONObject} cannot be built here.
 */
public class JsonTest {

    @Test
    public void integersBecomeTheirPlainDigits() {
        // What org.json actually hands back for a small id.
        assertEquals("1", Json.idOf(1));
        assertEquals("42", Json.idOf(42L));
        assertEquals("9007199254740993", Json.idOf(new BigInteger("9007199254740993")));
    }

    @Test
    public void floatingPointIdsDoNotKeepTheDecimalPoint() {
        // The failure this helper exists to prevent: String.valueOf(1.0d) is
        // "1.0", an id no route and no lookup will ever match.
        assertEquals("1", Json.idOf(1.0d));
        assertEquals("7", Json.idOf(7.0f));
    }

    @Test
    public void stringsPassThroughUntouched() {
        // A pre-migration server still sends uuids, and the app has to keep
        // working against one.
        assertEquals("7ce2f0e2-0e0f-4f6b-9a4e-2b1c0a5d9f11",
                Json.idOf("7ce2f0e2-0e0f-4f6b-9a4e-2b1c0a5d9f11"));
        assertEquals("1", Json.idOf("1"));
    }

    @Test
    public void aMissingIdIsEmptyRatherThanTheWordNull() {
        assertEquals("", Json.idOf(null));
    }

    @Test
    public void anIdGoesBackOnTheWireAsANumber() {
        // worktree_id is typed int server-side, so a digits-only id has to
        // leave as a JSON number rather than a quoted string.
        assertEquals(1L, Json.wire("1"));
        assertEquals(9007199254740993L, Json.wire("9007199254740993"));
    }

    @Test
    public void anIdWithNoNumericFormGoesBackAsItself() {
        // A uuid from a pre-migration server has nothing to parse, and passes
        // through as the string it is.
        assertEquals("7ce2f0e2-0e0f-4f6b-9a4e-2b1c0a5d9f11",
                Json.wire("7ce2f0e2-0e0f-4f6b-9a4e-2b1c0a5d9f11"));
    }
}
