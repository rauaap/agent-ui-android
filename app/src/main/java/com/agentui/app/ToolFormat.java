package com.agentui.app;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds human-readable views for canonical tool actions: git-style diffs for
 * file changes and a terminal block for commands. Other action kinds return
 * {@code null} so the caller can show their canonical JSON.
 */
final class ToolFormat {
    private ToolFormat() {}

    private static final int ADD = 0xFF6FD39B; // added line (green)
    private static final int DEL = Theme.DANGER; // removed line (red)
    private static final int CTX = Theme.FAINT;  // unchanged context line

    /**
     * A formatted body view for the collapsible tool card, or {@code null} when
     * the tool has no special formatting and the caller should show raw JSON.
     */
    static View body(Context ctx, CanonicalAction action) {
        if (action == null) return null;
        switch (action.kind()) {
            case "edit":
            case "write":
                return diff(ctx, action);
            case "command":
                return command(ctx, action.json());
            default:
                return null;
        }
    }

    /* ---------------------------------------------------------------- */
    /* file edits → git-style diff                                      */
    /* ---------------------------------------------------------------- */

    private static View diff(Context ctx, CanonicalAction action) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        JSONObject value = action.json();
        if ("edit".equals(action.kind())) {
            JSONArray edits = value.optJSONArray("edits");
            if (edits != null) {
                for (int i = 0; i < edits.length(); i++) {
                    JSONObject edit = edits.optJSONObject(i);
                    if (edit == null) continue;
                    if (sb.length() > 0) line(sb, "", "⋯", CTX);
                    appendDiff(sb, edit.optString("old_text", ""),
                            edit.optString("new_text", ""));
                }
            }
        } else { // write
            appendDiff(sb, "", value.optString("content", ""));
        }
        if (sb.length() == 0) return null;
        return Widgets.mono(ctx, sb, Theme.INK, 12.5f);
    }

    /** Paint a {@link LineDiff} as red/green/dim gutter lines into the spannable. */
    private static void appendDiff(SpannableStringBuilder sb, String oldS, String newS) {
        for (LineDiff.Row row : LineDiff.diff(oldS, newS)) {
            switch (row.kind) {
                case ADD:    line(sb, "+", row.text, ADD); break;
                case DELETE: line(sb, "-", row.text, DEL); break;
                default:     line(sb, " ", row.text, CTX); break;
            }
        }
    }

    private static void line(SpannableStringBuilder sb, String gutter, String text, int color) {
        if (sb.length() > 0) sb.append("\n");
        int start = sb.length();
        sb.append(gutter).append(' ').append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /* ---------------------------------------------------------------- */
    /* shell commands → terminal block                                  */
    /* ---------------------------------------------------------------- */

    private static View command(Context ctx, JSONObject in) {
        String cmd = in.optString("command", "");
        if (cmd.isEmpty()) return null;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = cmd.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            int start = sb.length();
            sb.append(i == 0 ? "$ " : "  ");
            sb.setSpan(new ForegroundColorSpan(Theme.ACCENT_STRONG), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(lines[i]);
        }

        String desc = in.optString("description", "");
        if (!desc.isEmpty()) {
            sb.append('\n');
            int start = sb.length();
            sb.append("# ").append(desc);
            sb.setSpan(new ForegroundColorSpan(CTX), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return Widgets.mono(ctx, sb, Theme.INK, 13f);
    }
}
