package com.agentui.app;

import java.util.List;

/** Project badge policy, independent of view construction. */
final class ProjectStatus {
    private ProjectStatus() {}

    static String aggregate(Project project, List<Session> sessions) {
        if (sessions == null) return null;
        String status = null;
        for (Session session : sessions) {
            if (!project.owns(session)) continue;
            if ("awaiting_approval".equals(session.status)) return "awaiting_approval";
            if ("running".equals(session.status)) status = "running";
        }
        return status;
    }
}
