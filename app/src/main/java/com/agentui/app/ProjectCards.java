package com.agentui.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Shared project card header and path; callers can append list-only metadata. */
final class ProjectCards {
    static LinearLayout build(Context context, Project p, List<Session> sessions,
                              Runnable open, Runnable delete) {
        return build(context, p, sessions, open, 0, "Delete project", delete);
    }

    static LinearLayout build(Context context, Project p, List<Session> sessions,
                              Runnable open, int actionIcon, String actionDescription,
                              Runnable action) {
        LinearLayout card = Widgets.column(context);
        card.setBackground(Theme.rounded(context, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(context, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(context, 12);
        card.setLayoutParams(cardLp);
        card.setOnClickListener(v -> open.run());

        LinearLayout head = Widgets.row(context);
        TextView name = Widgets.text(context, p.name, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);
        String status = aggregateStatus(p, sessions);
        if (status != null) {
            TextView badge = Widgets.statusBadge(context, status);
            Widgets.margins(badge, Theme.dp(context, 8), 0, 0, 0);
            head.addView(badge);
        }
        if (p.isArchived()) {
            TextView tag = Widgets.tag(context, "archived", Theme.MUTED);
            Widgets.margins(tag, Theme.dp(context, 8), 0, 0, 0);
            head.addView(tag);
        }
        if (!p.exists) {
            TextView tag = Widgets.tag(context, "missing", Theme.DANGER);
            Widgets.margins(tag, Theme.dp(context, 8), 0, 0, 0);
            head.addView(tag);
        }
        View actionButton;
        if (actionIcon != 0) {
            ImageButton icon = new ImageButton(context);
            icon.setImageResource(actionIcon);
            icon.setColorFilter(Theme.INK);
            icon.setBackground(Theme.rounded(context, Theme.PANEL2, 8));
            icon.setPadding(0, 0, 0, 0);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            actionButton = icon;
        } else {
            TextView delete = Widgets.text(context, "×", Theme.MUTED, 22, true);
            delete.setGravity(Gravity.CENTER);
            actionButton = delete;
        }
        int dp32 = Theme.dp(context, 32);
        actionButton.setLayoutParams(lp(dp32, dp32));
        actionButton.setContentDescription(actionDescription);
        Widgets.margins(actionButton, Theme.dp(context, 8), 0, 0, 0);
        actionButton.setOnClickListener(v -> action.run());
        head.addView(actionButton);
        card.addView(head);
        TextView path = Widgets.mono(context, p.path, p.exists ? Theme.FAINT : Theme.DANGER, 12);
        Widgets.margins(path, 0, Theme.dp(context, 10), 0, 0);
        card.addView(path);
        return card;
    }

    static String aggregateStatus(Project project, List<Session> sessions) {
        if (sessions == null) return null;
        boolean running = false;
        for (Session session : sessions) {
            if (!project.owns(session)) continue;
            if ("awaiting_approval".equals(session.status)) return "awaiting_approval";
            if ("running".equals(session.status)) running = true;
        }
        return running ? "running" : null;
    }
}
