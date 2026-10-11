package ai.opencode.ide.jetbrains.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the OpenCode v2 {@code /api/*} surface (verified on 2.0.26).
 *
 * Every endpoint returns a {@code {"data": ...}} envelope. Auth is HTTP Basic
 * with username {@code opencode} and the password from {@code service.json}.
 *
 * Uses only JDK {@link java.net.http.HttpClient} + Gson (bundled with the IDE),
 * so the plugin ships without OkHttp/Okio.
 */
public final class OpenCodeApiClient {

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient client;

    public OpenCodeApiClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String credentials = username + ":" + (password == null ? "" : password);
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** GET /api/info - returns version string, or null on failure. */
    public String info() {
        try {
            JsonObject root = get("/api/info");
            if (root.has("data") && root.getAsJsonObject("data").has("version")) {
                return root.getAsJsonObject("data").get("version").getAsString();
            }
            return root.has("version") ? root.get("version").getAsString() : "";
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /api/session - list sessions newest-first. */
    public List<JsonObject> listSessions(int limit, String order) {
        try {
            JsonObject root = get("/api/session?limit=" + limit + "&order=" + order);
            return asList(root.get("data"));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** POST /api/session - create a session for a directory. Returns session id or null. */
    public String createSession(String title, String directory) {
        JsonObject body = new JsonObject();
        body.addProperty("title", title);
        JsonObject loc = new JsonObject();
        loc.addProperty("directory", directory);
        body.add("location", loc);
        try {
            JsonObject root = post("/api/session", body.toString());
            JsonElement d = root.get("data");
            if (d != null && d.isJsonObject() && d.getAsJsonObject().has("id")) {
                return d.getAsJsonObject().get("id").getAsString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /api/session/{id} - session detail. */
    public JsonObject getSession(String sessionId) {
        try {
            JsonObject root = get("/api/session/" + sessionId);
            JsonElement d = root.get("data");
            return d != null && d.isJsonObject() ? d.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /api/session/{id}/diff?from=...&to=... - per-file diffs of a turn. */
    public List<JsonObject> sessionDiff(String sessionId, String from, String to) {
        StringBuilder sb = new StringBuilder("/api/session/").append(sessionId).append("/diff");
        List<String> params = new ArrayList<>();
        if (from != null && !from.isEmpty()) params.add("from=" + from);
        if (to != null && !to.isEmpty()) params.add("to=" + to);
        if (!params.isEmpty()) sb.append('?').append(String.join("&", params));
        try {
            JsonObject root = get(sb.toString());
            return asList(root.get("data"));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** GET /api/session/{id}/message - message list, newest-first with order=desc. */
    public List<JsonObject> listMessages(String sessionId, String order, int limit) {
        try {
            JsonObject root = get("/api/session/" + sessionId + "/message?order=" + order + "&limit=" + limit);
            return asList(root.get("data"));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** GET /api/session/active - map of session id -> "running". */
    public Map<String, String> activeSessions() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            JsonObject root = get("/api/session/active");
            JsonElement d = root.get("data");
            if (d != null && d.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : d.getAsJsonObject().entrySet()) {
                    String type = e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("type")
                            ? e.getValue().getAsJsonObject().get("type").getAsString() : "";
                    map.put(e.getKey(), type);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /**
     * POST /api/session/{id}/prompt - sends a user message.
     * Returns the created user message id (the "from" anchor for /diff), or null on failure.
     */
    public String prompt(String sessionId, String text, List<JsonObject> files) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        if (files != null && !files.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (JsonObject f : files) arr.add(f);
            body.add("files", arr);
        }
        try {
            JsonObject root = post("/api/session/" + sessionId + "/prompt", body.toString());
            JsonElement d = root.get("data");
            if (d != null && d.isJsonObject() && d.getAsJsonObject().has("id")) {
                return d.getAsJsonObject().get("id").getAsString();
            }
            return "";
        } catch (Exception e) {
            return null;
        }
    }

    /** POST /api/session/{id}/interrupt - stops the current generation. */
    public boolean interrupt(String sessionId) {
        try {
            post("/api/session/" + sessionId + "/interrupt", "{}");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<JsonObject> asList(JsonElement data) {
        List<JsonObject> out = new ArrayList<>();
        if (data != null && data.isJsonArray()) {
            for (int i = 0; i < data.getAsJsonArray().size(); i++) {
                JsonElement e = data.getAsJsonArray().get(i);
                if (e.isJsonObject()) out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    private JsonObject get(String path) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        return send(req, false);
    }

    private JsonObject post(String path, String json) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8))
                .build();
        return send(req, true);
    }

    private JsonObject send(HttpRequest req, boolean post) throws IOException {
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        String body = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() >= 300) {
            String snippet = body.length() > 400 ? body.substring(0, 400) : body;
            throw new IOException("HTTP " + resp.statusCode() + ": " + snippet);
        }
        if (body.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("bad json", e);
        }
    }
}
