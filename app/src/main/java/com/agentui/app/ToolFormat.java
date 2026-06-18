package com.agentui.app;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds human-readable views for {@code tool_use} payloads. The raw tool
 * arguments are JSON meant for machines; this turns the common cases into
 * something a person can scan — git-style diffs for file edits and a terminal
 * block for shell commands. Anything it doesn't recognise returns {@code null}
 * so the caller can fall back to pretty-printed JSON.
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
    static View body(Context ctx, String tool, JSONObject in) {
        if (in == null) return null;
        switch (tool) {
            case "Edit":
            case "MultiEdit":
            case "Write":
                return diff(ctx, tool, in);
            case "Bash":
                return command(ctx, in);
            default:
                return null;
        }
    }

    /* ---------------------------------------------------------------- */
    /* file edits → git-style diff                                      */
    /* ---------------------------------------------------------------- */

    private static View diff(Context ctx, String tool, JSONObject in) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        switch (tool) {
            case "MultiEdit":
                JSONArray edits = in.optJSONArray("edits");
                if (edits != null) {
                    for (int i = 0; i < edits.length(); i++) {
                        JSONObject e = edits.optJSONObject(i);
                        if (e == null) continue;
                        if (sb.length() > 0) line(sb, "", "⋯", CTX); // ⋯ separator
                        hunk(sb, e.optString("old_string", ""), e.optString("new_string", ""));
                    }
                }
                break;
            case "Write":
                hunk(sb, "", in.optString("content", ""));
                break;
            default: // Edit
                hunk(sb, in.optString("old_string", ""), in.optString("new_string", ""));
                break;
        }
        if (sb.length() == 0) return null;
        return Widgets.mono(ctx, sb, Theme.INK, 12.5f);
    }

    /** Append a removed/added pair as a diff hunk, eliding common context. */
    private static void hunk(SpannableStringBuilder sb, String oldS, String newS) {
        String[] o = oldS.split("\n", -1);
        String[] n = newS.split("\n", -1);

        int pre = 0;
        while (pre < o.length && pre < n.length && o[pre].equals(n[pre])) pre++;
        int suf = 0;
        while (suf < o.length - pre && suf < n.length - pre
                && o[o.length - 1 - suf].equals(n[n.length - 1 - suf])) suf++;

        for (int i = 0; i < pre; i++) line(sb, " ", o[i], CTX);
        for (int i = pre; i < o.length - suf; i++) line(sb, "-", o[i], DEL);
        for (int i = pre; i < n.length - suf; i++) line(sb, "+", n[i], ADD);
        for (int i = n.length - suf; i < n.length; i++) line(sb, " ", n[i], CTX);
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
