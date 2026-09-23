package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the pure Markdown parser. Runs on the plain JVM (no Android),
 * so it exercises {@link Markdown#parse} — the marker-stripping and span
 * offsets — without the {@link android.text.style} rendering.
 */
public class MarkdownTest {

    private static Markdown.Doc parse(String md) {
        return Markdown.parse(md);
    }

    /** The single span of the given type, asserting there is exactly one. */
    private static Markdown.Span only(Markdown.Doc d, Markdown.Type type) {
        Markdown.Span found = null;
        for (Markdown.Span s : d.spans) {
            if (s.type == type) {
                assertNull("expected a single " + type + " span", found);
                found = s;
            }
        }
        assertTrue("expected a " + type + " span", found != null);
        return found;
    }

    private static String sub(Markdown.Doc d, Markdown.Span s) {
        return d.text.substring(s.start, s.end);
    }

    @Test
    public void plainTextIsUnchanged() {
        Markdown.Doc d = parse("hello world");
        assertEquals("hello world", d.text);
        assertTrue(d.spans.isEmpty());
    }

    @Test
    public void boldStripsMarkersAndSpansContent() {
        Markdown.Doc d = parse("a **big** deal");
        assertEquals("a big deal", d.text);
        assertEquals("big", sub(d, only(d, Markdown.Type.BOLD)));
    }

    @Test
    public void italicWithAsterisk() {
        Markdown.Doc d = parse("an *odd* one");
        assertEquals("an odd one", d.text);
        assertEquals("odd", sub(d, only(d, Markdown.Type.ITALIC)));
    }

    @Test
    public void inlineCodeIsLiteralInside() {
        // Markers inside inline code must not be re-interpreted.
        Markdown.Doc d = parse("run `a*b*c` now");
        assertEquals("run a*b*c now", d.text);
        assertEquals("a*b*c", sub(d, only(d, Markdown.Type.CODE)));
        assertTrue(d.spans.size() == 1);
    }

    @Test
    public void headingStripsHashes() {
        Markdown.Doc d = parse("## Title");
        assertEquals("Title", d.text);
        Markdown.Span h = only(d, Markdown.Type.HEADING);
        assertEquals(2, h.level);
        assertEquals("Title", sub(d, h));
    }

    @Test
    public void bulletGetsGlyphPrefix() {
        Markdown.Doc d = parse("- one\n- two");
        assertEquals("•  one\n•  two", d.text);
    }

    @Test
    public void numberedListKeepsNumber() {
        Markdown.Doc d = parse("1. first\n2. second");
        assertEquals("1. first\n2. second", d.text);
    }

    @Test
    public void linkShowsLabelAndKeepsHref() {
        Markdown.Doc d = parse("see [docs](https://x.dev) here");
        assertEquals("see docs here", d.text);
        Markdown.Span link = only(d, Markdown.Type.LINK);
        assertEquals("docs", sub(d, link));
        assertEquals("https://x.dev", link.href);
    }

    @Test
    public void fencedCodeBlockKeepsContentDropsFences() {
        Markdown.Doc d = parse("```\nint x = 1;\nx++;\n```");
        assertEquals("int x = 1;\nx++;", d.text);
        assertEquals("int x = 1;\nx++;", sub(d, only(d, Markdown.Type.CODE_BLOCK)));
    }

    @Test
    public void longerOuterFenceContainsShorterFencedExample() {
        Markdown.Doc d = parse("````markdown\n```js\nconst x = 1;\n```\n````");
        String expected = "```js\nconst x = 1;\n```";
        assertEquals(expected, d.text);
        assertEquals(expected, sub(d, only(d, Markdown.Type.CODE_BLOCK)));
        assertEquals(1, d.spans.size());
    }

    @Test
    public void fenceWithTrailingTextInsideBlockIsLiteral() {
        Markdown.Doc d = parse("`````markdown\n````text\nliteral\n`````\nafter");
        assertEquals("````text\nliteral\nafter", d.text);
        assertEquals("````text\nliteral", sub(d, only(d, Markdown.Type.CODE_BLOCK)));
    }

    @Test
    public void shorterBareFenceInsideBlockIsLiteral() {
        Markdown.Doc d = parse("````markdown\n```\nafter\n````");
        String expected = "```\nafter";
        assertEquals(expected, d.text);
        assertEquals(expected, sub(d, only(d, Markdown.Type.CODE_BLOCK)));
    }

    @Test
    public void unterminatedFenceContinuesToEnd() {
        Markdown.Doc d = parse("```text\nfirst\n```js\nlast");
        String expected = "first\n```js\nlast";
        assertEquals(expected, d.text);
        assertEquals(expected, sub(d, only(d, Markdown.Type.CODE_BLOCK)));
    }

    @Test
    public void inlineCodeCanContainLongerBacktickRun() {
        Markdown.Doc d = parse("use `a``b` now");
        assertEquals("use a``b now", d.text);
        assertEquals("a``b", sub(d, only(d, Markdown.Type.CODE)));
    }

    @Test
    public void inlineCodeCanContainShorterBacktickRun() {
        Markdown.Doc d = parse("use ``a`b`` now");
        assertEquals("use a`b now", d.text);
        assertEquals("a`b", sub(d, only(d, Markdown.Type.CODE)));
    }

    @Test
    public void inlineCodeNormalizesPaddingSpaces() {
        Markdown.Doc d = parse("use ` code ` now");
        assertEquals("use code now", d.text);
        assertEquals("code", sub(d, only(d, Markdown.Type.CODE)));
    }

    @Test
    public void snakeCaseIsNotItalic() {
        // Underscores inside a word must not be treated as emphasis.
        Markdown.Doc d = parse("call some_long_name()");
        assertEquals("call some_long_name()", d.text);
        assertTrue(d.spans.isEmpty());
    }

    @Test
    public void unterminatedMarkerIsLiteral() {
        Markdown.Doc d = parse("2 * 3 = 6");
        assertEquals("2 * 3 = 6", d.text);
        assertTrue(d.spans.isEmpty());
    }

    @Test
    public void nestedEmphasisInsideBold() {
        Markdown.Doc d = parse("**bold `code`**");
        assertEquals("bold code", d.text);
        assertEquals("bold code", sub(d, only(d, Markdown.Type.BOLD)));
        assertEquals("code", sub(d, only(d, Markdown.Type.CODE)));
    }

    @Test
    public void strikethrough() {
        Markdown.Doc d = parse("~~gone~~");
        assertEquals("gone", d.text);
        assertEquals("gone", sub(d, only(d, Markdown.Type.STRIKE)));
    }

    /** The spans of the given type, in order. */
    private static List<Markdown.Span> all(Markdown.Doc d, Markdown.Type type) {
        List<Markdown.Span> found = new java.util.ArrayList<>();
        for (Markdown.Span s : d.spans) {
            if (s.type == type) found.add(s);
        }
        return found;
    }

    /** Cell texts of the doc's only table, header row first. */
    private static List<List<String>> cells(Markdown.Table t) {
        List<List<String>> rows = new java.util.ArrayList<>();
        for (Markdown.Doc[] row : t.rows) {
            List<String> texts = new java.util.ArrayList<>();
            for (Markdown.Doc cell : row) texts.add(cell.text);
            rows.add(texts);
        }
        return rows;
    }

    private static Markdown.Table onlyTable(Markdown.Doc d) {
        assertEquals(1, d.tables.size());
        return d.tables.get(0);
    }

    private static List<List<String>> rows(String[]... rows) {
        List<List<String>> out = new java.util.ArrayList<>();
        for (String[] row : rows) out.add(java.util.Arrays.asList(row));
        return out;
    }

    @Test
    public void pipeTableWithHeaderAndBody() {
        Markdown.Doc d = parse("| a | b |\n|---|---|\n| 1 | **2** |");
        assertEquals(String.valueOf(Markdown.TABLE_MARK), d.text);
        assertEquals(d.text, sub(d, only(d, Markdown.Type.TABLE)));
        Markdown.Table t = onlyTable(d);
        assertEquals(rows(new String[] {"a", "b"}, new String[] {"1", "2"}), cells(t));
        Markdown.Doc bold = t.rows.get(1)[1];
        assertEquals("2", sub(bold, only(bold, Markdown.Type.BOLD)));
    }

    @Test
    public void tableOuterPipesAreOptional() {
        Markdown.Doc d = parse("a | b\n--- | ---\n1 | 2");
        assertEquals(rows(new String[] {"a", "b"}, new String[] {"1", "2"}), cells(onlyTable(d)));
    }

    @Test
    public void tableDelimiterColonsSetAlignment() {
        Markdown.Doc d = parse("| a | b | c | d |\n| :-- | :-: | --: | --- |");
        Markdown.Table t = onlyTable(d);
        assertEquals(
                java.util.Arrays.asList(Markdown.Align.LEFT, Markdown.Align.CENTER,
                        Markdown.Align.RIGHT, Markdown.Align.NONE),
                t.aligns);
        assertEquals(rows(new String[] {"a", "b", "c", "d"}), cells(t));
    }

    @Test
    public void escapedPipesAndPipesInCodeDoNotSplitCells() {
        Markdown.Table t = onlyTable(
                parse("| op | note |\n|---|---|\n| `a || b` | x \\| y |\n| `c \\| d` | z |"));
        assertEquals(rows(new String[] {"op", "note"},
                new String[] {"a || b", "x | y"},
                new String[] {"c | d", "z"}), cells(t));
        Markdown.Doc code = t.rows.get(1)[0];
        assertEquals("a || b", sub(code, only(code, Markdown.Type.CODE)));
        code = t.rows.get(2)[0];
        assertEquals("c | d", sub(code, only(code, Markdown.Type.CODE)));
    }

    @Test
    public void raggedTableRowsArePaddedAndTruncatedToHeader() {
        Markdown.Table t = onlyTable(parse("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |\n| | 2 |"));
        assertEquals(rows(new String[] {"a", "b"},
                new String[] {"1", ""},
                new String[] {"1", "2"},
                new String[] {"", "2"}), cells(t));
    }

    @Test
    public void tableNeedsMatchingDelimiterRow() {
        for (String md : new String[] {"| a | b |\n| 1 | 2 |", "| a | b |\n|---|", "a | b"}) {
            Markdown.Doc d = parse(md);
            assertEquals(md, d.text);
            assertTrue(d.tables.isEmpty());
            assertTrue(all(d, Markdown.Type.TABLE).isEmpty());
        }
    }

    @Test
    public void tableEndsAtBlankOrPipelessLine() {
        String mark = String.valueOf(Markdown.TABLE_MARK);
        Markdown.Doc d = parse("intro\n| a |\n|---|\n| 1 |\nafter");
        assertEquals("intro\n" + mark + "\nafter", d.text);
        assertEquals(rows(new String[] {"a"}, new String[] {"1"}), cells(onlyTable(d)));

        d = parse("| a |\n|---|\n\n| 1 |");
        assertEquals(mark + "\n\n| 1 |", d.text);
        assertEquals(rows(new String[] {"a"}), cells(onlyTable(d)));
    }

    @Test
    public void blocksSplitProseAroundTables() {
        Markdown.Doc d = parse("**intro**\n\n| a |\n|---|\n| `1` |\n\nafter\n| b |\n|---|");
        List<Markdown.Block> blocks = Markdown.blocks(d);
        assertEquals(4, blocks.size());

        Markdown.Doc intro = blocks.get(0).doc;
        assertNull(blocks.get(0).table);
        assertEquals("intro", intro.text);
        assertEquals("intro", sub(intro, only(intro, Markdown.Type.BOLD)));

        assertNull(blocks.get(1).doc);
        assertEquals(rows(new String[] {"a"}, new String[] {"1"}), cells(blocks.get(1).table));

        assertEquals("after", blocks.get(2).doc.text);
        assertEquals(rows(new String[] {"b"}), cells(blocks.get(3).table));
    }

    @Test
    public void fenceInfoStringNamesLanguage() {
        assertEquals("kotlin", only(parse("```kotlin\nval x = 1\n```"), Markdown.Type.CODE_BLOCK).lang);
        assertEquals("js", only(parse("``` js title=a.js\nx\n```"), Markdown.Type.CODE_BLOCK).lang);
        assertNull(only(parse("```\nx\n```"), Markdown.Type.CODE_BLOCK).lang);
    }

    @Test
    public void blocksSplitProseAroundCodeBlocks() {
        List<Markdown.Block> blocks = Markdown.blocks(
                parse("intro `x`\n\n```java\nint a;\n  int b;\n```\nafter\n```\n```"));
        assertEquals(4, blocks.size());

        Markdown.Doc intro = blocks.get(0).doc;
        assertEquals("intro x", intro.text);
        assertEquals("x", sub(intro, only(intro, Markdown.Type.CODE)));

        Markdown.Block code = blocks.get(1);
        assertNull(code.doc);
        assertNull(code.table);
        assertEquals("int a;\n  int b;", code.code);
        assertEquals("java", code.lang);

        assertEquals("after", blocks.get(2).doc.text);

        // an empty block still gets its card
        assertEquals("", blocks.get(3).code);
        assertNull(blocks.get(3).lang);
    }

    @Test
    public void blocksWithoutTablesIsOneProseBlock() {
        List<Markdown.Block> blocks = Markdown.blocks(parse("one\n\ntwo"));
        assertEquals(1, blocks.size());
        assertEquals("one\n\ntwo", blocks.get(0).doc.text);
        assertTrue(Markdown.blocks(parse("")).isEmpty());
    }

    @Test
    public void nullInputIsEmpty() {
        Markdown.Doc d = parse(null);
        assertEquals("", d.text);
        assertTrue(d.spans.isEmpty());
    }
}
