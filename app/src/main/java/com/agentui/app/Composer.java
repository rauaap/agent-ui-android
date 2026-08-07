package com.agentui.app;

/**
 * What a line of composer text means: a prompt for the agent, or a shell
 * command for bash mode.
 *
 * The leading {@code !} is the <em>client's</em> syntax — the server never
 * inspects prompt text, it only honours a {@code bash} message — so {@code \!}
 * is how a prompt that genuinely starts with an exclamation mark stays
 * sendable. The escape is positional: first character only, and only in front
 * of a {@code !}.
 *
 * Pure and framework-free so it can be unit tested on the JVM, like
 * {@link LineDiff} and {@link Markdown}.
 */
final class Composer {

    /** True when this line runs in the shell instead of going to the agent. */
    final boolean bash;
    /** The command (bash mode) or the prompt, trimmed. */
    final String text;

    private Composer(boolean bash, String text) {
        this.bash = bash;
        this.text = text;
    }

    /**
     * @return what to send, or {@code null} when nothing was typed. A bare
     *     {@code !} parses as bash mode with an empty command, so the composer
     *     can restyle itself the moment the key is pressed; deciding there is
     *     nothing to run yet is the caller's job.
     */
    static Composer parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("!")) return new Composer(true, text.substring(1).trim());
        if (text.startsWith("\\!")) return new Composer(false, text.substring(1));
        return new Composer(false, text);
    }

    /** Whether the composer should be showing its command styling. */
    static boolean isBash(String raw) {
        Composer parsed = parse(raw);
        return parsed != null && parsed.bash;
    }
}
