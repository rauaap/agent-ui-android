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
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small Markdown subset for agent messages: headings, bold,
 * italic, strikethrough, inline code, fenced code blocks, bullet/numbered
 * lists, links and pipe tables.
 *
 * <p>Tables are parsed into {@link Table}s of per-cell docs; {@link #renderInto}
 * shows each as a grid of cell views in its own sideways-scrolling box. Fenced
 * code blocks likewise get their own card, headed by the fence's language and
 * a button that copies just that block.
 *
 * <p>{@link #parse} is pure (no Android types) so it can be unit-tested on the
 * plain JVM, mirroring {@link LineDiff}. It strips the markers and emits the
 * visible text plus a list of {@link Span}s describing what to style where —
 * the visible text is what the platform copies when you select it, so a
 * selection yields clean prose. {@link #renderInto} turns that into styled
 * views; the raw source is kept elsewhere for the "Copy" button.
 */
final class Markdown {
    private Markdown() {}

    enum Type { BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, HEADING, LINK, TABLE }

    /** Stands in for a table in {@link Doc#text}; a TABLE span covers it. */
    static final char TABLE_MARK = '￼';

    /** Column alignment from a table delimiter row. */
    enum Align { NONE, LEFT, CENTER, RIGHT }

    /** A styling instruction over [start, end) of the parsed text. */
    static final class Span {
        final int start;
        final int end;
        final Type type;
        final int level;   // heading level (1..6); 0 otherwise
        final String href; // link target; null otherwise
        final String lang; // code block language; null otherwise or if unnamed

        Span(int start, int end, Type type, int level, String href) {
            this(start, end, type, level, href, null);
        }

        Span(int start, int end, Type type, int level, String href, String lang) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.level = level;
            this.href = href;
            this.lang = lang;
        }
    }

    /**
     * The visible text with the markers removed, plus the spans over it. Each
     * table is a single {@link #TABLE_MARK} in the text; the TABLE spans, in
     * order, correspond to {@link #tables}.
     */
    static final class Doc {
        final String text;
        final List<Span> spans;
        final List<Table> tables;

        Doc(String text, List<Span> spans) {
            this(text, spans, new ArrayList<>());
        }

        Doc(String text, List<Span> spans, List<Table> tables) {
            this.text = text;
            this.spans = spans;
            this.tables = tables;
        }
    }

    /** A parsed table: per-column alignments and rows of parsed cells. */
    static final class Table {
        final List<Align> aligns;
        final List<Doc[]> rows; // the header first; each row has aligns.size() cells

        Table(List<Align> aligns, List<Doc[]> rows) {
            this.aligns = aligns;
            this.rows = rows;
        }
    }

    /* ---------------------------------------------------------------- */
    /* parsing (pure)                                                   */
    /* ---------------------------------------------------------------- */

    static Doc parse(String md) {
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        List<Table> tables = new ArrayList<>();
        if (md == null) md = "";
        String[] lines = md.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // fenced code block ``` ... ```
            int openingTicks = leadingBackticks(trimmed);
            if (openingTicks > 0) {
                // the info string's first word names the language: ```kotlin
                String info = trimmed.substring(openingTicks).trim();
                String lang = info.isEmpty() ? null : info.split("\\s+", 2)[0];
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
                spans.add(new Span(start, out.length(), Type.CODE_BLOCK, 0, null, lang));
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

            // table: a piped header row directly followed by a delimiter row with
            // the same number of columns. Body rows run until a blank or pipeless line.
            List<String> header = trimmed.indexOf('|') >= 0 ? splitRow(trimmed) : null;
            List<Align> aligns = header != null && i + 1 < lines.length
                    ? delimiterRow(lines[i + 1]) : null;
            if (aligns != null && aligns.size() == header.size()) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(header);
                int j = i + 2;
                while (j < lines.length && lines[j].indexOf('|') >= 0 && !lines[j].trim().isEmpty()) {
                    rows.add(splitRow(lines[j].trim()));
                    j++;
                }
                tables.add(table(rows, aligns));
                spans.add(new Span(out.length(), out.length() + 1, Type.TABLE, 0, null));
                out.append(TABLE_MARK);
                i = j;
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

        return new Doc(out.toString(), spans, tables);
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

    /**
     * Split a trimmed table row into raw cell texts. Leading and trailing pipes
     * are optional. {@code \|} is a literal pipe; unlike GFM, pipes inside a
     * closed code span do not split either, since agents rarely escape them there.
     */
    static List<String> splitRow(String row) {
        String s = row.startsWith("|") ? row.substring(1) : row;
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean endedOnPipe = false;

        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            endedOnPipe = false;
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                cur.append('|');
                i += 2;
            } else if (c == '`') {
                int runEnd = backtickRunEnd(s, i);
                int run = runEnd - i;
                int close = findRun(s, run, runEnd);
                int end = close < 0 ? runEnd : close + run;
                cur.append(s.substring(i, end).replace("\\|", "|"));
                i = end;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
                endedOnPipe = true;
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }

        if (!endedOnPipe) cells.add(cur.toString().trim());
        return cells;
    }

    /** Index of a backtick run of exactly {@code run} ticks at or after {@code from}, or -1. */
    private static int findRun(String s, int run, int from) {
        int search = from;
        while (search < s.length()) {
            int start = s.indexOf('`', search);
            if (start < 0) return -1;
            int end = backtickRunEnd(s, start);
            if (end - start == run) return start;
            search = end;
        }
        return -1;
    }

    /**
     * Parse a table delimiter row such as {@code | :--- | :-: | --: |} into
     * per-column alignments, or null if it is not one.
     */
    static List<Align> delimiterRow(String line) {
        String trimmed = line.trim();
        if (trimmed.indexOf('|') < 0) return null;
        List<Align> aligns = new ArrayList<>();
        for (String c : splitRow(trimmed)) {
            if (!c.matches(":?-+:?")) return null;
            boolean left = c.startsWith(":");
            boolean right = c.endsWith(":");
            if (left && right) aligns.add(Align.CENTER);
            else if (right) aligns.add(Align.RIGHT);
            else aligns.add(left ? Align.LEFT : Align.NONE);
        }
        return aligns;
    }

    /**
     * Parse each raw cell's inline markup. Ragged rows are padded and truncated
     * to the header's column count.
     */
    private static Table table(List<List<String>> rows, List<Align> aligns) {
        int cols = aligns.size();
        List<Doc[]> cells = new ArrayList<>();
        for (List<String> row : rows) {
            Doc[] parsed = new Doc[cols];
            for (int c = 0; c < cols; c++) {
                StringBuilder text = new StringBuilder();
                List<Span> cellSpans = new ArrayList<>();
                if (c < row.size()) inline(row.get(c), text, cellSpans);
                parsed[c] = new Doc(text.toString(), cellSpans);
            }
            cells.add(parsed);
        }
        return new Table(aligns, cells);
    }

    /** A run of the document shown in its own view: prose, one table, or one code block. */
    static final class Block {
        final Doc doc;     // prose; null otherwise
        final Table table; // a table; null otherwise
        final String code; // a code block's text; null otherwise
        final String lang; // the code block's language; null if unnamed

        Block(Doc doc, Table table, String code, String lang) {
            this.doc = doc;
            this.table = table;
            this.code = code;
            this.lang = lang;
        }
    }

    /**
     * Split a parsed doc at its tables and code blocks. Prose blocks drop the
     * newlines at their edges, since the views they land in are spaced apart
     * already; prose that is only newlines is dropped entirely. Code blocks are
     * kept even when empty, so a fence shows its card as soon as it opens.
     */
    static List<Block> blocks(Doc doc) {
        List<Block> out = new ArrayList<>();
        int pos = 0;
        int t = 0;
        for (Span s : doc.spans) {
            if (s.type == Type.TABLE) {
                prose(doc, pos, s.start, out);
                out.add(new Block(null, doc.tables.get(t++), null, null));
                pos = s.end;
            } else if (s.type == Type.CODE_BLOCK) {
                prose(doc, pos, s.start, out);
                out.add(new Block(null, null, doc.text.substring(s.start, s.end), s.lang));
                pos = s.end;
            }
        }
        prose(doc, pos, doc.text.length(), out);
        return out;
    }

    private static void prose(Doc doc, int start, int end, List<Block> out) {
        while (start < end && doc.text.charAt(start) == '\n') start++;
        while (end > start && doc.text.charAt(end - 1) == '\n') end--;
        if (start < end) out.add(new Block(slice(doc, start, end), null, null, null));
    }

    /** The text in [start, end) with the spans inside it, rebased to 0. */
    private static Doc slice(Doc doc, int start, int end) {
        List<Span> spans = new ArrayList<>();
        for (Span s : doc.spans) {
            int st = Math.max(s.start, start);
            int en = Math.min(s.end, end);
            if (st < en) spans.add(new Span(st - start, en - start, s.type, s.level, s.href));
        }
        return new Doc(doc.text.substring(start, end), spans);
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

    private enum Kind { PROSE, TABLE, CODE }

    private static Kind kind(Block block) {
        if (block.table != null) return Kind.TABLE;
        return block.code != null ? Kind.CODE : Kind.PROSE;
    }

    private static Kind kind(View view) {
        if (view instanceof HorizontalScrollView) return Kind.TABLE;
        return view instanceof TextView ? Kind.PROSE : Kind.CODE;
    }

    /**
     * Render {@code md} into {@code box} (a vertical {@link LinearLayout}): prose
     * in selectable text views, each table as a grid of cell views in its own
     * sideways-scrolling box, and each code block as a card. Existing views are
     * reused while their kind still matches, so re-rendering as chunks stream in
     * keeps a table's or code block's scroll offset.
     */
    static void renderInto(LinearLayout box, String md) {
        Context ctx = box.getContext();
        List<Block> blocks = blocks(parse(md));
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            Kind kind = kind(block);
            View view = b < box.getChildCount() ? box.getChildAt(b) : null;
            if (view == null || kind(view) != kind) {
                if (view != null) box.removeViewAt(b);
                switch (kind) {
                    case TABLE: view = tableView(ctx); break;
                    case CODE: view = codeView(ctx); break;
                    default: view = proseView(ctx); break;
                }
                box.addView(view, b);
            }
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
            int top = b > 0 ? Theme.dp(ctx, 8) : 0;
            if (lp.topMargin != top) {
                lp.topMargin = top;
                view.setLayoutParams(lp);
            }
            switch (kind) {
                case TABLE:
                    bindTable((TableLayout) ((HorizontalScrollView) view).getChildAt(0), block.table);
                    break;
                case CODE:
                    bindCode((LinearLayout) view, block);
                    break;
                default:
                    ((TextView) view).setText(style(block.doc));
                    break;
            }
        }
        while (box.getChildCount() > blocks.size()) box.removeViewAt(box.getChildCount() - 1);
    }

    /**
     * A code block's card: a header with the language and a copy button, a
     * divider, then the code in a box that scrolls sideways rather than wrapping.
     */
    private static LinearLayout codeView(Context ctx) {
        LinearLayout card = Widgets.column(ctx);
        card.setBackground(Theme.rounded(ctx, Theme.BG, 10, Theme.LINE, 1));
        card.setClipToOutline(true);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout header = Widgets.row(ctx);
        header.setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 2), Theme.dp(ctx, 4), Theme.dp(ctx, 2));
        TextView lang = Widgets.mono(ctx, "", Theme.MUTED, 11.5f);
        header.addView(lang, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = Widgets.text(ctx, "COPY", Theme.FAINT, 11, true);
        copy.setLetterSpacing(0.06f);
        copy.setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 6), Theme.dp(ctx, 8), Theme.dp(ctx, 6));
        copy.setClickable(true);
        copy.setFocusable(true);
        header.addView(copy);
        card.addView(header);

        View divider = new View(ctx);
        divider.setBackgroundColor(Theme.LINE);
        card.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, hairline(ctx)));

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        TextView code = Widgets.mono(ctx, "", Theme.INK, 13);
        code.setTextIsSelectable(true);
        int h = Theme.dp(ctx, 12);
        int v = Theme.dp(ctx, 10);
        code.setPadding(h, v, h, v);
        scroll.addView(code, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // copies what is shown, so it keeps up as the block streams in
        copy.setOnClickListener(x ->
                Widgets.copy(ctx, "code", code.getText().toString(), "Copied"));
        return card;
    }

    private static void bindCode(LinearLayout card, Block block) {
        LinearLayout header = (LinearLayout) card.getChildAt(0);
        ((TextView) header.getChildAt(0)).setText(block.lang == null ? "" : block.lang);
        HorizontalScrollView scroll = (HorizontalScrollView) card.getChildAt(2);
        ((TextView) scroll.getChildAt(0)).setText(block.code);
    }

    private static TextView proseView(Context ctx) {
        TextView t = Widgets.text(ctx, "", Theme.INK, 15, false);
        t.setTextIsSelectable(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /**
     * A box that scrolls a table sideways. It wraps its content but, as a
     * WRAP_CONTENT child of the message column, is capped at the column's width;
     * only the grid inside is measured unbounded.
     *
     * <p>The grid draws its borders by painting the line colour behind cells
     * that are inset 1px from each other: the grid pads its top and left edges,
     * each cell margins its right and bottom.
     */
    private static HorizontalScrollView tableView(Context ctx) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        TableLayout grid = new TableLayout(ctx);
        grid.setBackgroundColor(Theme.LINE);
        int line = hairline(ctx);
        grid.setPadding(line, line, 0, 0);
        scroll.addView(grid, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Fill {@code grid} from {@code table}, reusing rows whose column count still fits. */
    private static void bindTable(TableLayout grid, Table table) {
        Context ctx = grid.getContext();
        int cols = table.aligns.size();
        for (int r = 0; r < table.rows.size(); r++) {
            TableRow row = r < grid.getChildCount() ? (TableRow) grid.getChildAt(r) : null;
            if (row == null || row.getChildCount() != cols) {
                if (row != null) grid.removeViewAt(r);
                row = new TableRow(ctx);
                for (int c = 0; c < cols; c++) row.addView(cellView(ctx, r == 0));
                grid.addView(row, r);
            }
            Doc[] cells = table.rows.get(r);
            for (int c = 0; c < cols; c++) {
                TextView cell = (TextView) row.getChildAt(c);
                cell.setText(style(cells[c]));
                cell.setGravity(Gravity.CENTER_VERTICAL | gravity(table.aligns.get(c)));
            }
        }
        while (grid.getChildCount() > table.rows.size()) grid.removeViewAt(grid.getChildCount() - 1);
    }

    private static TextView cellView(Context ctx, boolean header) {
        TextView t = Widgets.text(ctx, "", Theme.INK, 14, header);
        t.setTextIsSelectable(true);
        t.setBackgroundColor(header ? Theme.PANEL_HOVER : Theme.PANEL);
        // long cells wrap inside their column instead of stretching the table
        t.setMaxWidth(Theme.dp(ctx, 280));
        int h = Theme.dp(ctx, 10);
        int v = Theme.dp(ctx, 6);
        t.setPadding(h, v, h, v);
        TableRow.LayoutParams lp = new TableRow.LayoutParams(
                TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT);
        int line = hairline(ctx);
        lp.setMargins(0, 0, line, line);
        t.setLayoutParams(lp);
        return t;
    }

    private static int gravity(Align align) {
        switch (align) {
            case CENTER: return Gravity.CENTER_HORIZONTAL;
            case RIGHT: return Gravity.END;
            default: return Gravity.START;
        }
    }

    private static int hairline(Context ctx) {
        return Math.max(1, Theme.dp(ctx, 1));
    }

    private static CharSequence style(Doc doc) {
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
                case HEADING:
                    span(sb, new StyleSpan(Typeface.BOLD), st, en);
                    span(sb, new RelativeSizeSpan(
                            HEADING_SCALE[Math.max(1, Math.min(6, s.level)) - 1]), st, en);
                    break;
                case LINK:
                    span(sb, new ForegroundColorSpan(Theme.INFO), st, en);
                    span(sb, new UnderlineSpan(), st, en);
                    break;
                case CODE_BLOCK:
                case TABLE:
                    break; // rendered as its own view, see renderInto
            }
        }
        return sb;
    }

    private static void span(SpannableStringBuilder sb, Object what, int start, int end) {
        sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
