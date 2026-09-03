package com.agentui.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Splitting the two list responses into what is live and what is archived.
 *
 * <p>The server records the archive — {@code archived_at}, null while live —
 * but deliberately does not filter either listing, so the split is the client's
 * job and happens here. Everything the archive view needs is already in those
 * two responses; nothing extra is fetched.
 *
 * <p>Ordering matters as much as the split. {@code GET /projects} sorts by
 * {@code last_active_at} descending, and an archived project's is always null,
 * so the server's order dumps the archive at the end in no useful sequence.
 * The archive is therefore sorted here, by {@code archived_at} descending —
 * most recently filed first.
 *
 * <p>Pure and unit tested; the activities do the fetching and the drawing.
 */
final class Archive {
    private Archive() {}

    private static final Comparator<String> NEWEST_FIRST = Comparator.reverseOrder();

    /* ----------------------------------------------------------------- */
    /* sessions                                                          */
    /* ----------------------------------------------------------------- */

    /** The sessions a list screen shows. */
    static List<Session> liveSessions(List<Session> sessions) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (!s.isArchived()) out.add(s);
        }
        return out;
    }

    /** The archived sessions, newest filing first. */
    static List<Session> archivedSessions(List<Session> sessions) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (s.isArchived()) out.add(s);
        }
        sortSessions(out);
        return out;
    }

    /**
     * The archived sessions the archive lists on their own: the ones under a
     * project that is still live. A session under an archived project is
     * already represented by that project's row — and by the server's cascade
     * there is no other kind of session under one — so listing it again would
     * be the same thing twice. Sessions belonging to no project the client
     * knows about are kept, not silently dropped.
     */
    static List<Session> looseArchivedSessions(List<Project> projects, List<Session> sessions) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions) {
            if (!s.isArchived() || underArchivedProject(projects, s)) continue;
            out.add(s);
        }
        sortSessions(out);
        return out;
    }

    private static boolean underArchivedProject(List<Project> projects, Session s) {
        for (Project p : projects) {
            if (p.isArchived() && p.owns(s)) return true;
        }
        return false;
    }

    /* ----------------------------------------------------------------- */
    /* projects                                                          */
    /* ----------------------------------------------------------------- */

    /** The projects the launcher shows. */
    static List<Project> liveProjects(List<Project> projects) {
        List<Project> out = new ArrayList<>();
        for (Project p : projects) {
            if (!p.isArchived()) out.add(p);
        }
        return out;
    }

    /** The archived projects, newest filing first. */
    static List<Project> archivedProjects(List<Project> projects) {
        List<Project> out = new ArrayList<>();
        for (Project p : projects) {
            if (p.isArchived()) out.add(p);
        }
        Collections.sort(out, (a, b) -> NEWEST_FIRST.compare(a.archivedAt, b.archivedAt));
        return out;
    }

    /** The project with this path, or null when the listing has no such row. */
    static Project find(List<Project> projects, String path) {
        for (Project p : projects) {
            if (p.path.equals(path)) return p;
        }
        return null;
    }

    // ISO 8601 in UTC with a trailing Z, so lexicographic order is chronological.
    private static void sortSessions(List<Session> sessions) {
        Collections.sort(sessions, (a, b) -> NEWEST_FIRST.compare(a.archivedAt, b.archivedAt));
    }
}
