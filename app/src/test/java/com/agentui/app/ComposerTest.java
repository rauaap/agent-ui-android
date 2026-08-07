package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the {@code !} split. Mirrors the desktop client's
 * parseComposerInput tests, so the two front ends cannot drift on what a
 * leading exclamation mark means.
 */
public class ComposerTest {

    @Test
    public void leadingBangIsACommand() {
        Composer c = Composer.parse("!df -h");
        assertTrue(c.bash);
        assertEquals("df -h", c.text);
    }

    @Test
    public void theBangMayBeFollowedByASpace() {
        assertEquals("ls -la", Composer.parse("  !  ls -la  ").text);
    }

    @Test
    public void bareBangIsBashModeWithNothingToRun() {
        Composer c = Composer.parse("!");
        assertTrue(c.bash);
        assertEquals("", c.text);
    }

    @Test
    public void backslashEscapesToAPromptStartingWithBang() {
        Composer c = Composer.parse("\\!important, read this");
        assertFalse(c.bash);
        assertEquals("!important, read this", c.text);
    }

    @Test
    public void theEscapeIsPositionalNotGlobal() {
        // Only the first character is special, so a backslash anywhere else —
        // and a `!` anywhere else — travels verbatim.
        assertEquals("grep -r \"\\!\" .", Composer.parse("grep -r \"\\!\" .").text);
        assertEquals("wow! ok", Composer.parse("wow! ok").text);
    }

    @Test
    public void nothingTypedIsNothingToSend() {
        assertNull(Composer.parse("   "));
        assertNull(Composer.parse(""));
        assertNull(Composer.parse(null));
    }

    @Test
    public void isBashDrivesTheComposerStyling() {
        assertTrue(Composer.isBash("!"));
        assertTrue(Composer.isBash("!ls"));
        assertFalse(Composer.isBash("\\!ls"));
        assertFalse(Composer.isBash("ls"));
        assertFalse(Composer.isBash(""));
    }
}
