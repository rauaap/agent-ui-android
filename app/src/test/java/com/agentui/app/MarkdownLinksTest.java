package com.agentui.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MarkdownLinksTest {
    @Test public void acceptsBrowserUrls() {
        assertTrue(MarkdownLinks.isWebUrl("https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference#reference"));
        assertTrue(MarkdownLinks.isWebUrl("http://example.com/docs?q=one%20two"));
        assertTrue(MarkdownLinks.isWebUrl("HTTPS://developer.mozilla.org/"));
    }

    @Test public void resolvesSharedAssetsAgainstConfiguredServerOrigin() {
        assertEquals("http://server:8080/shared-assets/notes/report/",
                MarkdownLinks.resolveUrl("/shared-assets/notes/report/", "http://server:8080"));
        assertEquals("https://server:8443/shared-assets/notes/my%20report.html?q=1#chart",
                MarkdownLinks.resolveUrl("/shared-assets/notes/my%20report.html?q=1#chart", "https://server:8443"));
        assertEquals("https://server/shared-assets/notes/",
                MarkdownLinks.resolveUrl("/shared-assets/notes/", "https://server/settings/"));
        assertTrue(MarkdownLinks.isActionableUrl("/shared-assets/notes/report/"));
    }

    @Test public void externalLinksDoNotRequireServerResolution() {
        String mdn = "https://developer.mozilla.org/en-US/docs/Web/JavaScript";
        assertEquals(mdn, MarkdownLinks.resolveUrl(mdn, null));
        assertEquals(mdn, MarkdownLinks.resolveUrl(mdn, "http://server:8080"));
    }

    @Test public void rejectsOtherRelativeTargetsAndAssetTraversal() {
        for (String href : new String[] {"/projects", "notes/report/", "//evil.example/shared-assets/notes/",
                "/shared-assets-evil/notes/", "/shared-assets/../../projects",
                "/shared-assets/%2e%2e/projects", "/shared-assets/notes/../../../projects",
                "/shared-assets/notes/\\\\evil", "/shared-assets/notes/\n"}) {
            assertNull(href, MarkdownLinks.resolveUrl(href, "http://server:8080"));
            assertFalse(href, MarkdownLinks.isActionableUrl(href));
        }
        assertNull(MarkdownLinks.resolveUrl("/shared-assets/notes/", null));
        assertNull(MarkdownLinks.resolveUrl("/shared-assets/notes/", "not a server"));
    }

    @Test public void rejectsUnsafeOrMalformedTargets() {
        for (String href : new String[] {null, "", "javascript:alert(1)",
                "intent://example.com", "file:///etc/passwd", "mailto:a@example.com",
                "/relative", "https://", "https:///missing-host", "https://example.com/a b"}) {
            assertFalse(String.valueOf(href), MarkdownLinks.isWebUrl(href));
        }
    }
}
