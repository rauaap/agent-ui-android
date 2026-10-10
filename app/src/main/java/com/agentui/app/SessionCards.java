package com.agentui.app;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Shared session card for project lists and search results. */
final class SessionCards {
    private SessionCards() {}

    static void addMissingTag(Context context, LinearLayout row, Session session,
                              List<Worktree> worktrees) {
        if (!Worktree.isMissingFor(session, worktrees)) return;
        TextView tag = Widgets.tag(context, "missing", Theme.DANGER);
        Widgets.margins(tag, 0, 0, Theme.dp(context, 8), 0);
        row.addView(tag);
    }

    static View build(Context context, Session s, String projectDir, boolean projectArchived,
                      List<Agent> agents, List<Worktree> worktrees, Runnable open, Runnable menu) {
        LinearLayout card = Widgets.column(context);
        card.setBackground(Theme.rounded(context, Theme.PANEL, 14, Theme.LINE, 1));
        int pad = Theme.dp(context, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
        cardLp.bottomMargin = Theme.dp(context, 12);
        card.setLayoutParams(cardLp);
        card.setClickable(true);
        card.setOnClickListener(v -> open.run());
        if (menu != null) card.setOnLongClickListener(v -> { menu.run(); return true; });

        LinearLayout head = Widgets.row(context);
        TextView name = Widgets.text(context, s.name, Theme.INK, 16, true);
        name.setLayoutParams(lp(0, WRAP, 1f));
        head.addView(name);
        LinearLayout actions = Widgets.row(context);
        addMissingTag(context, actions, s, worktrees);
        if (s.isArchived() && !projectArchived) {
            TextView tag = Widgets.tag(context, "archived", Theme.MUTED);
            Widgets.margins(tag, 0, 0, Theme.dp(context, 8), 0);
            actions.addView(tag);
        }
        actions.addView(Widgets.statusBadge(context, s.status));
        head.addView(actions);
        card.addView(head);

        // A worktree's directory differs from its project's, so keep it visible.
        boolean elsewhere = projectDir == null || !projectDir.equals(s.workingDir);
        if (elsewhere) {
            LinearLayout where = Widgets.row(context);
            if (!s.worktreeId.isEmpty()) {
                TextView tag = Widgets.tag(context, "worktree", Theme.INFO);
                Widgets.margins(tag, 0, 0, Theme.dp(context, 8), 0);
                where.addView(tag);
            } else if (s.isFormerWorktree(projectDir)) {
                TextView tag = Widgets.tag(context, "former worktree", Theme.MUTED);
                Widgets.margins(tag, 0, 0, Theme.dp(context, 8), 0);
                where.addView(tag);
            }
            TextView path = Widgets.mono(context, s.workingDir, Theme.FAINT, 12);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            path.setLayoutParams(lp(0, WRAP, 1f));
            where.addView(path);
            Widgets.margins(where, 0, Theme.dp(context, 10), 0, 0);
            card.addView(where);
        }

        String model = Agent.modelLabel(agents, s.agent, s.model);
        String meta = Agent.label(agents, s.agent) + (model != null ? "  ·  " + model : "")
                + (s.reasoningLevel != null ? "  ·  " + s.reasoningLevel : "")
                + "  ·  " + SessionListActivity.formatTime(s.lastActiveAt);
        TextView metaView = Widgets.text(context, meta, Theme.MUTED, 12.5f, false);
        Widgets.margins(metaView, 0, Theme.dp(context, elsewhere ? 8 : 10), 0, 0);
        card.addView(metaView);
        return card;
    }
}
