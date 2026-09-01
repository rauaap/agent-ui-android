package com.agentui.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the worktree path template and the path arithmetic under it.
 *
 * <p>Two things matter here. The branch seed the user never edits must be
 * something {@code git check-ref-format} accepts — the server is the authority
 * on whatever they type instead. And an expanded path must match the one the
 * server stores for the same input, or the client's own comparisons against
 * {@code GET /worktrees} miss and every duplicate becomes a 409 it cannot
 * recover from.
 */
public class WorktreePathTest {

    /* ---- branch seeds ---- */

    @Test
    public void slugLowercasesAndDashes() {
        assertEquals("fix-login", WorktreePath.slug("Fix Login"));
        assertEquals("refactor-db", WorktreePath.slug("refactor db"));
    }

    @Test
    public void runsOfPunctuationCollapseToOneDash() {
        assertEquals("fix-login", WorktreePath.slug("fix // login"));
        assertEquals("v2-api", WorktreePath.slug("v2 (api)"));
    }

    @Test
    public void dashesNeverLeadOrTrail() {
        // A leading dash would read as an option to git, and ".." or a trailing
        // dot are ref-format violations — none of them survive slugging.
        assertEquals("wip", WorktreePath.slug("  ...wip!!  "));
        assertEquals("a-b", WorktreePath.slug("-a..b-"));
    }

    @Test
    public void aNameWithNothingToKeepFallsBackToAWord() {
        assertEquals("session", WorktreePath.slug("!!!"));
        assertEquals("session", WorktreePath.slug(""));
        assertEquals("session", WorktreePath.slug(null));
        assertEquals("session", WorktreePath.slug("日本語"));
    }

    @Test
    public void longNamesAreCappedWithoutATrailingDash() {
        // The cap lands mid-word, which is fine; what must not happen is it
        // landing on a dash, which git would reject as a ref name.
        String slug = WorktreePath.slug("aaaaaaaaaa bbbbbbbbbb cccccccccc "
                + "dddddddddd eeeeeeeeee ffffffffff gggggggggg");
        assertEquals(48, slug.length());
        assertEquals("aaaaaaaaaa-bbbbbbbbbb-cccccccccc-dddddddddd-eeee", slug);
    }

    /* ---- template expansion ---- */

    @Test
    public void theDefaultIsASiblingOfTheProjectDirectory() {
        assertEquals("/projects/app-fix-login",
                WorktreePath.expand(WorktreePath.DEFAULT_TEMPLATE, "/projects/app", "fix-login"));
    }

    @Test
    public void specifiersSplitAtTheProjectsParent() {
        // %P and %N compose: together they rebuild the project directory, which
        // is what lets a template nest the worktree under it.
        assertEquals("/projects/app",
                WorktreePath.expand("%P/%N", "/projects/app", "fix-login"));
        assertEquals("/projects/worktrees/app-fix-login",
                WorktreePath.expand("%P/worktrees/%N-%B", "/projects/app", "fix-login"));
    }

    @Test
    public void nIsThePathsLastSegmentNotAnyDisplayName() {
        // The display name defaults to this segment but can be anything —
        // "My App", spaces and all — so the path never goes near it.
        assertEquals("app", WorktreePath.basename("/projects/app"));
        assertEquals("/projects", WorktreePath.dirname("/projects/app"));
    }

    @Test
    public void slashesInABranchNestUnderBOnlyWhenAskedFor() {
        // %B is the spelling people reach for, so it is the safe one: a sibling
        // directory. %b is for deliberately nested layouts and keeps the slash.
        assertEquals("/projects/app-feature-fix-login",
                WorktreePath.expand("%P/%N-%B", "/projects/app", "feature/fix-login"));
        assertEquals("/projects/app-feature/fix-login",
                WorktreePath.expand("%P/%N-%b", "/projects/app", "feature/fix-login"));
    }

    @Test
    public void aProjectDirectlyUnderTheRootDoesNotDoubleTheSlash() {
        // %P is "/" here, so a naive concatenation would give "//app-fix". The
        // server collapses that; our own comparisons against it would not.
        assertEquals("/app-fix", WorktreePath.expand("%P/%N-%B", "/app", "fix"));
    }

    @Test
    public void anEmptyOrMissingTemplateFallsBackToTheDefault() {
        assertEquals("/projects/app-fix", WorktreePath.expand("", "/projects/app", "fix"));
        assertEquals("/projects/app-fix", WorktreePath.expand(null, "/projects/app", "fix"));
    }

    @Test
    public void anUnknownSpecifierStaysVisibleRatherThanVanishing() {
        // A typo shows up in the previewed path instead of silently shortening it.
        assertEquals("/projects/%x-fix", WorktreePath.expand("%P/%x-%B", "/projects/app", "fix"));
        assertEquals("/projects/100%", WorktreePath.expand("%P/100%%", "/projects/app", "fix"));
    }

    /* ---- normalising and resolving ---- */

    @Test
    public void normalisingMatchesTheServersLexicalRules() {
        assertEquals("/projects/app-fix", WorktreePath.normalize("/projects/app/../app-fix"));
        assertEquals("/projects/app", WorktreePath.normalize("/projects//app/"));
        assertEquals("/projects/app", WorktreePath.normalize("/projects/./app"));
        assertEquals("/", WorktreePath.normalize("/"));
        // Nothing above an absolute root to climb to.
        assertEquals("/app", WorktreePath.normalize("/../app"));
    }

    @Test
    public void aRelativePathResolvesAgainstTheProjectDirectory() {
        // The server takes absolute paths only and has no notion of "relative
        // to the project", so the form resolves one before sending it.
        assertEquals("/projects/app-fix",
                WorktreePath.resolve("../app-fix", "/projects/app"));
        assertEquals("/projects/app/wt",
                WorktreePath.resolve("wt", "/projects/app"));
        assertEquals("/elsewhere/wt",
                WorktreePath.resolve("/elsewhere/wt", "/projects/app"));
    }
}
