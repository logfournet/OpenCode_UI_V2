package ai.opencode.ide.jetbrains.session;

import com.google.gson.JsonObject;

/**
 * Lightweight view of a {@code Session.Info} entry from GET /api/session.
 */
public final class SessionInfo {
    public final String id;
    public final String title;
    public final String directory;
    public final long created;
    public final long updated;
    public final String outcome;

    public SessionInfo(String id, String title, String directory, long created, long updated, String outcome) {
        this.id = id;
        this.title = title;
        this.directory = directory;
        this.created = created;
        this.updated = updated;
        this.outcome = outcome;
    }

    public String displayName() {
        if (title != null && !title.isBlank()) return title;
        return id;
    }

    public static SessionInfo fromJson(JsonObject o) {
        String id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
        String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : "";
        String dir = null;
        if (o.has("location") && o.get("location").isJsonObject()
                && o.getAsJsonObject("location").has("directory")) {
            dir = o.getAsJsonObject("location").get("directory").getAsString();
        }
        long created = 0, updated = 0;
        if (o.has("time") && o.get("time").isJsonObject()) {
            JsonObject t = o.getAsJsonObject("time");
            if (t.has("created") && t.get("created").isJsonPrimitive()) created = t.get("created").getAsLong();
            if (t.has("updated") && t.get("updated").isJsonPrimitive()) updated = t.get("updated").getAsLong();
        }
        String outcome = o.has("outcome") && !o.get("outcome").isJsonNull() ? o.get("outcome").getAsString() : "";
        return new SessionInfo(id, title, dir, created, updated, outcome);
    }
}
