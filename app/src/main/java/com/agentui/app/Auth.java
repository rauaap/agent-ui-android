package com.agentui.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Process-wide authentication gate. Secrets never enter intents or diagnostics. */
public final class Auth extends Application implements Application.ActivityLifecycleCallbacks {
    private static Auth instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean rejected;
    private volatile boolean settingsOpen;
    private Activity foreground;
    private final java.util.Set<Runnable> resumptions = new java.util.HashSet<>();
    static void whenReady(Runnable action) { instance.resumptions.add(action); }
    static void forget(Runnable action) { instance.resumptions.remove(action); }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(this);
    }

    static boolean ready() {
        return instance != null && !instance.rejected && !instance.settingsOpen
                && !new Prefs(instance).token().isEmpty();
    }

    static OkHttpClient client(android.content.Context context) {
        Prefs prefs = new Prefs(context);
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor(new TokenInterceptor(Auth::ready, prefs::token,
                        token -> reject(prefs, token), token -> check(prefs.httpBase(), token)))
                .build();
    }

    /** Dedicated validation transport, deliberately bypassing the saved-token gate. */
    static int check(String base, String token) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
        try {
            Request request = new Request.Builder().url(base + "/agents")
                    .header("Authorization", "Bearer " + token).build();
            try (Response response = client.newCall(request).execute()) { return response.code(); }
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private static void reject(Prefs prefs, String token) {
        if (!token.equals(prefs.token())) return; // Ignore an obsolete request after replacement.
        instance.rejected = true;
        instance.main.post(instance::prompt);
    }

    private void prompt() {
        if (foreground == null || foreground instanceof SettingsActivity || settingsOpen) return;
        settingsOpen = true;
        foreground.startActivity(new Intent(foreground, SettingsActivity.class));
    }

    static boolean rejected() { return instance.rejected; }
    static void saved() { instance.rejected = false; }

    @Override public void onActivityResumed(Activity a) {
        foreground = a;
        settingsOpen = a instanceof SettingsActivity;
        if (!settingsOpen && !ready()) prompt();
        else if (ready()) {
            for (Runnable action : new java.util.ArrayList<>(resumptions)) action.run();
        }
    }
    @Override public void onActivityPaused(Activity a) { if (foreground == a) foreground = null; }
    @Override public void onActivityDestroyed(Activity a) { }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) {
        settingsOpen = a instanceof SettingsActivity;
    }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
}
