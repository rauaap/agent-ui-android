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

    @Test
    public void nullInputIsEmpty() {
        Markdown.Doc d = parse(null);
        assertEquals("", d.text);
        assertTrue(d.spans.isEmpty());
    }
}
