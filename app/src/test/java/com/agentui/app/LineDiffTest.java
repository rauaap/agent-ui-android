package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the pure line-diff algorithm. Runs on the plain JVM (no
 * Android), so it exercises {@link LineDiff} directly without the rendering.
 */
public class LineDiffTest {

    /** Render rows as gutter-prefixed lines, e.g. "  A", "- X", "+ X1". */
    private static String render(List<LineDiff.Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (LineDiff.Row r : rows) {
            char g = r.kind == LineDiff.Kind.ADD ? '+'
                    : r.kind == LineDiff.Kind.DELETE ? '-' : ' ';
            if (sb.length() > 0) sb.append('\n');
            sb.append(g).append(' ').append(r.text);
        }
        return sb.toString();
    }

    private static String diff(String oldS, String newS) {
        return render(LineDiff.diff(oldS, newS));
    }

    @Test
    public void unchangedIsAllContext() {
        assertEquals("  a\n  b", diff("a\nb", "a\nb"));
    }

    @Test
    public void insertionKeepsSurroundingContext() {
        assertEquals("  a\n+ b\n  c", diff("a\nc", "a\nb\nc"));
    }

    @Test
    public void deletionKeepsSurroundingContext() {
        assertEquals("  a\n- b\n  c", diff("a\nb\nc", "a\nc"));
    }

    @Test
    public void interiorUnchangedLineStaysContext() {
        // The whole point of the LCS: B is unchanged and must not show as -B/+B.
        assertEquals(
                "  A\n- X\n+ X1\n  B\n- Y\n+ Y1\n  C",
                diff("A\nX\nB\nY\nC", "A\nX1\nB\nY1\nC"));
    }

    @Test
    public void replacementGroupsDeletesBeforeInserts() {
        assertEquals("- a\n- b\n+ c\n+ d", diff("a\nb", "c\nd"));
    }

    @Test
    public void writeWithEmptyOldIsAllAdditions() {
        assertEquals("+ x\n+ y\n+ z", diff("", "x\ny\nz"));
    }

    @Test
    public void emptyNewIsAllDeletions() {
        assertEquals("- x\n- y", diff("x\ny", ""));
    }

    @Test
    public void emptyBothProducesNoRows() {
        // A degenerate edit with neither side present yields nothing, so the
        // caller falls back to raw JSON rather than rendering an empty "+" line.
        assertTrue(LineDiff.diff("", "").isEmpty());
    }

    @Test
    public void oversizeChangeFallsBackToBlockDiff() {
        // Above the 500-line cap the alignment is skipped: every old line is a
        // delete, then every new line an add (no interior matching).
        int n = LineDiff.MAX_DIFF_LINES + 100;
        StringBuilder oldS = new StringBuilder();
        StringBuilder newS = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) { oldS.append('\n'); newS.append('\n'); }
            oldS.append("o").append(i);
            newS.append("n").append(i);
        }
        List<LineDiff.Row> rows = LineDiff.diff(oldS.toString(), newS.toString());
        assertEquals(2 * n, rows.size());
        assertEquals(LineDiff.Kind.DELETE, rows.get(0).kind);
        assertEquals("o0", rows.get(0).text);
        assertEquals(LineDiff.Kind.DELETE, rows.get(n - 1).kind);
        assertEquals(LineDiff.Kind.ADD, rows.get(n).kind);
        assertEquals("n0", rows.get(n).text);
    }

    @Test
    public void belowCapStillAlignsInterior() {
        // Just under the cap, a shared middle line is still recognised as context.
        int n = LineDiff.MAX_DIFF_LINES;
        StringBuilder oldS = new StringBuilder();
        StringBuilder newS = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) { oldS.append('\n'); newS.append('\n'); }
            String shared = "x" + i;
            oldS.append(shared);
            newS.append(i == n / 2 ? shared : "y" + i);
        }
        String out = diff(oldS.toString(), newS.toString());
        assertTrue("shared middle line should appear as context",
                out.contains("  x" + (n / 2)));
    }
}
