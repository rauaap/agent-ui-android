package com.agentui.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared bounded binary cache; all I/O and display-only decoding happen on workers. */
final class Images {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService UPLOADS = Executors.newSingleThreadExecutor();
    private static final ExecutorService VIEWERS = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ImageDiskCache cache;

    private static synchronized ImageDiskCache cache(Context context) {
        if (cache == null) cache = new ImageDiskCache(new File(context.getCacheDir(), "images"), 100L * 1024 * 1024);
        return cache;
    }

    static final class Selection {
        final Bitmap preview;
        final SelectedImageFile original;
        Selection(Bitmap preview, SelectedImageFile original) {
            this.preview = preview;
            this.original = original;
        }
    }

    static void release(SelectedImageFile original) {
        if (original != null) UPLOADS.execute(original::close);
    }

    static void upload(Context context, Api api, String server, Uri uri, Api.Cb<Selection> preview, Api.Cb<ImageAttachment> cb) {
        Context app = context.getApplicationContext();
        UPLOADS.execute(() -> {
            File temp = null;
            boolean handedOff = false;
            try {
                if (!Auth.ready() || !server.equals(api.prefs().httpBase()))
                    throw new IOException("Authentication required for this server");
                String mime = app.getContentResolver().getType(uri);
                if (!ImageAttachment.supported(mime)) throw new IOException("Choose JPEG, PNG, GIF or WebP");
                temp = cache(app).temporaryFile();
                try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot read selected image");
                    ImageDiskCache.copy(in, temp);
                }
                Bitmap thumbnail = decode(temp, 320);
                Selection selected = new Selection(thumbnail, new SelectedImageFile(temp));
                MAIN.post(() -> preview.onResult(selected));
                handedOff = true;
                ImageAttachment image = ImageTransport.upload(api.http(), server, temp, mime, cache(app));
                MAIN.post(() -> cb.onResult(image));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Image upload failed" : e.getMessage();
                MAIN.post(() -> cb.onError(message));
            } finally { if (temp != null && !handedOff) temp.delete(); }
        });
    }

    static void load(Context context, Api api, String server, ImageAttachment image, int target,
                     Api.Cb<Bitmap> cb) {
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                // Cached private bytes must not bypass the auth gate.
                if (!Auth.ready() || !server.equals(api.prefs().httpBase()))
                    throw new IOException("Authentication required for this server");
                Bitmap bitmap = ImageTransport.load(api.http(), server, image, cache(app),
                        file -> decode(file, target));
                if (bitmap == null) throw new IOException("Cannot decode image");
                Bitmap result = bitmap;
                MAIN.post(() -> cb.onResult(result));
            } catch (Exception e) { MAIN.post(() -> cb.onError("Image unavailable")); }
        });
    }

    static void loadSelected(Api api, String server, SelectedImageFile original, int target, Api.Cb<Bitmap> cb) {
        WORK.execute(() -> {
            try {
                if (!Auth.ready() || !server.equals(api.prefs().httpBase()))
                    throw new IOException("Authentication required for this server");
                Bitmap bitmap = original.read(file -> decode(file, target));
                if (bitmap == null) throw new IOException("Cannot decode image");
                MAIN.post(() -> cb.onResult(bitmap));
            } catch (Exception e) { MAIN.post(() -> cb.onError("Image unavailable")); }
        });
    }

    static void loadViewer(Context context, Api api, String server, ImageAttachment image,
                           SelectedImageFile original, int target, Api.Cb<Bitmap> cb) {
        Context app = context.getApplicationContext();
        // A tapped image must not wait behind a transcript replay's thumbnail download queue.
        VIEWERS.execute(() -> {
            try {
                if (!Auth.ready() || !server.equals(api.prefs().httpBase()))
                    throw new IOException("Authentication required for this server");
                Bitmap bitmap = original != null ? original.read(file -> decode(file, target))
                        : ImageTransport.load(api.http(), server, image, cache(app), file -> decode(file, target));
                if (bitmap == null) throw new IOException("Cannot decode image");
                MAIN.post(() -> cb.onResult(bitmap));
            } catch (Exception e) { MAIN.post(() -> cb.onError("Image unavailable")); }
        });
    }

    private static Bitmap decode(File file, int target) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Cannot decode image");
        options.inSampleSize = ImageDecodeSize.sampleSize(options.outWidth, options.outHeight, target);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

}
