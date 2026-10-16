package com.agentui.app;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Shared session card; list owners can bind fresh models without replacing views. */
final class SessionCards {
    private SessionCards() {}

    static View build(Context context, Session s, String projectDir,
                      List<Agent> agents, DirectorySnapshot existence, Runnable open, Runnable menu) {
        Card card = new Card(context, ignored -> open.run(),
                menu == null ? null : ignored -> menu.run());
        card.bind(s, projectDir, agents, existence);
        return card;
    }

    static final class Card extends LinearLayout {
        private final TextView name, status, path, meta, worktree, former, missing;
        private final LinearLayout where;
        private Session session;
        private Boolean elsewhereMargin;

        Card(Context context, Consumer<Session> open, Consumer<Session> menu) {
            super(context);
            setOrientation(VERTICAL);
            setBackground(Theme.rounded(context, Theme.PANEL, 14, Theme.LINE, 1));
            int pad = Theme.dp(context, 16);
            setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cardLp = lp(MATCH, WRAP);
            cardLp.bottomMargin = Theme.dp(context, 12);
            setLayoutParams(cardLp);
            setOnClickListener(v -> open.accept(session));
            if (menu != null) setOnLongClickListener(v -> { menu.accept(session); return true; });

            LinearLayout head = Widgets.row(context);
            name = Widgets.text(context, "", Theme.INK, 16, true);
            name.setLayoutParams(lp(0, WRAP, 1f));
            head.addView(name);
            status = Widgets.statusBadge(context, "idle");
            head.addView(status);
            addView(head);

            where = Widgets.row(context);
            worktree = marker(context, "WT", Theme.INFO);
            worktree.setContentDescription("Worktree");
            former = marker(context, "FWT", Theme.MUTED);
            former.setContentDescription("Former worktree");
            missing = marker(context, "missing", Theme.DANGER);
            where.addView(worktree);
            where.addView(former);
            where.addView(missing);
            path = Widgets.mono(context, "", Theme.FAINT, 12);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            path.setLayoutParams(lp(0, WRAP, 1f));
            where.addView(path);
            Widgets.margins(where, 0, Theme.dp(context, 10), 0, 0);
            addView(where);
            meta = Widgets.text(context, "", Theme.MUTED, 12.5f, false);
            addView(meta);
        }

        void bind(Session fresh, String projectDir, List<Agent> agents, DirectorySnapshot existence) {
            session = fresh; // Click and long-press handlers always use the current model.
            Widgets.bindText(name, fresh.name);
            Widgets.bindStatusBadge(status, fresh.status);
            boolean elsewhere = projectDir == null || !projectDir.equals(fresh.workingDir);
            boolean absent = existence.missing(fresh.workingDir);
            where.setVisibility(elsewhere || absent ? VISIBLE : GONE);
            worktree.setVisibility(!fresh.worktreeId.isEmpty() ? VISIBLE : GONE);
            former.setVisibility(fresh.worktreeId.isEmpty() && fresh.isFormerWorktree(projectDir) ? VISIBLE : GONE);
            missing.setVisibility(absent ? VISIBLE : GONE);
            Widgets.bindText(path, fresh.workingDir);
            int pathColor = absent ? Theme.DANGER : Theme.FAINT;
            if (path.getCurrentTextColor() != pathColor) path.setTextColor(pathColor);
            String model = Agent.modelLabel(agents, fresh.agent, fresh.model);
            Widgets.bindText(meta, Agent.label(agents, fresh.agent)
                    + (model != null ? "  ·  " + model : "")
                    + (fresh.reasoningLevel != null ? "  ·  " + fresh.reasoningLevel : "")
                    + "  ·  " + SessionListActivity.formatTime(fresh.lastActiveAt));
            if (elsewhereMargin == null || elsewhereMargin != elsewhere) {
                Widgets.margins(meta, 0, Theme.dp(getContext(), elsewhere ? 8 : 10), 0, 0);
                elsewhereMargin = elsewhere;
            }
        }

        private static TextView marker(Context context, String label, int color) {
            TextView tag = Widgets.tag(context, label, color);
            Widgets.margins(tag, 0, 0, Theme.dp(context, 8), 0);
            return tag;
        }
    }
}
