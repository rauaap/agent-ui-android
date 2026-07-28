package com.agentui.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Foreground service that watches sessions for task completion even when no
 * activity is on screen. For each watched session it holds its own WebSocket
 * (independent of {@link SessionActivity}'s live connection) and posts a
 * notification when the session transitions from a busy state back to idle.
 *
 * <p>Watching is opt-in per session via the bell toggle in the session header,
 * so the service only runs while at least one session is being watched.
 *
 * <p>All mutable state is touched on the main thread; OkHttp callbacks hop onto
 * {@link #handler} before doing anything.
 */
public class WatchService extends Service {

    static final String ACTION_WATCH = "com.agentui.app.WATCH";
    static final String ACTION_UNWATCH = "com.agentui.app.UNWATCH";
    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_STATUS = "status";

    private static final String CHANNEL_ONGOING = "watch_ongoing";
    private static final String CHANNEL_DONE = "task_done";
    private static final int FG_NOTIF_ID = 1;
    private static final int MAX_RECONNECTS = 5;

    /** Session currently visible on screen; completions for it are not notified. */
    private static volatile String viewing;

    static void setViewing(String id) { viewing = id; }

    static void clearViewing(String id) {
        if (id != null && id.equals(viewing)) viewing = null;
    }

    static void watch(Context ctx, String id, String name, String status) {
        if (id == null) return;
        Intent i = new Intent(ctx, WatchService.class).setAction(ACTION_WATCH);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_NAME, name);
        i.putExtra(EXTRA_STATUS, status);
        ctx.startForegroundService(i);
    }

    static void unwatch(Context ctx, String id) {
        if (id == null) return;
        Intent i = new Intent(ctx, WatchService.class).setAction(ACTION_UNWATCH);
        i.putExtra(EXTRA_ID, id);
        ctx.startForegroundService(i);
    }

    private static final class Watch {
        WebSocket socket;
        String name = "Session";
        boolean armed;      // saw a busy state -> a completion is expected
        boolean approvalNotified; // already alerted for the current approval pause
        int attempt;        // reconnect attempts since last clean message
        boolean closing;    // we asked the socket to close; ignore its callbacks
    }

    private final Map<String, Watch> watches = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OkHttpClient http;
    private Prefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        http = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A foreground service must promote itself promptly after being started.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIF_ID, ongoingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_NOTIF_ID, ongoingNotification());
        }

        if (intent != null && ACTION_WATCH.equals(intent.getAction())) {
            startWatch(intent.getStringExtra(EXTRA_ID),
                    intent.getStringExtra(EXTRA_NAME),
                    intent.getStringExtra(EXTRA_STATUS));
        } else if (intent != null && ACTION_UNWATCH.equals(intent.getAction())) {
            stopWatch(intent.getStringExtra(EXTRA_ID), true);
        }

        stopIfIdle();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        for (Watch w : watches.values()) {
            w.closing = true;
            if (w.socket != null) w.socket.close(1000, null);
        }
        watches.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ----------------------------------------------------------------- */
    /* watch lifecycle                                                   */
    /* ----------------------------------------------------------------- */

    private void startWatch(String id, String name, String status) {
        if (id == null) return;
        Watch w = watches.get(id);
        if (w == null) {
            w = new Watch();
            watches.put(id, w);
        }
        if (name != null && !name.isEmpty()) w.name = name;
        if (isBusy(status)) w.armed = true;
        if (w.socket == null) connect(id, w);
        updateOngoing();
    }

    private void stopWatch(String id, boolean cancelNotification) {
        if (id == null) return;
        Watch w = watches.remove(id);
        if (w != null) {
            w.closing = true;
            if (w.socket != null) w.socket.close(1000, null);
        }
        if (cancelNotification) notifManager().cancel(doneNotifId(id));
        updateOngoing();
    }

    private void connect(String id, Watch w) {
        w.closing = false;
        Request req = new Request.Builder()
                .url(prefs.wsBase() + "/ws/sessions/" + id)
                .build();
        w.socket = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                handler.post(() -> onStatus(id, ws, text));
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                handler.post(() -> onDrop(id, ws));
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                handler.post(() -> onDrop(id, ws));
            }
        });
    }

    private void onStatus(String id, WebSocket ws, String raw) {
        Watch w = watches.get(id);
        if (w == null || ws != w.socket) return;
        w.attempt = 0;

        String type;
        String status;
        try {
            JSONObject msg = new JSONObject(raw);
            type = msg.optString("type", "");
            status = msg.optString("status", "");
        } catch (Exception e) {
            return;
        }
        if (!"status".equals(type)) return;

        if ("awaiting_approval".equals(status)) {
            w.armed = true;
            if (!w.approvalNotified && !id.equals(viewing)) {
                notifyAlert(id, w.name, "Needs your approval");
                w.approvalNotified = true;
            }
        } else if ("running".equals(status)) {
            w.armed = true;
            w.approvalNotified = false;
            notifManager().cancel(doneNotifId(id));
        } else if (w.armed) {
            // Busy -> idle: the turn finished.
            w.armed = false;
            if (!id.equals(viewing)) notifyAlert(id, w.name, "Task complete");
            // Nothing more to wait for; drop the watch until the next prompt.
            stopWatch(id, false);
        }
    }

    private void onDrop(String id, WebSocket ws) {
        Watch w = watches.get(id);
        if (w == null || ws != w.socket || w.closing) return;
        w.socket = null;
        if (w.armed && w.attempt < MAX_RECONNECTS) {
            long delay = Math.min(1000L * (1L << w.attempt), 10000L);
            w.attempt++;
            handler.postDelayed(() -> {
                Watch cur = watches.get(id);
                if (cur == w && cur.socket == null) connect(id, cur);
            }, delay);
        } else {
            watches.remove(id);
            updateOngoing();
        }
    }

    private static boolean isBusy(String status) {
        return "running".equals(status) || "awaiting_approval".equals(status);
    }

    /* ----------------------------------------------------------------- */
    /* notifications                                                     */
    /* ----------------------------------------------------------------- */

    private void createChannels() {
        NotificationManager nm = notifManager();
        NotificationChannel ongoing = new NotificationChannel(
                CHANNEL_ONGOING, "Watching sessions", NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("Shown while waiting for watched agent tasks to finish.");
        ongoing.setShowBadge(false);
        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE, "Task alerts", NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("A watched agent task finished or needs your approval.");
        nm.createNotificationChannel(ongoing);
        nm.createNotificationChannel(done);
    }

    private Notification ongoingNotification() {
        int n = watches.size();
        String text = n <= 1 ? "Watching for task completion"
                : "Watching " + n + " sessions";
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, ProjectListActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ONGOING)
                .setContentTitle("Agent UI")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateOngoing() {
        if (watches.isEmpty()) {
            stopIfIdle();
        } else {
            notifManager().notify(FG_NOTIF_ID, ongoingNotification());
        }
    }

    private void notifyAlert(String id, String name, String text) {
        Intent open = new Intent(this, SessionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SessionActivity.EXTRA_ID, id)
                .putExtra(SessionActivity.EXTRA_NAME, name);
        PendingIntent pi = PendingIntent.getActivity(this, id.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_DONE)
                .setContentTitle(name)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        notifManager().notify(doneNotifId(id), n);
    }

    private static int doneNotifId(String id) {
        // Keep clear of FG_NOTIF_ID and make it stable per session.
        return 100 + (id.hashCode() & 0x7fffff);
    }

    private NotificationManager notifManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private void stopIfIdle() {
        if (!watches.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }
}
