package com.agentui.app;

/**
 * Where a session's git worktree goes, and what its branch is called.
 *
 * Both are seeded from the session name and then editable — the same
 * seed-then-break-the-link pattern the new-project dialog uses for name vs
 * directory. The seed is deliberately conservative: ASCII letters, digits and
 * single dashes, which is both a legal branch name and a path that needs no
 * quoting.
 *
 * Pure and framework-free so it can be unit tested on the JVM, like
 * {@link Composer}.
 */
final class Worktree {
    private Worktree() {}

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
     * authority, server-side, on whatever the user types instead.
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
     * A sibling of the project directory named after the session: project
     * {@code /home/me/app} plus session "fix login" gives
     * {@code /home/me/app-fix-login}.
     *
     * <p>Beside the project rather than inside it, so the worktree is not part
     * of the tree the agent is working on.
     */
    static String pathFor(String projectDir, String name) {
        String base = projectDir == null ? "" : projectDir.trim();
        while (base.length() > 1 && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String slug = slug(name);
        // Nothing to hang a suffix on: neither "" nor "/" has a last segment.
        if (base.isEmpty() || base.equals("/")) return "/" + slug;
        return base + "-" + slug;
    }
}
