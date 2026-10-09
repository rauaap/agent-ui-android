package com.agentui.app;

import java.io.*;
import okhttp3.*;
import org.json.JSONObject;

/** Binary HTTP contract. Uses the caller's authenticated transport; never follows redirects. */
final class ImageTransport {
    static String url(String server, ImageAttachment image) {
        return HttpUrl.get(server).newBuilder().addPathSegment("images").addPathSegment(image.id).build().toString();
    }

    static ImageAttachment upload(OkHttpClient client, String server, File original,
                                  String mime, ImageDiskCache cache) throws Exception {
        Request request = new Request.Builder().url(server + "/images")
                .post(RequestBody.create(original, MediaType.get(mime))).build();
        ImageAttachment image;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException(error(response));
            image = new ImageAttachment(new JSONObject(response.body().string()));
        }
        // Cache publication is independent of any Activity/view lifetime.
        try (InputStream in = new FileInputStream(original)) { cache.put(url(server, image), in); }
        catch (IOException ignored) { /* Upload is authoritative; later cache misses use GET. */ }
        return image;
    }

    static <T> T load(OkHttpClient client, String server, ImageAttachment image,
                      ImageDiskCache cache, ImageDiskCache.Reader<T> reader) throws Exception {
        String url = url(server, image);
        T result = cache.read(url, reader);
        if (result != null) return result;
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) throw new IOException(error(response));
            try (InputStream in = response.body().byteStream()) { cache.put(url, in); }
        }
        return cache.read(url, reader);
    }

    private static String error(Response response) throws IOException {
        String body = response.body() == null ? "" : response.body().string();
        try { return new JSONObject(body).optString("detail", "HTTP " + response.code()); }
        catch (Exception ignored) { return "HTTP " + response.code(); }
    }
}
