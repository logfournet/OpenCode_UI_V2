package ai.opencode.ide.jetbrains.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Connection details of the running OpenCode v2 background service.
 */
public final class ServerConnection {
    public final String url;
    public final String password;
    public final long pid;
    public final String version;

    public ServerConnection(String url, String password, long pid, String version) {
        this.url = url;
        this.password = password;
        this.pid = pid;
        this.version = version;
    }

    public String hostname() {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public int port() {
        try {
            int p = URI.create(url).getPort();
            return p > 0 ? p : 80;
        } catch (Exception e) {
            return -1;
        }
    }

    public String display() {
        return hostname() + ":" + port();
    }

    /**
     * Discovers the OpenCode background service by reading {@code service.json}.
     *
     * OpenCode stores its state under:
     *   Windows: %LOCALAPPDATA%\opencode\state\service.json  (or ~\.local\state\opencode)
     *   macOS/Linux: $XDG_STATE_HOME/opencode or ~/.local/state/opencode/service.json
     *
     * @return connection info or null if no readable service.json is found.
     */
    public static ServerConnection discover() {
        for (Path dir : stateDirCandidates()) {
            File f = dir.resolve("service.json").toFile();
            if (!f.isFile() || !f.canRead()) continue;
            try {
                JsonObject root = JsonParser.parseString(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!root.has("url")) continue;
                String url = root.get("url").getAsString();
                String password = root.has("password") && !root.get("password").isJsonNull()
                        ? root.get("password").getAsString() : null;
                long pid = root.has("pid") && root.get("pid").isJsonPrimitive()
                        ? root.get("pid").getAsLong() : 0;
                String version = root.has("version") && !root.get("version").isJsonNull()
                        ? root.get("version").getAsString() : null;
                return new ServerConnection(url, password, pid, version);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Returns the standard candidates for the OpenCode state directory. */
    public static List<Path> stateDirCandidates() {
        List<Path> result = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                result.add(Paths.get(local, "opencode", "state"));
                result.add(Paths.get(local, "opencode"));
            }
        }
        String xdg = System.getenv("XDG_STATE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            result.add(Paths.get(xdg, "opencode"));
        }
        if (home != null && !home.isBlank()) {
            result.add(Paths.get(home, ".local", "state", "opencode"));
            result.add(Paths.get(home, ".local", "state", "opencode", "state"));
            result.add(Paths.get(home, ".opencode"));
        }
        return result;
    }

    /** True if the host/port accepts TCP connections. */
    public static boolean isReachable(ServerConnection conn, int timeoutMs) {
        if (conn == null || conn.url == null || conn.url.isBlank()) return false;
        try {
            URI uri = URI.create(conn.url);
            if (uri.getHost() == null || uri.getPort() < 0) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), timeoutMs);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
