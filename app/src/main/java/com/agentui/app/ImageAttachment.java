package com.agentui.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Immutable server metadata, in attachment occurrence order. No image bytes. */
final class ImageAttachment {
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_COUNT = 10;
    final String id, mimeType;
    final long size;
    final int width, height;

    ImageAttachment(JSONObject o) {
        id = o.optString("id", "");
        mimeType = o.optString("mime_type", "");
        size = o.optLong("size", 0);
        width = o.optInt("width", 0);
        height = o.optInt("height", 0);
        if (id.isEmpty() || !supported(mimeType) || size <= 0 || size > MAX_BYTES
                || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image metadata");
    }

    static boolean supported(String mime) {
        return "image/jpeg".equals(mime) || "image/png".equals(mime)
                || "image/gif".equals(mime) || "image/webp".equals(mime);
    }

    JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("mime_type", mimeType).put("size", size)
                    .put("width", width).put("height", height);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return o;
    }

    static List<ImageAttachment> parse(JSONArray array) {
        List<ImageAttachment> images = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException("Invalid image metadata");
            images.add(new ImageAttachment(o));
        }
        return images;
    }

    static JSONArray metadata(List<ImageAttachment> images) {
        JSONArray array = new JSONArray();
        for (ImageAttachment image : images) array.put(image.json());
        return array;
    }

    static JSONArray ids(List<ImageAttachment> images) {
        JSONArray array = new JSONArray();
        for (ImageAttachment image : images) array.put(image.id);
        return array;
    }
}
