package com.agentui.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SelectionQuoteTest {
    @Test public void prefixesSpaceAndAppendsExactlyTwoNewlines() {
        assertEquals("> highlighted text\n\n", SelectionQuote.format("highlighted text"));
    }

    @Test public void preservesHighlightedWhitespaceAndLineBreaks() {
        assertEquals(">  first line\nsecond line \n\n",
                SelectionQuote.format(" first line\nsecond line "));
    }

    @Test public void preservesMarkdownAndUnicode() {
        assertEquals("> **café** ☕\n\n", SelectionQuote.format("**café** ☕"));
    }
}
