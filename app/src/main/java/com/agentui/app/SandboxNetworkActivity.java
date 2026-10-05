package com.agentui.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Explicitly saved server-wide TCP exceptions. No project/session scope. */
public class SandboxNetworkActivity extends Activity {
    private Api api;
    private String server;
    private List<SandboxNetworkDestination> draft = new ArrayList<>();
    private String baseline = "[]";
    private boolean loaded;
    private boolean saving;
    private LinearLayout entries;
    private TextView status;
    private TextView add;
    private TextView save;
    private TextView clear;
    private TextView retry;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        server = api.prefs().httpBase();
        setContentView(buildRoot());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        if (state != null && state.getBoolean("loaded") && server.equals(state.getString("server"))) {
            try {
                draft = SandboxNetworkDestination.from(new JSONArray(state.getString("draft")));
                baseline = state.getString("baseline");
                loaded = true;
                render();
                status.setText(isDirty() ? "Unsaved changes" : "");
                return;
            } catch (Exception ignored) { /* Reload unusable saved state. */ }
        }
        load();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("loaded", loaded);
        out.putString("server", server);
        out.putString("draft", SandboxNetworkDestination.toJson(draft).toString());
        out.putString("baseline", baseline);
    }

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);
        int pad = Theme.dp(this, 16);
        LinearLayout header = Widgets.row(this);
        header.setPadding(pad, pad, pad, pad);
        TextView back = button("‹", this::onBackPressed);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        header.addView(back);
        LinearLayout headings = Widgets.column(this);
        headings.setLayoutParams(lp(0, WRAP, 1f));
        headings.addView(Widgets.text(this, "Sandbox network", Theme.INK, 18, true));
        headings.addView(Widgets.text(this, "Server-wide TCP exceptions", Theme.MUTED, 13, false));
        TextView address = Widgets.mono(this, server, Theme.FAINT, 11.5f);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        headings.addView(address);
        header.addView(headings);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        LinearLayout form = Widgets.column(this);
        form.setPadding(pad, pad, pad, pad);
        form.addView(note("Each exception exposes only one TCP port at an exact unicast IPv4 address. "
                + "Other ports and UDP stay blocked. If Gitea and agent-ui-server share an IP, "
                + "allow only the needed service port; add HTTPS and SSH separately."));
        form.addView(note("No hostnames, CIDRs, IPv6, loopback, unspecified, reserved or multicast addresses, "
                + "or 169.254.0.53 (sandbox DNS proxy). The server validates destinations."));
        form.addView(note("Changes apply to newly launched sandboxed turns across all projects and sessions. "
                + "Running turns retain their rules. Empty means no private-network exceptions."));
        form.addView(Widgets.spacer(this, 16));
        entries = Widgets.column(this);
        form.addView(entries);
        add = button("+ Add IP + port", () -> edit(-1, new SandboxNetworkDestination("", 443)));
        form.addView(add);
        form.addView(Widgets.spacer(this, 20));
        status = Widgets.text(this, "", Theme.MUTED, 12.5f, false);
        form.addView(status);
        retry = button("Retry loading", this::load);
        form.addView(retry);
        save = Widgets.primaryButton(this, "Save exceptions");
        save.setLayoutParams(lp(MATCH, WRAP));
        save.setMinimumHeight(Theme.dp(this, 48));
        save.setOnClickListener(v -> save());
        form.addView(save);
        clear = Widgets.dangerButton(this, "Clear all exceptions");
        clear.setLayoutParams(lp(MATCH, WRAP));
        Widgets.margins(clear, 0, Theme.dp(this, 10), 0, 0);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear all exceptions?")
                .setMessage("Remove every server-wide entry? This takes effect only after Save.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
                    draft.clear(); changed();
                }).show());
        form.addView(clear);
        scroll.addView(form);
        root.addView(scroll);
        return root;
    }

    private void load() {
        loaded = false;
        render();
        retry.setVisibility(View.GONE);
        status.setTextColor(Theme.MUTED);
        status.setText("Loading…");
        api.getSandboxNetwork(new Api.StatusCb<List<SandboxNetworkDestination>>() {
            @Override public void onResult(List<SandboxNetworkDestination> result) {
                if (isDestroyed()) return;
                ready(result);
                status.setText("");
            }
            @Override public void onError(String message) { fail(message); }
            @Override public void onHttpError(int code, String message) {
                fail(code == 404 ? "This server does not support sandbox network settings." : message);
            }
        });
    }

    private void ready(List<SandboxNetworkDestination> result) {
        draft = new ArrayList<>(result);
        baseline = SandboxNetworkDestination.toJson(result).toString();
        loaded = true;
        render();
        status.setTextColor(Theme.MUTED);
    }

    private void changed() {
        render();
        status.setTextColor(Theme.MUTED);
        status.setText(isDirty() ? "Unsaved changes" : "");
    }

    private boolean isDirty() {
        return !baseline.equals(SandboxNetworkDestination.toJson(draft).toString());
    }

    private void render() {
        entries.removeAllViews();
        if (loaded) {
            if (draft.isEmpty()) entries.addView(note("No private-network exceptions."));
            for (int i = 0; i < draft.size(); i++) {
                final int index = i;
                SandboxNetworkDestination entry = draft.get(i);
                LinearLayout card = Widgets.column(this);
                card.setBackground(Theme.rounded(this, Theme.PANEL, 14, Theme.LINE, 1));
                int pad = Theme.dp(this, 14);
                card.setPadding(pad, pad, pad, pad);
                card.setLayoutParams(lp(MATCH, WRAP));
                Widgets.margins(card, 0, 0, 0, Theme.dp(this, 12));
                card.addView(Widgets.mono(this, entry.ip + ":" + entry.port + "  TCP", Theme.INK, 13));
                LinearLayout actions = Widgets.row(this);
                TextView edit = button("Edit", () -> edit(index, entry));
                TextView remove = button("Remove", () -> { draft.remove(index); changed(); });
                edit.setEnabled(!saving);
                remove.setEnabled(!saving);
                actions.addView(edit);
                actions.addView(remove);
                card.addView(actions);
                entries.addView(card);
            }
        }
        add.setEnabled(loaded && !saving);
        save.setEnabled(loaded && !saving);
        clear.setEnabled(loaded && !saving && !draft.isEmpty());
        retry.setVisibility(loaded ? View.GONE : View.VISIBLE);
    }

    private void edit(int index, SandboxNetworkDestination initial) {
        LinearLayout form = Widgets.column(this);
        int pad = Theme.dp(this, 20);
        form.setPadding(pad, pad, pad, pad);
        form.addView(Widgets.fieldLabel(this, "Unicast IPv4 address"));
        EditText ip = Widgets.field(this, "100.64.0.10",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, true);
        ip.setText(initial.ip);
        form.addView(ip);
        form.addView(Widgets.fieldLabel(this, "TCP port (1–65535)"));
        EditText port = Widgets.field(this, "443", InputType.TYPE_CLASS_NUMBER, true);
        port.setText(String.valueOf(initial.port));
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "Add exception" : "Edit exception")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = ip.getText().toString().trim();
            if (!SandboxNetworkDestination.isIpv4Literal(value)) {
                ip.setError("Enter an exact IPv4 address, not a hostname or CIDR"); return;
            }
            int tcpPort;
            try { tcpPort = SandboxNetworkDestination.parsePort(port.getText().toString().trim()); }
            catch (IllegalArgumentException e) { port.setError(e.getMessage()); return; }
            for (int i = 0; i < draft.size(); i++) {
                SandboxNetworkDestination other = draft.get(i);
                if (i != index && other.ip.equals(value) && other.port == tcpPort) {
                    ip.setError("This IP + port is already in the list"); return;
                }
            }
            SandboxNetworkDestination entry = new SandboxNetworkDestination(value, tcpPort);
            if (index < 0) draft.add(entry); else draft.set(index, entry);
            changed();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void save() {
        if (!server.equals(api.prefs().httpBase())) { fail("Server address changed. Reopen this editor."); return; }
        saving = true;
        render();
        status.setTextColor(Theme.MUTED);
        status.setText("Saving…");
        api.saveSandboxNetwork(new ArrayList<>(draft), new Api.Cb<List<SandboxNetworkDestination>>() {
            @Override public void onResult(List<SandboxNetworkDestination> result) {
                if (isDestroyed()) return;
                saving = false;
                ready(result); // Use the normalized response, not the submitted draft.
                status.setText("Saved. Applies to new sandboxed turns only.");
            }
            @Override public void onError(String message) {
                saving = false;
                fail(message); // Keep the draft intact on validation or transport errors.
            }
        });
    }

    private void fail(String message) {
        if (isDestroyed()) return;
        render();
        status.setTextColor(Theme.DANGER);
        status.setText("Couldn't complete request: " + message);
    }

    // Modern gestures use the registered platform callback; older devices use this override.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (saving) return;
        if (loaded && isDirty()) {
            new AlertDialog.Builder(this).setTitle("Discard unsaved exceptions?")
                    .setNegativeButton("Keep editing", null)
                    .setPositiveButton("Discard", (d, w) -> finish()).show();
        } else super.onBackPressed();
    }

    private TextView note(String text) {
        TextView view = Widgets.text(this, text, Theme.MUTED, 12.5f, false);
        Widgets.margins(view, 0, 0, 0, Theme.dp(this, 12));
        return view;
    }

    private TextView button(String text, Runnable action) {
        TextView view = Widgets.ghostButton(this, text);
        view.setOnClickListener(v -> action.run());
        return view;
    }
}
