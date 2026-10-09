package com.agentui.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Dismissible full-screen, fit-to-screen viewer. Fetch/decode never block dialog display. */
final class ImageViewer {
    static Dialog show(Activity activity, Api api, String server, ImageAttachment image, SelectedImageFile original) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        dialog.setCancelable(true); // Native Back/back gesture dismisses rather than leaving the session.
        LinearLayout root = Widgets.column(activity);
        root.setBackgroundColor(Theme.BG);
        int p = Theme.dp(activity, 12);
        root.setPadding(p, p, p, p);

        LinearLayout header = Widgets.row(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Widgets.text(activity, "Image", Theme.INK, 16, true);
        header.addView(title, lp(0, WRAP, 1f));
        TextView close = Widgets.text(activity, "×", Theme.INK, 28, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close image");
        close.setBackground(Theme.rounded(activity, Theme.PANEL2, 10, Theme.LINE, 1));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, lp(Theme.dp(activity, 44), Theme.dp(activity, 44)));
        root.addView(header, lp(MATCH, WRAP));

        FrameLayout content = new FrameLayout(activity);
        ImageView view = new ImageView(activity);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription("Full image");
        content.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView status = Widgets.text(activity, "Loading image…", Theme.MUTED, 14, false);
        status.setGravity(Gravity.CENTER);
        content.addView(status, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(content, lp(MATCH, 0, 1f));
        FrameLayout safe = new FrameLayout(activity);
        safe.setBackgroundColor(Theme.BG);
        safe.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));
        Widgets.fitSystemWindows(safe);
        dialog.setContentView(safe);
        dialog.setOnDismissListener(d -> view.setImageDrawable(null));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Theme.BG));
            window.setLayout(MATCH, MATCH);
        }

        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int target = ImageDecodeSize.viewerTarget(metrics.widthPixels, metrics.heightPixels);
        Api.Cb<Bitmap> result = new Api.Cb<Bitmap>() {
            private boolean current() { return dialog.isShowing() && !activity.isDestroyed(); }
            @Override public void onResult(Bitmap bitmap) {
                if (!current()) return;
                view.setImageBitmap(bitmap);
                status.setVisibility(android.view.View.GONE);
            }
            @Override public void onError(String message) {
                if (current()) status.setText(message);
            }
        };
        if (image != null || original != null)
            Images.loadViewer(activity, api, server, image, original, target, result);
        else result.onError("Image unavailable");
        return dialog;
    }
}
