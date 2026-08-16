package com.agentui.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the worktree path/branch seeds. The server validates the
 * branch with {@code git check-ref-format}, so what matters here is that the
 * seed the user never edits is always something git accepts.
 */
public class WorktreeTest {

    @Test
    public void slugLowercasesAndDashes() {
        assertEquals("fix-login", Worktree.slug("Fix Login"));
        assertEquals("refactor-db", Worktree.slug("refactor db"));
    }

    @Test
    public void runsOfPunctuationCollapseToOneDash() {
        assertEquals("fix-login", Worktree.slug("fix // login"));
        assertEquals("v2-api", Worktree.slug("v2 (api)"));
    }

    @Test
    public void dashesNeverLeadOrTrail() {
        // A leading dash would read as an option to git, and ".." or a trailing
        // dot are ref-format violations — none of them survive slugging.
        assertEquals("wip", Worktree.slug("  ...wip!!  "));
        assertEquals("a-b", Worktree.slug("-a..b-"));
    }

    @Test
    public void aNameWithNothingToKeepFallsBackToAWord() {
        assertEquals("session", Worktree.slug("!!!"));
        assertEquals("session", Worktree.slug(""));
        assertEquals("session", Worktree.slug(null));
        assertEquals("session", Worktree.slug("日本語"));
    }

    @Test
    public void longNamesAreCappedWithoutATrailingDash() {
        // The cap lands mid-word, which is fine; what must not happen is it
        // landing on a dash, which git would reject as a ref name.
        String slug = Worktree.slug("aaaaaaaaaa bbbbbbbbbb cccccccccc "
                + "dddddddddd eeeeeeeeee ffffffffff gggggggggg");
        assertEquals(48, slug.length());
        assertEquals("aaaaaaaaaa-bbbbbbbbbb-cccccccccc-dddddddddd-eeee", slug);
    }

    @Test
    public void pathIsASiblingOfTheProjectDirectory() {
        assertEquals("/home/me/app-fix-login", Worktree.pathFor("/home/me/app", "fix login"));
    }

    @Test
    public void trailingSlashesOnTheProjectDoNotDoubleUp() {
        assertEquals("/home/me/app-wip", Worktree.pathFor("/home/me/app//", "wip"));
        assertEquals("/home/me/app-wip", Worktree.pathFor("  /home/me/app/  ", "wip"));
    }

    @Test
    public void aRootProjectHasNoSegmentToExtend() {
        assertEquals("/wip", Worktree.pathFor("/", "wip"));
        assertEquals("/wip", Worktree.pathFor("", "wip"));
        assertEquals("/wip", Worktree.pathFor(null, "wip"));
    }
}
