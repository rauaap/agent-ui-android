package com.agentui.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local, metadata-only search; leaves the source lists and their ordering intact. */
final class ProjectSearch {
    /** Independent of the query: a temporarily hidden project keeps its state. */
    static final class Expansion {
        private final java.util.Set<String> collapsed = new java.util.HashSet<>();

        private String key(Project project) {
            return project.id.isEmpty() ? "path:" + project.path : "id:" + project.id;
        }

        boolean isExpanded(Project project) {
            return !collapsed.contains(key(project));
        }

        void toggle(Project project) {
            String key = key(project);
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

        Group(Project project, List<Session> sessions) {
            this.project = project;
            this.sessions = sessions;
        }
    }

    static List<Group> filter(List<Project> projects, List<Session> sessions, String value) {
        List<Group> groups = new ArrayList<>();
        String query = value.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return groups;
        for (Project project : projects) {
            boolean projectMatches = matches(project.name, query) || matches(project.path, query);
            List<Session> children = new ArrayList<>();
            for (Session session : sessions) {
                if (project.owns(session) && (projectMatches
                        || matches(session.name.isEmpty() ? session.id : session.name, query))) {
                    children.add(session);
                }
            }
            if (projectMatches || !children.isEmpty()) groups.add(new Group(project, children));
        }
        return groups;
    }

    private static boolean matches(String text, String query) {
        return text.toLowerCase(Locale.ROOT).contains(query);
    }
}
