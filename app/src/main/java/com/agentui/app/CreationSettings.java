package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.function.Consumer;

/** Fresh defaults for one creation form, with cancellation and explicit retry. */
final class CreationSettings {
    private CreationSettings() {}

    static void load(Activity activity, Api api, Consumer<ServerSettings> ready) {
        AlertDialog loading = new AlertDialog.Builder(activity)
                .setMessage("Loading server settings…")
                .setNegativeButton("Cancel", null).create();
        loading.show();
        api.getSettings(new Api.Cb<ServerSettings>() {
            @Override public void onResult(ServerSettings settings) {
                if (!loading.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                loading.dismiss();
                ready.accept(settings);
            }
            @Override public void onError(String message) {
                if (!loading.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                loading.dismiss();
                new AlertDialog.Builder(activity).setTitle("Unable to load server settings")
                        .setMessage(message).setNegativeButton("Cancel", null)
                        .setPositiveButton("Retry", (d, w) -> load(activity, api, ready)).show();
            }
        });
    }
}
