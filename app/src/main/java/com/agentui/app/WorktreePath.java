package com.agentui.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a worktree goes: the path template, its expansion, and the lexical
 * path arithmetic that keeps the result comparable with what the server
 * reports back.
 *
 * <p>The template is purely a client-side idea — the server has never heard of
 * it and only ever receives a finished absolute path. It exists because every
 * worktree needs a directory, and typing one out per branch is busywork:
 * {@code %P/%N-%B} turns branch {@code fix-login} in project
 * {@code /projects/app} into {@code /projects/app-fix-login}.
 *
 * <p>Paths are normalised the way the server normalises them on arrival —
 * lexically, with no symlink resolution — so a path built here matches the one
 * {@code GET /worktrees} hands back, and comparisons against the existing
 * worktrees actually hit.
 *
 * <p>Pure and framework-free so it can be unit tested on the JVM, like
 * {@link Composer}.
 */
final class WorktreePath {
    private WorktreePath() {}

    /**
     * A sibling of the project directory — {@code /projects/app} plus branch
     * {@code fix-login} gives {@code /projects/app-fix-login}. Beside the
     * project rather than inside it, so the worktree is not part of the tree
     * the agent is working on, and still under the same parent, which is what
     * keeps it inside the {@code /projects} bind mount in a container
     * deployment.
     */
    static final String DEFAULT_TEMPLATE = "%P/%N-%B";

    /** Cap on a seeded slug. git allows far more; a path stays readable. */
    private static final int MAX_SLUG = 48;

    /** Used when a name slugs away to nothing — "!!!", or a non-Latin script. */
    private static final String FALLBACK = "session";

    /**
     * A session name reduced to a branch-safe, path-safe token:
     * {@code "Fix login!"} becomes {@code "fix-login"}.
     *
     * <p>Runs of anything else collapse to one dash and never lead or trail, so
     * the result cannot trip {@code git check-ref-format} — which is the
     * authority, server-side, on whatever the user types instead. Nothing here
     * validates a hand-typed branch: git's own rules are the check, and an
     * approximation of them would reject names git accepts.
     */
    static String slug(String name) {
        if (name == null) return FALLBACK;
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < name.length() && out.length() < MAX_SLUG; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!keep) {
                gap = true;
                continue;
            }
            if (gap && out.length() > 0) out.append('-');
            gap = false;
            out.append(c);
        }
        return out.length() == 0 ? FALLBACK : out.toString();
    }

    /**
     * A branch name flattened to a single path segment. Slashes are legal in a
     * ref and common in practice, so {@code feature/fix-login} in {@code %N-%b}
     * would nest the worktree a level deeper than the sibling the user pictured
     * — {@code %B} is the spelling that cannot do that.
     */
    static String branchSlug(String branch) {
        return branch == null ? "" : branch.trim().replace('/', '-');
    }

    /**
     * The template expanded against one project and one branch.
     *
     * <table>
     *   <tr><td>{@code %P}</td><td>the project's parent directory</td></tr>
     *   <tr><td>{@code %N}</td><td>the project directory's last segment</td></tr>
     *   <tr><td>{@code %B}</td><td>the branch, slashes flattened to dashes</td></tr>
     *   <tr><td>{@code %b}</td><td>the branch verbatim</td></tr>
     * </table>
     *
     * <p>{@code %N} is the path's last segment and deliberately not the
     * project's display name, which merely defaults to it and can be anything —
     * "My App", spaces and all, has no business in a path.
     *
     * <p>The result is normalised, which is also what joins the pieces: for a
     * project directly under the root, {@code %P} is {@code /} and
     * {@code %P/%N-%B} would otherwise concatenate to {@code //app-fix}.
     */
    static String expand(String template, String projectPath, String branch) {
        String t = (template == null || template.trim().isEmpty())
                ? DEFAULT_TEMPLATE : template.trim();
        String project = normalize(projectPath);
        String parent = dirname(project);
        String name = basename(project);
        String verbatim = branch == null ? "" : branch.trim();

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '%' || i + 1 == t.length()) {
                out.append(c);
                continue;
            }
            char spec = t.charAt(++i);
            switch (spec) {
                case 'P': out.append(parent); break;
                case 'N': out.append(name); break;
                case 'B': out.append(branchSlug(verbatim)); break;
                case 'b': out.append(verbatim); break;
                case '%': out.append('%'); break;
                // An unknown specifier stays as typed rather than vanishing, so
                // a typo shows up in the previewed path instead of silently
                // shortening it.
                default: out.append('%').append(spec); break;
            }
        }
        return normalize(out.toString());
    }

    /**
     * An absolute path, resolving a relative one against the project directory.
     * The server takes absolute paths only — a relative one is a 400 — and has
     * no notion of "relative to the project", so the expansion happens here.
     */
    static String resolve(String path, String projectDir) {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("/")) return normalize(p);
        String base = normalize(projectDir);
        if (base.isEmpty()) base = "/";
        return normalize(base + "/" + p);
    }

    /**
     * A path collapsed lexically — duplicate slashes, {@code .} and {@code ..}
     * removed without touching the disk. The same treatment the server gives a
     * path on arrival ({@code os.path.normpath}), which is what makes a path
     * built here comparable with one it reports back.
     *
     * <p>Lexical means symlink-blind: {@code /a/link/..} collapses to
     * {@code /a} even if {@code link} points elsewhere. That matches the
     * server, which is the only agreement that matters here.
     */
    static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.isEmpty()) return "";
        boolean absolute = p.startsWith("/");
        List<String> parts = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                int last = parts.size() - 1;
                if (last >= 0 && !parts.get(last).equals("..")) parts.remove(last);
                // Above an absolute root there is nothing to climb to, so the
                // segment is dropped; a relative path keeps it.
                else if (!absolute) parts.add("..");
                continue;
            }
            parts.add(seg);
        }
        StringBuilder out = new StringBuilder();
        for (String seg : parts) {
            if (out.length() > 0) out.append('/');
            out.append(seg);
        }
        if (absolute) return "/" + out;
        return out.length() == 0 ? "." : out.toString();
    }

    /** The path without its last segment; {@code /} for a path directly under it. */
    static String dirname(String path) {
        String p = normalize(path);
        int slash = p.lastIndexOf('/');
        if (slash < 0) return "";
        return slash == 0 ? "/" : p.substring(0, slash);
    }

    /** The path's last segment; empty for {@code /}, which has none. */
    static String basename(String path) {
        String p = normalize(path);
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }
}
