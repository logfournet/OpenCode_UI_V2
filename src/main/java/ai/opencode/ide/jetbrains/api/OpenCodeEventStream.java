package ai.opencode.ide.jetbrains.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SSE listener for {@code GET /api/event} of OpenCode v2.
 *
 * Event frames look like {@code data: {"id":"evt_...","type":"session.step.ended","data":{...}}},
 * observed on 2.0.26. The stream is volatile by contract, so we reconnect forever
 * with a small backoff; the service additionally polls REST endpoints as a fallback.
 */
public final class OpenCodeEventStream implements AutoCloseable {

    private final URI uri;
    private final String authHeader;
    private final BiConsumer<String, JsonObject> listener;
    private final Consumer<Boolean> stateListener;
    private final HttpClient client;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile HttpResponse<InputStream> current;

    public OpenCodeEventStream(String baseUrl, String username, String password,
                               BiConsumer<String, JsonObject> listener,
                               Consumer<Boolean> stateListener) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.uri = URI.create(base + "/api/event");
        String credentials = username + ":" + (password == null ? "" : password);
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.listener = listener;
        this.stateListener = stateListener;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this::loop, "opencode-sse");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                connectOnce();
            } catch (Throwable t) {
                stateListener.accept(false);
            }
            sleep(1500);
        }
    }

    private void connectOnce() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", authHeader)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        current = resp;
        if (resp.statusCode() != 200) {
            stateListener.accept(false);
            return;
        }
        stateListener.accept(true);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String json = line.substring(5).trim();
                    if (!json.isEmpty()) dispatch(json);
                }
            }
        }
        stateListener.accept(false);
    }

    private void dispatch(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "";
            JsonObject data = obj.has("data") && obj.get("data").isJsonObject()
                    ? obj.getAsJsonObject("data") : new JsonObject();
            listener.accept(type, data);
        } catch (Exception ignored) {
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        HttpResponse<InputStream> c = current;
        if (c != null) {
            try {
                c.body().close();
            } catch (Exception ignored) {
            }
        }
        if (worker != null) worker.interrupt();
    }
}
