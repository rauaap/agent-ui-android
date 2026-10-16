package com.agentui.app;

import org.junit.Test;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class SessionVisibilityTest {
    private final long now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli();
    private Session session(String name, String timestamp) {
        return new Session("10", name, "1", "/app", "", "pi", "idle", timestamp, false, false);
    }
    @Test public void inclusiveBoundaryAndTimeAloneCanHideSessions() {
        Session s = session("Old", Instant.ofEpochMilli(now - 7 * SessionVisibility.DAY).toString());
        assertTrue(SessionVisibility.visible(s, 7, "", now));
        assertFalse(SessionVisibility.visible(s, 7, "", now + 1));
        assertTrue(SessionVisibility.visible(s, null, "", now + 1));
    }
    @Test public void nameSearchOverridesAgeAndInvalidMetadataStaysAccessible() {
        Session s = session("Old LOGIN", "2020-01-01T00:00:00Z");
        assertFalse(SessionVisibility.visible(s, 7, "app", now));
        assertTrue(SessionVisibility.visible(s, 7, "login", now));
        assertTrue(SessionVisibility.visible(session("Legacy", ""), 7, "", now));
        assertTrue(SessionVisibility.visible(session("Bad", "invalid"), 7, "", now));
        assertTrue(SessionVisibility.visible(session("Future", "2030-01-01T00:00:00Z"), 7, "", now));
    }
    @Test public void sliderPresetsRoundUpWithoutChangingExactCutoffs() {
        int[] days = {1, 2, 3, 4, 5, 6, 7, 14, 30, 60, 90};
        for (int i = 0; i < days.length; i++) {
            assertEquals(i, SessionAgeSteps.position(days[i]));
            assertEquals(Integer.valueOf(days[i]), SessionAgeSteps.days(i));
        }
        assertEquals(7, SessionAgeSteps.position(10));
        assertEquals(9, SessionAgeSteps.position(45));
        assertEquals(SessionAgeSteps.ALL, SessionAgeSteps.position(100));
        assertEquals(SessionAgeSteps.ALL, SessionAgeSteps.position(Integer.MAX_VALUE));
        assertEquals(SessionAgeSteps.ALL, SessionAgeSteps.position(null));
        assertNull(SessionAgeSteps.days(SessionAgeSteps.ALL));
        Session old = session("Old", Instant.ofEpochMilli(now - 101 * SessionVisibility.DAY).toString());
        assertFalse(SessionVisibility.visible(old, 100, "", now));
        assertTrue(SessionVisibility.visible(old, SessionAgeSteps.days(SessionAgeSteps.ALL), "", now));
    }
    @Test public void projectSearchHidesOldChildrenButRetainsProjectsAndNameMatches() {
        Project project = new Project("1", "/app", "App", 2, "", true);
        Session old = session("Old", "2020-01-01T00:00:00Z");
        Session named = session("App investigation", "2020-01-01T00:00:00Z");
        ProjectSearch.Index index = new ProjectSearch.Index(Collections.singletonList(project), Arrays.asList(old, named));
        List<ProjectSearch.Group> filtered = index.filter("app", 7, now);
        assertEquals(1, filtered.size());
        assertEquals(Collections.singletonList(named), filtered.get(0).sessions);
        assertEquals(1, filtered.get(0).hidden);
        assertEquals(2, index.filter("app", null, now).get(0).sessions.size());
        assertEquals(Collections.singletonList(old), index.filter("old", 7, now).get(0).sessions);
        assertEquals(2, index.filter("/app", 7, now).get(0).hidden);
        assertTrue(index.filter("/app", 7, now).get(0).sessions.isEmpty());
    }
}
