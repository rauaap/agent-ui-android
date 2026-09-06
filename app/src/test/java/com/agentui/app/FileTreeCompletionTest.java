package com.agentui.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileTreeCompletionTest {
    private static FileTreeCompletion tree(String... paths) throws Exception {
        FileTreeCompletion tree = new FileTreeCompletion();
        List<String> sorted = new ArrayList<>(Arrays.asList(paths));
        java.util.Collections.sort(sorted);
        tree.applySnapshot("generation-a", 0, sorted);
        return tree;
    }

    private static List<String> matches(FileTreeCompletion tree, String query) {
        List<String> result = new ArrayList<>();
        for (FileTreeCompletion.Candidate candidate : tree.match(query, 50)) {
            result.add(candidate.path);
        }
        return result;
    }

    @Test
    public void snapshotReplacesAndPatchAdvancesAuthoritativeState() throws Exception {
        FileTreeCompletion tree = tree("README.md", "src/", "src/Main.java");
        assertEquals(3, tree.size());
        tree.applyPatch("generation-a", 0, 1,
                Arrays.asList("docs/", "docs/design.md"),
                Arrays.asList("README.md"));
        assertEquals(1, tree.revision());
        assertEquals(Arrays.asList("docs/", "docs/design.md"), matches(tree, "docs"));

        tree.applySnapshot("generation-b", 0, Arrays.asList("only.txt"));
        assertEquals(1, tree.size());
        assertEquals(Arrays.asList("only.txt"), matches(tree, "only"));
    }

    @Test
    public void rejectsGenerationAndRevisionMismatchWithoutApplyingPatch() throws Exception {
        FileTreeCompletion tree = tree("old.txt");
        try {
            tree.applyPatch("other", 0, 1, Arrays.asList("new.txt"), new ArrayList<>());
            fail("generation mismatch accepted");
        } catch (FileTreeCompletion.ProtocolException expected) {}
        try {
            tree.applyPatch("generation-a", 4, 5, Arrays.asList("new.txt"), new ArrayList<>());
            fail("revision mismatch accepted");
        } catch (FileTreeCompletion.ProtocolException expected) {}
        assertEquals(Arrays.asList("old.txt"), matches(tree, "old"));
        assertTrue(matches(tree, "new").isEmpty());
    }

    @Test
    public void matchesOnlyCaseInsensitiveComponentBoundaries() throws Exception {
        FileTreeCompletion tree = tree(
                "SRC/Utils/Clean.py", "scripts/utils.py", "src/futile.py",
                "src/futile/experiment.py", "util.py");
        assertEquals(Arrays.asList("util.py", "SRC/Utils/Clean.py", "scripts/utils.py"),
                matches(tree, "UTIL"));
    }

    @Test
    public void matchesLiteralMultipleComponentsAndDirectoryDescendants() throws Exception {
        FileTreeCompletion tree = tree(
                "my_scripts/display.py", "scripts/display/", "scripts/display/icon.svg",
                "scripts/display_setup.py", "scripts/my_display.py", "tools/scripts/display.py");
        assertEquals(Arrays.asList(
                        "scripts/display/", "scripts/display/icon.svg",
                        "scripts/display_setup.py", "tools/scripts/display.py"),
                matches(tree, "scripts/display"));
    }

    @Test
    public void exactAndPrefixRankingAndDisplayLimitAreDeterministic() throws Exception {
        List<String> paths = new ArrayList<>();
        paths.add("foo/");
        paths.add("foo-long.txt");
        paths.add("deep/foo");
        for (int i = 0; i < 80; i++) paths.add(String.format("z%02d/foo.txt", i));
        FileTreeCompletion tree = tree(paths.toArray(new String[0]));
        List<FileTreeCompletion.Candidate> result = tree.match("foo", 50);
        assertEquals(50, result.size());
        assertEquals("foo/", result.get(0).path);
        assertEquals("foo-long.txt", result.get(1).path);
        assertEquals("z00/foo.txt", result.get(2).path);
        assertTrue(result.get(0).directory);
        assertFalse(result.get(1).directory);
    }

    @Test
    public void tokenUsesShellBoundariesAndPreservesSurroundingCommand() {
        String command = "!cat src/uti | grep x";
        FileTreeCompletion.Token token = FileTreeCompletion.tokenAtCursor(command, 12);
        assertEquals("src/uti", token.query);
        assertEquals("!cat src/utils.py | grep x", token.replace(command, "src/utils.py"));
        assertEquals(17, token.cursorAfter("src/utils.py"));
    }

    @Test
    public void tokenDecodesIncompleteQuotesAndBackslashEscapes() {
        String quoted = "!cat 'my dir/fi";
        FileTreeCompletion.Token single = FileTreeCompletion.tokenAtCursor(quoted, quoted.length());
        assertEquals("my dir/fi", single.query);
        assertEquals("!cat 'my dir/file.txt'", single.replace(quoted, "my dir/file.txt"));

        String escaped = "!cp some\\ file /tmp";
        FileTreeCompletion.Token backslash = FileTreeCompletion.tokenAtCursor(escaped, 14);
        assertEquals("some file", backslash.query);
        assertEquals("!cp 'some file.txt' /tmp", backslash.replace(escaped, "some file.txt"));

        String doubleQuoted = "!cat \"a\\q file";
        FileTreeCompletion.Token doubleQuote = FileTreeCompletion.tokenAtCursor(
                doubleQuoted, doubleQuoted.length());
        assertEquals("a\\q file", doubleQuote.query);

        // The cursor can sit between an escape and the space it protects; the
        // replacement still consumes the complete shell token to its right.
        String insideEscape = "!cat some\\ file.txt tail";
        FileTreeCompletion.Token middle = FileTreeCompletion.tokenAtCursor(insideEscape, 10);
        assertEquals("some\\", middle.query);
        assertEquals("!cat replacement.txt tail",
                middle.replace(insideEscape, "replacement.txt"));

        assertNull(FileTreeCompletion.tokenAtCursor("tell the agent", 5));
    }

    @Test
    public void shellEscapingCoversQuotesGlobsOperatorsAndDirectories() {
        assertEquals("src/main/", FileTreeCompletion.shellEscape("src/main/"));
        assertEquals("'a b'", FileTreeCompletion.shellEscape("a b"));
        assertEquals("'a'\\''b'", FileTreeCompletion.shellEscape("a'b"));
        assertEquals("'*.java'", FileTreeCompletion.shellEscape("*.java"));
        assertEquals("'left|right'", FileTreeCompletion.shellEscape("left|right"));
        assertEquals("'$(bad)'", FileTreeCompletion.shellEscape("$(bad)"));
    }

    @Test
    public void clearingDisablesAllStaleMatches() throws Exception {
        FileTreeCompletion tree = tree("src/Main.java");
        tree.clear();
        assertFalse(tree.isReady());
        assertTrue(matches(tree, "src").isEmpty());
    }
}
