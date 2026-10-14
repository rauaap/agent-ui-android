package com.agentui.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

/** Local, metadata-only search; leaves the source lists and their ordering intact. */
final class ProjectSearch {
    /** Stable row/expansion identity, including projects from legacy servers. */
    static String projectKey(Project project) {
        return project.id.isEmpty() ? "path:" + project.path : "id:" + project.id;
    }

    /** Independent of the query: a temporarily hidden project keeps its state. */
    static final class Expansion {
        private final java.util.Set<String> collapsed = new java.util.HashSet<>();

        boolean isExpanded(Project project) {
            return !collapsed.contains(projectKey(project));
        }

        void toggle(Project project) {
            String key = projectKey(project);
            if (!collapsed.remove(key)) collapsed.add(key);
        }

        ArrayList<String> snapshot() {
            return new ArrayList<>(collapsed);
        }

        void restore(List<String> keys) {
            collapsed.clear();
            if (keys != null) collapsed.addAll(keys);
        }
    }

    static final class Group {
        final Project project;
        final List<Session> sessions;
        /** Summary of ALL owned sessions, not just query-matching children. */
        final String status;

        Group(Project project, List<Session> sessions, String status) {
            this.project = project;
            this.sessions = sessions;
            this.status = status;
        }
    }

    static String normalizeQuery(String value) { return value.trim().toLowerCase(Locale.ROOT); }

    /** Catalog snapshot: ownership, normalized text and badges are computed only
     * when project/session data changes, never while binding views. */
    static final class Index {
        private final List<Bucket> buckets = new ArrayList<>();

        Index(List<Project> projects, List<Session> sessions) {
            Map<String, List<Bucket>> byId = new HashMap<>();
            Map<String, List<Bucket>> byPath = new HashMap<>();
            Map<String, List<Bucket>> legacyByPath = new HashMap<>();
            for (Project project : projects) {
                Bucket bucket = new Bucket(project);
                buckets.add(bucket);
                byPath.computeIfAbsent(project.path, ignored -> new ArrayList<>()).add(bucket);
                if (project.id.isEmpty()) {
                    legacyByPath.computeIfAbsent(project.path, ignored -> new ArrayList<>()).add(bucket);
                } else byId.computeIfAbsent(project.id, ignored -> new ArrayList<>()).add(bucket);
            }
            // Iterate sessions in source order, routing by ID or the exact legacy
            // path fallback. Worktrees are owned by ID, not by their directory.
            for (Session session : sessions) {
                Entry entry = new Entry(session);
                if (session.projectId.isEmpty()) route(byPath.get(session.workingDir), entry);
                else {
                    route(byId.get(session.projectId), entry);
                    route(legacyByPath.get(session.workingDir), entry);
                }
            }
            for (Bucket bucket : buckets) {
                bucket.status = ProjectStatus.aggregate(bucket.project, bucket.sessions);
            }
        }

        List<Group> filter(String value) {
            String query = normalizeQuery(value);
            if (query.isEmpty()) return Collections.emptyList();
            List<Group> groups = new ArrayList<>();
            for (Bucket bucket : buckets) {
                boolean projectMatches = bucket.name.contains(query) || bucket.path.contains(query);
                List<Session> children;
                if (projectMatches) children = bucket.sessions;
                else {
                    children = new ArrayList<>();
                    for (Entry entry : bucket.entries) {
                        if (entry.name.contains(query)) children.add(entry.session);
                    }
                }
                if (projectMatches || !children.isEmpty()) {
                    groups.add(new Group(bucket.project, children, bucket.status));
                }
            }
            return groups;
        }

        private static void route(List<Bucket> buckets, Entry entry) {
            if (buckets == null) return;
            for (Bucket bucket : buckets) {
                bucket.entries.add(entry);
                bucket.sessions.add(entry.session);
            }
        }
        private static final class Entry {
            final Session session;
            final String name;
            Entry(Session session) {
                this.session = session;
                name = (session.name.isEmpty() ? session.id : session.name).toLowerCase(Locale.ROOT);
            }
        }
        private static final class Bucket {
            final Project project;
            final String name, path;
            final List<Entry> entries = new ArrayList<>();
            final List<Session> sessions = new ArrayList<>();
            String status;
            Bucket(Project project) {
                this.project = project;
                name = project.name.toLowerCase(Locale.ROOT);
                path = project.path.toLowerCase(Locale.ROOT);
            }
        }
    }

    /** Convenience for one-shot callers; interactive search retains its Index. */
    static List<Group> filter(List<Project> projects, List<Session> sessions, String value) {
        return new Index(projects, sessions).filter(value);
    }
}
