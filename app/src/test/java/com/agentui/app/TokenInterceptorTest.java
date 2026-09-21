package com.agentui.app;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TokenInterceptorTest {
    private static final String SECRET = "abcdefghijklmnopqrstuvwxyz012345+/=";

    @Test public void missingTokenMakesNoNetworkRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            OkHttpClient client = client(false, new AtomicReference<>(""), new AtomicBoolean(), 200);
            try {
                client.newCall(new Request.Builder().url(server.url("/agents")).build()).execute();
                fail();
            } catch (IOException expected) { assertFalse(expected.getMessage().contains(SECRET)); }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void everyRestRequestReadsCurrentTokenAnd401Rejects() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicReference<String> token = new AtomicReference<>(SECRET);
            AtomicBoolean rejected = new AtomicBoolean();
            OkHttpClient client = client(true, token, rejected, 200);
            server.enqueue(new MockResponse().setBody("[]"));
            server.enqueue(new MockResponse().setResponseCode(401).setBody("Missing or invalid token"));
            try (Response response = client.newCall(new Request.Builder().url(server.url("/projects")).build()).execute()) {
                assertEquals(200, response.code());
            }
            assertEquals("Bearer " + SECRET, server.takeRequest().getHeader("Authorization"));
            token.set(SECRET + "new");
            try (Response response = client.newCall(new Request.Builder().url(server.url("/usage")).build()).execute()) {
                assertEquals(401, response.code());
            }
            assertEquals("Bearer " + SECRET + "new", server.takeRequest().getHeader("Authorization"));
            assertTrue(rejected.get());
        }
    }

    @Test public void failedWebSocketsProbeBeforeReportingFailure() throws Exception {
        for (int probeCode : new int[]{200, 401, -1}) {
            try (MockWebServer server = new MockWebServer()) {
                AtomicBoolean rejected = new AtomicBoolean();
                AtomicInteger probes = new AtomicInteger();
                OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new TokenInterceptor(
                        () -> true, () -> SECRET, s -> rejected.set(true), s -> {
                            assertEquals(SECRET, s);
                            probes.incrementAndGet();
                            if (probeCode == -1) throw new IOException("offline");
                            return probeCode;
                        })).build();
                server.enqueue(new MockResponse().setResponseCode(403));
                CountDownLatch failed = new CountDownLatch(1);
                WebSocket socket = client.newWebSocket(new Request.Builder()
                        .url(server.url("/ws/sessions/1/files")).build(), new WebSocketListener() {
                    @Override public void onFailure(WebSocket ws, Throwable t, Response r) { failed.countDown(); }
                });
                assertTrue(failed.await(5, TimeUnit.SECONDS));
                RecordedRequest request = server.takeRequest();
                assertEquals("Bearer " + SECRET, request.getHeader("Authorization"));
                assertEquals("/ws/sessions/1/files", request.getPath());
                assertEquals(1, probes.get());
                assertEquals(probeCode == 401, rejected.get());
                socket.cancel();
                client.dispatcher().executorService().shutdown();
            }
        }
    }

    @Test public void successfulWebSocketsAndReconnectsCarryHeaderOnly() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicReference<String> token = new AtomicReference<>(SECRET);
            OkHttpClient client = client(true, token, new AtomicBoolean(), 200);
            for (String path : new String[]{"/ws/sessions/1", "/ws/sessions/1/files", "/ws/sessions/1"}) {
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
                CountDownLatch opened = new CountDownLatch(1);
                WebSocket socket = client.newWebSocket(new Request.Builder().url(server.url(path)).build(),
                        new WebSocketListener() {
                            @Override public void onOpen(WebSocket ws, Response response) { opened.countDown(); }
                        });
                assertTrue(opened.await(5, TimeUnit.SECONDS));
                RecordedRequest request = server.takeRequest();
                assertEquals("Bearer " + token.get(), request.getHeader("Authorization"));
                assertEquals(path, request.getPath());
                socket.cancel();
                token.set(SECRET + "replacement");
            }
            client.dispatcher().executorService().shutdown();
        }
    }

    @Test public void redirectsDoNotForwardCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", other.url("/agents")));
            OkHttpClient client = client(true, new AtomicReference<>(SECRET), new AtomicBoolean(), 200);
            try (Response response = client.newCall(new Request.Builder().url(server.url("/agents")).build()).execute()) {
                assertEquals(302, response.code());
            }
            assertEquals(0, other.getRequestCount());
        }
    }

    private OkHttpClient client(boolean ready, AtomicReference<String> token,
                               AtomicBoolean rejected, int probeCode) {
        return new OkHttpClient.Builder().followRedirects(false).addInterceptor(
                new TokenInterceptor(() -> ready, token::get, s -> rejected.set(true), s -> probeCode)).build();
    }
}
