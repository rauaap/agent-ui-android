package com.agentui.app;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ImagesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static ImageAttachment image(String id, long size) throws Exception {
        return new ImageAttachment(new JSONObject().put("id", id).put("mime_type", "image/png")
                .put("size", size).put("width", 800).put("height", 600));
    }
    private ImageDiskCache cache(long size) throws Exception {
        return new ImageDiskCache(temp.newFolder(), size);
    }
    private static byte[] read(File file) throws IOException { return Files.readAllBytes(file.toPath()); }

    @Test public void originalUploadSeedsCacheWithoutGetAndUsesAuthentication() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            byte[] original = new byte[]{(byte) 137, 0, 1, 2, (byte) 255};
            File file = temp.newFile();
            Files.write(file.toPath(), original);
            server.enqueue(new MockResponse().setResponseCode(201).setBody(image("a", original.length).json().toString()));
            OkHttpClient client = new OkHttpClient.Builder().followRedirects(false)
                    .addInterceptor(new TokenInterceptor(() -> true, () -> "secret", token -> {}, token -> 200)).build();
            ImageDiskCache cache = cache(1000);
            String base = server.url("/").toString().replaceAll("/$", "");
            ImageAttachment uploaded = ImageTransport.upload(client, base, file, "image/png", cache);
            assertArrayEquals(original, ImageTransport.load(client, base, uploaded, cache, ImagesTest::read));
            assertEquals(1, server.getRequestCount());
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest();
            assertEquals("POST", request.getMethod());
            assertEquals("/images", request.getPath());
            assertEquals("image/png", request.getHeader("Content-Type"));
            assertEquals("Bearer secret", request.getHeader("Authorization"));
            assertArrayEquals(original, request.getBody().readByteArray());
            // New disk owner (app restart), same full URL, still no GET.
            assertArrayEquals(original, cache.read(ImageTransport.url(base, uploaded), ImagesTest::read));
        }
    }

    @Test public void cacheMissDownloadsAuthenticatedOriginalAndEncodesOpaqueId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            byte[] bytes = {4, 5, 6};
            server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                    .setBody(new Buffer().write(bytes)));
            OkHttpClient client = new OkHttpClient.Builder().followRedirects(false)
                    .addInterceptor(new TokenInterceptor(() -> true, () -> "secret", token -> {}, token -> 200)).build();
            String base = server.url("/").toString();
            ImageAttachment image = image("a/b ?#", 3);
            ImageDiskCache cache = cache(1000);
            assertArrayEquals(bytes, ImageTransport.load(client, base, image, cache, ImagesTest::read));
            assertArrayEquals(bytes, ImageTransport.load(client, base, image, cache, ImagesTest::read));
            assertEquals(1, server.getRequestCount());
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest();
            assertEquals("/images/a%2Fb%20%3F%23", request.getPath());
            assertEquals("Bearer secret", request.getHeader("Authorization"));
        }
    }

    @Test public void diskCacheIsBoundedServerScopedAndSurvivesRecreation() throws Exception {
        File directory = temp.newFolder();
        ImageDiskCache cache = new ImageDiskCache(directory, 5);
        String a = "https://one/images/id", b = "https://two/images/id";
        assertNotEquals(ImageDiskCache.key(a), ImageDiskCache.key(b));
        cache.put(a, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        new File(directory, ImageDiskCache.key(a)).setLastModified(1);
        cache.put(b, new ByteArrayInputStream(new byte[]{4, 5, 6}));
        assertNull(cache.read(a, ImagesTest::read));
        assertArrayEquals(new byte[]{4, 5, 6}, new ImageDiskCache(directory, 5).read(b, ImagesTest::read));
        assertEquals(1, directory.list().length);
    }

    @Test public void interruptedAndOversizedWritesNeverExposePartialFiles() throws Exception {
        File directory = temp.newFolder();
        ImageDiskCache cache = new ImageDiskCache(directory, 100);
        cache.put("url", new ByteArrayInputStream(new byte[]{7}));
        InputStream broken = new InputStream() {
            int read;
            @Override public int read() throws IOException {
                if (read++ == 2) throw new IOException("interrupted");
                return 1;
            }
        };
        assertThrows(IOException.class, () -> cache.put("url", broken));
        assertArrayEquals(new byte[]{7}, cache.read("url", ImagesTest::read));
        assertThrows(IOException.class, () -> cache.put("big", new ByteArrayInputStream(new byte[(int) ImageAttachment.MAX_BYTES + 1])));
        assertNull(cache.read("big", ImagesTest::read));
        assertEquals(1, directory.list().length);
    }

    @Test public void draftMetadataRestoresInOrderAndRemovingDoesNotChangeText() throws Exception {
        Map<String, String> values = new HashMap<>();
        SessionDraft.Store store = new SessionDraft.Store() {
            public String get(String key) { return values.get(key); }
            public void put(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        };
        SessionDraft draft = new SessionDraft("https://one", "1", store);
        draft.save("exact text\n");
        draft.saveImages(Arrays.asList(image("b", 2), image("a", 3), image("b", 2)));
        SessionDraft restored = new SessionDraft("https://one", "1", store);
        assertEquals("[\"b\",\"a\",\"b\"]", ImageAttachment.ids(restored.restoreImages()).toString());
        assertEquals(600, restored.restoreImages().get(0).height);
        assertTrue(new SessionDraft("https://two", "1", store).restoreImages().isEmpty());
        restored.saveImages(Collections.emptyList());
        assertEquals("exact text\n", restored.restore());
        assertTrue(restored.restoreImages().isEmpty());
        restored.save("");
        assertTrue(values.isEmpty());
    }

    @Test public void queueSnapshotsShipmentReplayKeepOrderedMetadataAndDeduplicate() throws Exception {
        MessageQueue queue = new MessageQueue();
        JSONArray images = ImageAttachment.metadata(Arrays.asList(image("b", 2), image("a", 3)));
        JSONObject input = new JSONObject().put("message_id", 123).put("text", "")
                .put("delivery", "queued").put("images", images);
        assertEquals(MessageQueue.Accepted.QUEUED, queue.accept(input));
        assertEquals("b", queue.pending().get(0).images.get(0).id);
        JSONObject snapshot = new JSONObject().put("messages", new JSONArray().put(input));
        queue.replace(snapshot);
        assertEquals("a", queue.pending().get(0).images.get(1).id);
        input.put("delivery", "shipped");
        List<MessageQueue.Message> rows = queue.ship(snapshot);
        assertEquals("", rows.get(0).text);
        assertEquals("b", rows.get(0).images.get(0).id);
        assertTrue(queue.ship(snapshot).isEmpty());
        queue.clear();
        assertEquals("a", queue.ship(snapshot).get(0).images.get(1).id);
    }

    @Test public void imageOnlyComposerAndCatalogCapabilityAreExplicit() throws Exception {
        assertNull(Composer.parse("  ", false));
        assertEquals("", Composer.parse("  ", true).text);
        assertFalse(Composer.parse("", true).bash);
        assertTrue(Composer.parse("!ls", true).bash);
        JSONObject model = new JSONObject().put("id", "m").put("name", "model")
                .put("reasoning_levels", new JSONArray());
        assertFalse(Model.from(model).supportsImages());
        model.put("input", new JSONArray().put("text").put("image"));
        assertTrue(Model.from(model).supportsImages());
        model.put("input", new JSONArray().put("text"));
        assertFalse(Model.from(model).supportsImages());
    }

    @Test public void httpErrorsRemainFailuresAndNeverPopulateCache() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"detail\":\"Unknown image\"}"));
            ImageDiskCache cache = cache(1000);
            String base = server.url("/").toString();
            ImageAttachment image = image("missing", 3);
            IOException error = assertThrows(IOException.class, () -> ImageTransport.load(
                    new OkHttpClient(), base, image, cache, ImagesTest::read));
            assertEquals("Unknown image", error.getMessage());
            assertNull(cache.read(ImageTransport.url(base, image), ImagesTest::read));
        }
    }
}
