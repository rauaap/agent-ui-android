package com.agentui.app;

import android.content.Context;
import android.content.res.Resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Suggests a session name like {@code able-blink}, so creating a session is a
 * tap rather than a bout of typing on a touchscreen keyboard.
 *
 * <p>Names are not unique and are not meant to be: sessions are identified by
 * id, duplicates are harmless, and checking for them would put a round trip in
 * front of the very interaction this exists to shorten.
 */
final class NameGenerator {
    private NameGenerator() {}

    private static final Random RANDOM = new Random();

    // Loaded once and kept — a few KB of short strings, read on the main thread
    // when the new-session dialog opens.
    private static List<String> adjectives;
    private static List<String> names;

    /** e.g. {@code "quiet-harbor"}, or {@code "session"} if the lists are unusable. */
    static String suggest(Context ctx) {
        if (adjectives == null) adjectives = load(ctx, R.raw.adjectives);
        if (names == null) names = load(ctx, R.raw.names);
        if (adjectives.isEmpty() || names.isEmpty()) return "session";
        return pick(adjectives) + "-" + pick(names);
    }

    private static String pick(List<String> words) {
        return words.get(RANDOM.nextInt(words.size()));
    }

    private static List<String> load(Context ctx, int resourceId) {
        List<String> words = new ArrayList<>();
        try (InputStream in = ctx.getResources().openRawResource(resourceId);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) words.add(word);
            }
        } catch (IOException | Resources.NotFoundException e) {
            // A missing word list must not stop anyone creating a session;
            // suggest() falls back to a fixed name.
        }
        return words;
    }
}
