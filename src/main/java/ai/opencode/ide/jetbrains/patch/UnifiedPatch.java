package ai.opencode.ide.jetbrains.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal unified-diff utilities used to reconstruct the "before" side of a
 * file OpenCode changed. The v2 {@code /diff} endpoint only returns a unified
 * {@code patch} plus status, so for modified files we reverse-apply the patch
 * to the current (after) content to obtain the before content.
 *
 * Supported hunk header forms:
 *   @@ -1,3 +1,4 @@
 *   @@ -1 +1 @@            (counts default to 1)
 *   @@ -0,0 +1 @@          (file addition)
 *   @@ -1,3 +0,0 @@        (file deletion)
 */
public final class UnifiedPatch {

    private static final Pattern HUNK = Pattern.compile(
            "^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");

    private UnifiedPatch() {
    }

    /**
     * Reverse-applies {@code patch} to {@code after} to produce the before content.
     * Returns null if the patch cannot be parsed.
     */
    public static String reverseApply(String patch, String after) {
        if (patch == null || after == null) return null;
        String[] raw = patch.split("\n", -1);
        List<Hunk> hunks = parseHunks(raw);
        if (hunks.isEmpty()) return null;

        boolean afterTrailingNL = after.endsWith("\n");
        String body = afterTrailingNL ? after.substring(0, after.length() - 1) : after;
        List<String> work = new ArrayList<>();
        if (!body.isEmpty()) {
            for (String p : body.split("\n", -1)) work.add(p);
        }

        boolean noNewlineMarker = false;

        // Process hunks bottom-up so line indices stay valid.
        for (int h = hunks.size() - 1; h >= 0; h--) {
            Hunk hunk = hunks.get(h);
            // Parse the hunk body into ops, each tagged with its after-file index.
            List<Op> ops = new ArrayList<>();
            int idx = hunk.newStart - 1;
            for (String line : hunk.body) {
                if (line.isEmpty()) continue;
                char tag = line.charAt(0);
                String text = line.length() > 1 ? line.substring(1) : "";
                switch (tag) {
                    case ' ':
                        ops.add(new Op(' ', idx, text));
                        idx++;
                        break;
                    case '+':
                        ops.add(new Op('+', idx, text));
                        idx++;
                        break;
                    case '-':
                        ops.add(new Op('-', idx, text));
                        break;
                    case '\\':
                        noNewlineMarker = true;
                        break;
                    default:
                        break;
                }
            }
            // Apply ops in reverse: delete '+' additions, insert '-' removals.
            for (int o = ops.size() - 1; o >= 0; o--) {
                Op op = ops.get(o);
                if (op.tag == '+') {
                    if (op.idx >= 0 && op.idx < work.size()) work.remove(op.idx);
                } else if (op.tag == '-') {
                    int at = Math.max(0, Math.min(op.idx, work.size()));
                    work.add(at, op.text);
                }
            }
        }

        if (work.isEmpty()) return "";

        boolean trailingNL;
        if (noNewlineMarker) {
            trailingNL = false;
        } else if (after.isEmpty()) {
            // File was deleted; the before content presumably ended with a newline.
            trailingNL = true;
        } else {
            trailingNL = afterTrailingNL;
        }

        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < work.size(); k++) {
            if (k > 0) sb.append('\n');
            sb.append(work.get(k));
        }
        if (trailingNL) sb.append('\n');
        return sb.toString();
    }

    /**
     * Extracts the full "added" content of a brand-new file directly from its
     * unified diff. Works for hunks like {@code @@ -0,0 +1,N @@} where the patch
     * body is only {@code +} lines. Returns null when the patch is not a clean
     * file addition (e.g. it also has context or {@code -} lines).
     */
    public static String extractAddedContent(String patch) {
        if (patch == null) return null;
        String[] raw = patch.split("\n", -1);
        List<Hunk> hunks = parseHunks(raw);
        if (hunks.isEmpty()) return null;
        boolean onlyAdditions = true;
        for (Hunk h : hunks) {
            if (h.oldStart != 0 || h.oldCount != 0) onlyAdditions = false;
            for (String line : h.body) {
                if (line.isEmpty()) continue;
                char tag = line.charAt(0);
                if (tag == '-' || tag == ' ') {
                    onlyAdditions = false;
                    break;
                }
            }
        }
        if (!onlyAdditions) return null;

        List<String> lines = new ArrayList<>();
        boolean noNewlineMarker = false;
        for (Hunk h : hunks) {
            for (String line : h.body) {
                if (line.isEmpty()) continue;
                char tag = line.charAt(0);
                if (tag == '\\') {
                    noNewlineMarker = true;
                } else if (tag == '+') {
                    lines.add(line.length() > 1 ? line.substring(1) : "");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            if (k > 0) sb.append('\n');
            sb.append(lines.get(k));
        }
        if (!noNewlineMarker) sb.append('\n');
        return sb.toString();
    }

    private static List<Hunk> parseHunks(String[] raw) {
        List<Hunk> hunks = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            String line = raw[i];
            if (line.startsWith("@@")) {
                Matcher m = HUNK.matcher(line);
                if (!m.matches()) {
                    i++;
                    continue;
                }
                int oldStart = Integer.parseInt(m.group(1));
                int oldCount = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                int newStart = Integer.parseInt(m.group(3));
                int newCount = m.group(4) != null ? Integer.parseInt(m.group(4)) : 1;
                List<String> bodyLines = new ArrayList<>();
                i++;
                while (i < raw.length && !raw[i].startsWith("@@")) {
                    bodyLines.add(raw[i]);
                    i++;
                }
                hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, bodyLines));
            } else {
                i++;
            }
        }
        return hunks;
    }

    private static final class Op {
        final char tag;
        final int idx;
        final String text;

        Op(char tag, int idx, String text) {
            this.tag = tag;
            this.idx = idx;
            this.text = text;
        }
    }

    private static final class Hunk {
        final int oldStart;
        final int oldCount;
        final int newStart;
        final int newCount;
        final List<String> body;

        Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> body) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.body = body;
        }
    }
}