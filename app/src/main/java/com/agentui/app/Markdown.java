package com.agentui.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small Markdown subset for agent messages: headings, bold,
 * italic, strikethrough, inline code, fenced code blocks, bullet/numbered
 * lists and links.
 *
 * <p>{@link #parse} is pure (no Android types) so it can be unit-tested on the
 * plain JVM, mirroring {@link LineDiff}. It strips the markers and emits the
 * visible text plus a list of {@link Span}s describing what to style where —
 * the visible text is what the platform copies when you select it, so a
 * selection yields clean prose. {@link #render} turns that into a styled
 * {@link CharSequence}; the raw source is kept elsewhere for the "Copy" button.
 */
final class Markdown {
    private Markdown() {}

    enum Type { BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, HEADING, LINK }

    /** A styling instruction over [start, end) of the parsed text. */
    static final class Span {
        final int start;
        final int end;
        final Type type;
        final int level;   // heading level (1..6); 0 otherwise
        final String href; // link target; null otherwise

        Span(int start, int end, Type type, int level, String href) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.level = level;
            this.href = href;
        }
    }

    /** The visible text with the markers removed, plus the spans over it. */
    static final class Doc {
        final String text;
        final List<Span> spans;

        Doc(String text, List<Span> spans) {
            this.text = text;
            this.spans = spans;
        }
    }

    /* ---------------------------------------------------------------- */
    /* parsing (pure)                                                   */
    /* ---------------------------------------------------------------- */

    static Doc parse(String md) {
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        if (md == null) md = "";
        String[] lines = md.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // fenced code block ``` ... ```
            int openingTicks = leadingBackticks(trimmed);
            if (openingTicks > 0) {
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                while (j < lines.length) {
                    String candidate = lines[j].trim();
                    int ticks = leadingBackticks(candidate);
                    boolean bareFence = ticks == candidate.length();
                    if (bareFence && ticks >= openingTicks) break;

                    if (code.length() > 0) code.append('\n');
                    code.append(lines[j]);
                    j++;
                }
                if (out.length() > 0) out.append('\n');
                int start = out.length();
                out.append(code);
                spans.add(new Span(start, out.length(), Type.CODE_BLOCK, 0, null));
                i = (j < lines.length) ? j + 1 : j; // skip the closing fence
                continue;
            }

            if (out.length() > 0) out.append('\n');

            // heading: 1..6 '#' followed by a space
            int h = 0;
            while (h < trimmed.length() && trimmed.charAt(h) == '#') h++;
            if (h >= 1 && h <= 6 && h < trimmed.length() && trimmed.charAt(h) == ' ') {
                int start = out.length();
                inline(trimmed.substring(h + 1).trim(), out, spans);
                spans.add(new Span(start, out.length(), Type.HEADING, h, null));
                i++;
                continue;
            }

            // bullet list: -, * or + followed by a space
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                out.append("•  ");
                inline(trimmed.substring(2).trim(), out, spans);
                i++;
                continue;
            }

            // numbered list: digits, a dot, then a space — keep the number
            int d = 0;
            while (d < trimmed.length() && Character.isDigit(trimmed.charAt(d))) d++;
            if (d > 0 && d + 1 < trimmed.length()
                    && trimmed.charAt(d) == '.' && trimmed.charAt(d + 1) == ' ') {
                out.append(trimmed, 0, d + 2);
                inline(trimmed.substring(d + 2).trim(), out, spans);
                i++;
                continue;
            }

            // plain paragraph line (blank lines fall through as empty)
            inline(line, out, spans);
            i++;
        }

        return new Doc(out.toString(), spans);
    }

    /** Return the leading backtick count when it forms a fence. */
    private static int leadingBackticks(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == '`') count++;
        return count >= 3 ? count : 0;
    }

    /** Return the index immediately after a complete backtick run. */
    private static int backtickRunEnd(String value, int start) {
        int end = start;
        while (end < value.length() && value.charAt(end) == '`') end++;
        return end;
    }

    private static boolean isAllSpaces(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != ' ') return false;
        }
        return true;
    }

    /** Parse inline markers within {@code s}, appending text and spans. */
    private static void inline(String s, StringBuilder out, List<Span> spans) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            // inline code `...` — delimiter runs must have equal lengths
            if (c == '`') {
                int openingEnd = backtickRunEnd(s, i);
                int delimiterLength = openingEnd - i;
                int closingStart = openingEnd;
                boolean closed = false;
                while (closingStart < n) {
                    if (s.charAt(closingStart) != '`') {
                        closingStart++;
                        continue;
                    }
                    int closingEnd = backtickRunEnd(s, closingStart);
                    if (closingEnd - closingStart == delimiterLength) {
                        String content = s.substring(openingEnd, closingStart);
                        if (content.length() >= 2
                                && content.charAt(0) == ' '
                                && content.charAt(content.length() - 1) == ' '
                                && !isAllSpaces(content)) {
                            content = content.substring(1, content.length() - 1);
                        }
                        int start = out.length();
                        out.append(content);
                        spans.add(new Span(start, out.length(), Type.CODE, 0, null));
                        i = closingEnd;
                        closed = true;
                        break;
                    }
                    closingStart = closingEnd;
                }
                if (closed) continue;
                out.append(s, i, openingEnd);
                i = openingEnd;
                continue;
            }

            // link [text](url)
            if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i && close + 1 < n && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > close) {
                        int start = out.length();
                        inline(s.substring(i + 1, close), out, spans);
                        spans.add(new Span(start, out.length(), Type.LINK, 0,
                                s.substring(close + 2, paren)));
                        i = paren + 1;
                        continue;
                    }
                }
            }

            // emphasis: ** / __ (bold), ~~ (strike), * / _ (italic)
            if (c == '*' || c == '_' || c == '~') {
                boolean dbl = i + 1 < n && s.charAt(i + 1) == c;
                boolean ok = c != '~' || dbl; // a lone '~' is not a marker
                // underscores inside words (snake_case) are not emphasis
                if (c == '_' && !(i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                    ok = false;
                }
                if (ok) {
                    String delim = dbl ? ("" + c + c) : ("" + c);
                    Type type = dbl ? (c == '~' ? Type.STRIKE : Type.BOLD) : Type.ITALIC;
                    int from = i + delim.length();
                    int close = s.indexOf(delim, from);
                    if (close >= from) {
                        int start = out.length();
                        inline(s.substring(from, close), out, spans);
                        spans.add(new Span(start, out.length(), type, 0, null));
                        i = close + delim.length();
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
    }

    /* ---------------------------------------------------------------- */
    /* rendering (Android)                                              */
    /* ---------------------------------------------------------------- */

    // larger text for higher-level headings
    private static final float[] HEADING_SCALE = {1.5f, 1.3f, 1.15f, 1.08f, 1.0f, 1.0f};

    static CharSequence render(Context ctx, String md, int baseColor) {
        Doc doc = parse(md);
        SpannableStringBuilder sb = new SpannableStringBuilder(doc.text);
        int len = sb.length();
        for (Span s : doc.spans) {
            if (s.start < 0 || s.end > len || s.start >= s.end) continue;
            int st = s.start;
            int en = s.end;
            switch (s.type) {
                case BOLD:
                    span(sb, new StyleSpan(Typeface.BOLD), st, en);
                    break;
                case ITALIC:
                    span(sb, new StyleSpan(Typeface.ITALIC), st, en);
                    break;
                case STRIKE:
                    span(sb, new StrikethroughSpan(), st, en);
                    break;
                case CODE:
                    span(sb, new TypefaceSpan("monospace"), st, en);
                    span(sb, new BackgroundColorSpan(0x33000000), st, en);
                    span(sb, new ForegroundColorSpan(Theme.ACCENT_STRONG), st, en);
                    break;
                case CODE_BLOCK:
                    span(sb, new TypefaceSpan("monospace"), st, en);
                    span(sb, new BackgroundColorSpan(0x40000000), st, en);
                    span(sb, new RelativeSizeSpan(0.92f), st, en);
                    break;
                case HEADING:
                    span(sb, new StyleSpan(Typeface.BOLD), st, en);
                    span(sb, new RelativeSizeSpan(
                            HEADING_SCALE[Math.max(1, Math.min(6, s.level)) - 1]), st, en);
                    break;
                case LINK:
                    span(sb, new ForegroundColorSpan(Theme.INFO), st, en);
                    span(sb, new UnderlineSpan(), st, en);
                    break;
            }
        }
        return sb;
    }

    private static void span(SpannableStringBuilder sb, Object what, int start, int end) {
        sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
