package com.agentui.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Shared project card, with optional list metadata and in-place binding. */
final class ProjectCards {
    static LinearLayout build(Context context, Project p, List<Session> sessions,
                              DirectorySnapshot existence, Runnable open, Runnable delete) {
        return build(context, p, sessions, existence, open, 0, "Delete project", delete);
    }

    static LinearLayout build(Context context, Project p, List<Session> sessions,
                              DirectorySnapshot existence, Runnable open, int actionIcon, String actionDescription,
                              Runnable action) {
        Card card = new Card(context, ignored -> open.run(), ignored -> action.run(),
                actionIcon, actionDescription, false);
        card.bind(p, sessions, existence);
        return card;
    }

    static final class Card extends LinearLayout {
        private final TextView name, status, missing, path, meta;
        private Project project;

        Card(Context context, Consumer<Project> open, Consumer<Project> action,
             int actionIcon, String actionDescription, boolean showMetadata) {
            super(context);
            setOrientation(VERTICAL);
            setBackground(Theme.rounded(context, Theme.PANEL, 14, Theme.LINE, 1));
            int pad = Theme.dp(context, 16);
            setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
            cardLp.bottomMargin = Theme.dp(context, 12);
            setLayoutParams(cardLp);
            setOnClickListener(v -> open.accept(project));

            LinearLayout head = Widgets.row(context);
            name = Widgets.text(context, "", Theme.INK, 16, true);
            name.setLayoutParams(lp(0, WRAP, 1f));
            head.addView(name);
            status = Widgets.statusBadge(context, "idle");
            Widgets.margins(status, Theme.dp(context, 8), 0, 0, 0);
            head.addView(status);
            missing = Widgets.tag(context, "missing", Theme.DANGER);
            Widgets.margins(missing, Theme.dp(context, 8), 0, 0, 0);
            head.addView(missing);
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
            actionButton.setOnClickListener(v -> action.accept(project));
            head.addView(actionButton);
            addView(head);
            path = Widgets.mono(context, "", Theme.FAINT, 12);
            Widgets.margins(path, 0, Theme.dp(context, 10), 0, 0);
            addView(path);
            if (showMetadata) {
                LinearLayout metaRow = Widgets.row(context);
                meta = Widgets.text(context, "", Theme.MUTED, 12.5f, false);
                meta.setLayoutParams(lp(0, WRAP, 1f));
                metaRow.addView(meta);
                Widgets.margins(metaRow, 0, Theme.dp(context, 8), 0, 0);
                addView(metaRow);
            } else meta = null;
        }

        void bind(Project fresh, List<Session> sessions, DirectorySnapshot existence) {
            project = fresh;
            Widgets.bindText(name, fresh.name);
            String aggregate = aggregateStatus(fresh, sessions);
            status.setVisibility(aggregate == null ? GONE : VISIBLE);
            if (aggregate != null) Widgets.bindStatusBadge(status, aggregate);
            boolean absent = existence.missing(fresh.path);
            missing.setVisibility(absent ? VISIBLE : GONE);
            Widgets.bindText(path, fresh.path);
            int pathColor = absent ? Theme.DANGER : Theme.FAINT;
            if (path.getCurrentTextColor() != pathColor) path.setTextColor(pathColor);
            if (meta != null) {
                String text = fresh.sessionCount + (fresh.sessionCount == 1 ? " session" : " sessions");
                String when = SessionListActivity.formatTime(fresh.lastActiveAt);
                if (!when.isEmpty()) text += "  ·  " + when;
                Widgets.bindText(meta, text);
            }
        }
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
