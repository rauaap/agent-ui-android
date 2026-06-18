package com.agentui.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-level diff of two text blocks, with interior line matching via a
 * longest-common-subsequence alignment. Pure Java — no Android dependencies —
 * so it can be unit-tested on the plain JVM; {@code ToolFormat} turns the
 * returned rows into a coloured spannable.
 */
final class LineDiff {
    private LineDiff() {}

    /** Whether a row is unchanged context, a removal, or an addition. */
    enum Kind { CONTEXT, DELETE, ADD }

    /** One output line: its role and its text (no gutter prefix). */
    static final class Row {
        final Kind kind;
        final String text;

        Row(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    // Above this many changed lines per side we skip the O(m*n) alignment and
    // fall back to a plain block diff — nobody reads a 500-line diff anyway.
    static final int MAX_DIFF_LINES = 500;

    /** Diff {@code oldS} against {@code newS} by line, in display order. */
    static List<Row> diff(String oldS, String newS) {
        List<Row> rows = new ArrayList<>();

        // Both sides empty: nothing to show. Returning no rows lets the caller
        // fall back to raw JSON instead of painting a lone empty "+" line.
        if (oldS.isEmpty() && newS.isEmpty()) return rows;

        // A created or deleted block has no counterpart to align against, so it
        // is purely added or removed — and this keeps a huge Write off the table.
        if (oldS.isEmpty()) {
            for (String l : newS.split("\n", -1)) rows.add(new Row(Kind.ADD, l));
            return rows;
        }
        if (newS.isEmpty()) {
            for (String l : oldS.split("\n", -1)) rows.add(new Row(Kind.DELETE, l));
            return rows;
        }

        String[] o = oldS.split("\n", -1);
        String[] n = newS.split("\n", -1);

        // Trim common leading/trailing lines into context, shrinking the region
        // the alignment has to chew on to just the part that actually changed.
        int pre = 0;
        while (pre < o.length && pre < n.length && o[pre].equals(n[pre])) pre++;
        int suf = 0;
        while (suf < o.length - pre && suf < n.length - pre
                && o[o.length - 1 - suf].equals(n[n.length - 1 - suf])) suf++;

        for (int i = 0; i < pre; i++) rows.add(new Row(Kind.CONTEXT, o[i]));

        if (o.length - pre - suf > MAX_DIFF_LINES || n.length - pre - suf > MAX_DIFF_LINES) {
            for (int i = pre; i < o.length - suf; i++) rows.add(new Row(Kind.DELETE, o[i]));
            for (int i = pre; i < n.length - suf; i++) rows.add(new Row(Kind.ADD, n[i]));
        } else {
            align(rows, o, n, pre, suf);
        }

        for (int i = n.length - suf; i < n.length; i++) rows.add(new Row(Kind.CONTEXT, n[i]));
        return rows;
    }

    /**
     * Align the changed region {@code [start, len-suf)} of both sides with an
     * LCS, so unchanged interior lines stay context instead of a delete plus an
     * insert. Within each replaced run, deletes are emitted before inserts (git
     * ordering).
     */
    private static void align(List<Row> rows, String[] o, String[] n, int start, int suf) {
        int m = o.length - suf - start; // changed old lines
        int p = n.length - suf - start; // changed new lines

        // dp[i][j] = LCS length of o[start+i..] and n[start+j..] within the region
        int[][] dp = new int[m + 1][p + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = p - 1; j >= 0; j--) {
                dp[i][j] = o[start + i].equals(n[start + j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }

        List<String> dels = new ArrayList<>();
        List<String> inss = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m && j < p) {
            if (o[start + i].equals(n[start + j])) {
                flush(rows, dels, inss);
                rows.add(new Row(Kind.CONTEXT, o[start + i]));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                dels.add(o[start + i++]);
            } else {
                inss.add(n[start + j++]);
            }
        }
        while (i < m) dels.add(o[start + i++]);
        while (j < p) inss.add(n[start + j++]);
        flush(rows, dels, inss);
    }

    /** Emit buffered deletes then inserts (git ordering), and clear them. */
    private static void flush(List<Row> rows, List<String> dels, List<String> inss) {
        for (String d : dels) rows.add(new Row(Kind.DELETE, d));
        for (String s : inss) rows.add(new Row(Kind.ADD, s));
        dels.clear();
        inss.clear();
    }
}
