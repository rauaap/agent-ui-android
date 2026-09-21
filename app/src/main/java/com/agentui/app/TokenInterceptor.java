package com.agentui.app;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Common REST and WebSocket handshake policy; never puts credentials in URLs. */
final class TokenInterceptor implements Interceptor {
    interface Probe { int check(String token) throws IOException; }
    private final BooleanSupplier ready;
    private final Supplier<String> token;
    private final Consumer<String> rejected;
    private final Probe probe;

    TokenInterceptor(BooleanSupplier ready, Supplier<String> token,
                     Consumer<String> rejected, Probe probe) {
        this.ready = ready;
        this.token = token;
        this.rejected = rejected;
        this.probe = probe;
    }

    @Override public Response intercept(Chain chain) throws IOException {
        if (!ready.getAsBoolean()) throw new IOException("Server token required; open Settings");
        String secret = token.get();
        Request request = chain.request().newBuilder()
                .header("Authorization", "Bearer " + secret).build();
        boolean socket = "websocket".equalsIgnoreCase(request.header("Upgrade"));
        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            if (socket) probe(secret);
            throw new IOException("Unable to reach server");
        }
        if (response.code() == 401) rejected.accept(secret);
        else if (socket && response.code() != 101) probe(secret);
        return response;
    }

    private void probe(String secret) {
        try { if (probe.check(secret) == 401) rejected.accept(secret); }
        catch (IOException ignored) { /* Existing offline handling. */ }
    }
}
