package com.agentui.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MarkdownLinksTest {
    @Test public void acceptsBrowserUrls() {
        assertTrue(MarkdownLinks.isWebUrl("https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference#reference"));
        assertTrue(MarkdownLinks.isWebUrl("http://example.com/docs?q=one%20two"));
        assertTrue(MarkdownLinks.isWebUrl("HTTPS://developer.mozilla.org/"));
    }

    @Test public void rejectsUnsafeOrMalformedTargets() {
        for (String href : new String[] {null, "", "javascript:alert(1)",
                "intent://example.com", "file:///etc/passwd", "mailto:a@example.com",
                "/relative", "https://", "https:///missing-host", "https://example.com/a b"}) {
            assertFalse(String.valueOf(href), MarkdownLinks.isWebUrl(href));
        }
    }
}
