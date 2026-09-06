package com.agentui.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The local, authoritative view of a session's file-tree socket, plus the pure
 * matching and shell-token operations used by Bash completion.
 *
 * <p>This class deliberately has no Android dependencies. Large searches can
 * run on a worker thread and the protocol and quoting behavior can be exercised
 * by ordinary JVM tests.
 */
final class FileTreeCompletion {
    static final int DISPLAY_LIMIT = 50;

    static final class ProtocolException extends Exception {
        ProtocolException(String message) { super(message); }
    }

    static final class Candidate {
        final String path;
        final boolean directory;

        Candidate(String path) {
            this.path = path;
            this.directory = path.endsWith("/");
        }
    }

    /** The shell token containing the cursor and its decoded prefix query. */
    static final class Token {
        final int start;
        final int end;
        final String query;

        Token(int start, int end, String query) {
            this.start = start;
            this.end = end;
            this.query = query;
        }

        String replace(String command, String path) {
            return command.substring(0, start) + shellEscape(path) + command.substring(end);
        }

        int cursorAfter(String path) {
            return start + shellEscape(path).length();
        }
    }

    private String generation;
    private long revision = -1;
    /** Original wire spelling to locale-independent lowercase spelling. */
    private final Map<String, String> paths = new HashMap<>();

    synchronized void clear() {
        generation = null;
        revision = -1;
        paths.clear();
    }

    synchronized boolean isReady() { return generation != null; }
    synchronized long revision() { return revision; }
    synchronized String generation() { return generation; }
    synchronized int size() { return paths.size(); }

    synchronized void applySnapshot(String nextGeneration, long nextRevision,
                                    List<String> nextPaths) throws ProtocolException {
        if (nextGeneration == null || nextGeneration.isEmpty()) {
            throw new ProtocolException("Snapshot generation is missing");
        }
        if (nextRevision < 0) throw new ProtocolException("Snapshot revision is negative");
        Map<String, String> replacement = new HashMap<>();
        String previous = null;
        for (String path : nextPaths) {
            validatePath(path);
            if (previous != null && previous.compareTo(path) >= 0) {
                throw new ProtocolException("Snapshot paths are not sorted and unique");
            }
            previous = path;
            replacement.put(path, fold(path));
        }
        paths.clear();
        paths.putAll(replacement);
        generation = nextGeneration;
        revision = nextRevision;
    }

    synchronized void applyPatch(String patchGeneration, long baseRevision,
                                 long nextRevision, List<String> added,
                                 List<String> removed) throws ProtocolException {
        if (generation == null) throw new ProtocolException("Patch arrived before a snapshot");
        if (!generation.equals(patchGeneration)) {
            throw new ProtocolException("Patch generation does not match the snapshot");
        }
        if (baseRevision != revision || nextRevision != baseRevision + 1) {
            throw new ProtocolException("Patch revision is not contiguous");
        }
        validateSorted(added, "added");
        validateSorted(removed, "removed");
        Set<String> removals = new HashSet<>(removed);
        for (String path : added) {
            if (removals.contains(path)) {
                throw new ProtocolException("Patch additions and removals overlap");
            }
        }
        for (String path : removed) paths.remove(path);
        for (String path : added) paths.put(path, fold(path));
        revision = nextRevision;
    }

    /** Return only the leading ranked window; the cache itself remains complete. */
    synchronized List<Candidate> match(String query, int limit) {
        if (generation == null || limit <= 0) return Collections.emptyList();
        String q = fold(query == null ? "" : query);
        List<Ranked> matches = new ArrayList<>();
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            String lower = entry.getValue();
            int boundary = boundary(lower, q);
            if (boundary < 0) continue;
            String withoutSlash = lower.endsWith("/")
                    ? lower.substring(0, lower.length() - 1) : lower;
            String queryWithoutSlash = q.endsWith("/")
                    ? q.substring(0, q.length() - 1) : q;
            boolean exact = withoutSlash.equals(queryWithoutSlash);
            matches.add(new Ranked(entry.getKey(), lower, exact, boundary));
        }
        matches.sort(RANKING);
        int count = Math.min(limit, matches.size());
        List<Candidate> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new Candidate(matches.get(i).path));
        return result;
    }

    private static final class Ranked {
        final String path;
        final String lower;
        final boolean exact;
        final int boundary;

        Ranked(String path, String lower, boolean exact, int boundary) {
            this.path = path;
            this.lower = lower;
            this.exact = exact;
            this.boundary = boundary;
        }
    }

    private static final Comparator<Ranked> RANKING = (a, b) -> {
        int c = Boolean.compare(b.exact, a.exact);
        if (c != 0) return c;
        c = Boolean.compare(a.boundary != 0, b.boundary != 0);
        if (c != 0) return c;
        c = Integer.compare(a.boundary, b.boundary);
        if (c != 0) return c;
        c = Integer.compare(a.path.length(), b.path.length());
        if (c != 0) return c;
        c = a.lower.compareTo(b.lower);
        if (c != 0) return c;
        return a.path.compareTo(b.path);
    };

    private static int boundary(String path, String query) {
        if (query.isEmpty()) return 0;
        if (path.startsWith(query)) return 0;
        int index = path.indexOf("/" + query);
        return index < 0 ? -1 : index + 1;
    }

    private static void validateSorted(List<String> values, String label)
            throws ProtocolException {
        String previous = null;
        for (String path : values) {
            validatePath(path);
            if (previous != null && previous.compareTo(path) >= 0) {
                throw new ProtocolException("Patch " + label + " paths are not sorted and unique");
            }
            previous = path;
        }
    }

    private static void validatePath(String path) throws ProtocolException {
        if (path == null || path.isEmpty() || path.startsWith("/")) {
            throw new ProtocolException("Invalid file-tree path");
        }
        String body = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (body.isEmpty()) throw new ProtocolException("File-tree root must be omitted");
        String[] components = body.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new ProtocolException("Invalid file-tree path component");
            }
        }
    }

    private static String fold(String value) { return value.toLowerCase(Locale.ROOT); }

    /**
     * Locate and decode the Bash token at {@code cursor}. The command's leading
     * {@code !} is client syntax and is never part of the token. Boundaries are
     * unquoted whitespace and shell operators; quotes may be incomplete.
     */
    static Token tokenAtCursor(String text, int cursor) {
        if (text == null) return null;
        cursor = Math.max(0, Math.min(cursor, text.length()));
        int bang = 0;
        while (bang < text.length() && Character.isWhitespace(text.charAt(bang))) bang++;
        if (bang >= text.length() || text.charAt(bang) != '!') return null;
        int commandStart = bang + 1;
        if (cursor < commandStart) return null;

        int start = commandStart;
        Quote quote = Quote.NONE;
        boolean escaped = false;
        for (int i = commandStart; i < cursor; i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote == Quote.SINGLE) {
                if (c == '\'') quote = Quote.NONE;
                continue;
            }
            if (quote == Quote.DOUBLE) {
                if (c == '"') quote = Quote.NONE;
                else if (c == '\\' && i + 1 < text.length()
                        && doubleQuoteEscapes(text.charAt(i + 1))) escaped = true;
                continue;
            }
            if (c == '\\') escaped = true;
            else if (c == '\'') quote = Quote.SINGLE;
            else if (c == '"') quote = Quote.DOUBLE;
            else if (isBoundary(c)) start = i + 1;
        }

        // Continue with the quote and escape state at the cursor to find the
        // complete token. Carrying a trailing backslash matters when the cursor
        // sits between that backslash and the character it protects.
        int end = text.length();
        for (int i = cursor; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote == Quote.SINGLE) {
                if (c == '\'') quote = Quote.NONE;
                continue;
            }
            if (quote == Quote.DOUBLE) {
                if (c == '"') quote = Quote.NONE;
                else if (c == '\\' && i + 1 < text.length()
                        && doubleQuoteEscapes(text.charAt(i + 1))) escaped = true;
                continue;
            }
            if (c == '\\') escaped = true;
            else if (c == '\'') quote = Quote.SINGLE;
            else if (c == '"') quote = Quote.DOUBLE;
            else if (isBoundary(c)) { end = i; break; }
        }
        return new Token(start, end, decodePrefix(text.substring(start, cursor)));
    }

    private enum Quote { NONE, SINGLE, DOUBLE }

    private static boolean isBoundary(char c) {
        return Character.isWhitespace(c) || "|&;()<>".indexOf(c) >= 0;
    }

    private static boolean doubleQuoteEscapes(char c) {
        return c == '$' || c == '`' || c == '"' || c == '\\' || c == '\n';
    }

    private static String decodePrefix(String raw) {
        StringBuilder decoded = new StringBuilder();
        Quote quote = Quote.NONE;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote == Quote.SINGLE) {
                if (c == '\'') quote = Quote.NONE;
                else decoded.append(c);
            } else if (quote == Quote.DOUBLE) {
                if (c == '"') quote = Quote.NONE;
                else if (c == '\\' && i + 1 < raw.length()
                        && doubleQuoteEscapes(raw.charAt(i + 1))) decoded.append(raw.charAt(++i));
                else decoded.append(c);
            } else if (c == '\'') {
                quote = Quote.SINGLE;
            } else if (c == '"') {
                quote = Quote.DOUBLE;
            } else if (c == '\\' && i + 1 < raw.length()) {
                decoded.append(raw.charAt(++i));
            } else {
                decoded.append(c);
            }
        }
        return decoded.toString();
    }

    /** Quote one server-provided relative path as exactly one POSIX shell word. */
    static String shellEscape(String path) {
        if (path == null || path.isEmpty()) return "''";
        boolean safe = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && "_@%+=:,./-".indexOf(c) < 0) {
                safe = false;
                break;
            }
        }
        if (safe) return path;
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
