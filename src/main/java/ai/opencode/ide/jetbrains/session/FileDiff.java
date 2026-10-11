package ai.opencode.ide.jetbrains.session;

import com.google.gson.JsonObject;

/**
 * A single file diff produced by an OpenCode turn.
 */
public final class FileDiff {
    public final String file;        // relative path from project root (forward slashes)
    public final String patch;       // unified diff
    public final int additions;
    public final int deletions;
    public final String status;      // added | deleted | modified

    // Resolved "before" and "after" content (used by the diff viewer).
    public String beforeContent;
    public String afterContent;

    public FileDiff(String file, String patch, int additions, int deletions, String status) {
        this.file = file;
        this.patch = patch;
        this.additions = additions;
        this.deletions = deletions;
        this.status = status;
    }

    public boolean isNewFile() {
        return "added".equals(status);
    }

    public boolean isDeleted() {
        return "deleted".equals(status);
    }

    public static FileDiff fromJson(JsonObject o) {
        String file = o.has("file") && !o.get("file").isJsonNull() ? o.get("file").getAsString() : "";
        String patch = o.has("patch") && !o.get("patch").isJsonNull() ? o.get("patch").getAsString() : "";
        int add = o.has("additions") && o.get("additions").isJsonPrimitive() ? o.get("additions").getAsInt() : 0;
        int del = o.has("deletions") && o.get("deletions").isJsonPrimitive() ? o.get("deletions").getAsInt() : 0;
        String status = o.has("status") && !o.get("status").isJsonNull() ? o.get("status").getAsString() : "modified";
        return new FileDiff(file, patch, add, del, status);
    }
}