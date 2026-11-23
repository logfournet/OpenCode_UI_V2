package ai.opencode.ide.jetbrains.terminal;

import com.intellij.openapi.diagnostic.Logger;
import com.jediterm.terminal.Terminal;
import com.jediterm.terminal.TerminalOutputStream;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;

import java.awt.event.KeyEvent;

/**
 * Drives the OpenCode TUI's own scroll bindings.
 *
 * <p>The OpenCode TUI runs in the terminal's alternate screen, where JediTerm has
 * no scrollback at all ({@code TerminalTextBuffer.useAlternateBuffer(true)} swaps the
 * history storage out and {@code TerminalPanel.myScrollingEnabled} becomes false).
 * Scrolling therefore has to be requested from the TUI itself, using the key
 * bindings it ships with.
 */
public final class OpenCodeTerminalScrollSupport {

    private static final Logger LOG = Logger.getInstance(OpenCodeTerminalScrollSupport.class);

    /** A logical scroll operation, mapped to one of the TUI's own key bindings. */
    public enum Scroll {
        PAGE_UP(KeyEvent.VK_PAGE_UP),
        PAGE_DOWN(KeyEvent.VK_PAGE_DOWN),
        TOP(KeyEvent.VK_HOME),
        BOTTOM(KeyEvent.VK_END);

        final int vk;

        Scroll(int vk) {
            this.vk = vk;
        }
    }

    private OpenCodeTerminalScrollSupport() {
    }

    /** Sends the TUI key that performs {@code scroll}. Safe no-op when the widget is gone. */
    public static void send(@Nullable ShellTerminalWidget widget, Scroll scroll) {
        if (widget == null || scroll == null) return;
        try {
            Terminal terminal = widget.getTerminal();
            if (terminal == null) return;
            byte[] code = terminal.getCodeForKey(scroll.vk, 0);
            if (code == null || code.length == 0) return;
            TerminalOutputStream out = widget.getTerminalPanel().getTerminalOutputStream();
            if (out != null) out.sendBytes(code, false);
        } catch (Throwable t) {
            LOG.debug("scroll key send failed", t);
        }
    }
}
