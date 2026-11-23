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
 * Scrolling therefore has to be requested from the TUI itself, using its global
 * line-scroll bindings ({@code Ctrl+Alt+Y} / {@code Ctrl+Alt+E}), its page bindings
 * ({@code PageUp} / {@code PageDown}), and its edge bindings
 * ({@code Ctrl+G} / {@code Ctrl+Alt+G}, which work even while the prompt is focused).
 */
public final class OpenCodeTerminalScrollSupport {

    private static final Logger LOG = Logger.getInstance(OpenCodeTerminalScrollSupport.class);

    /** A logical scroll operation, mapped to one of the TUI's own key bindings. */
    public enum Scroll {
        /** OpenCode global {@code session.line.up} ({@code Ctrl+Alt+Y}). */
        LINE_UP(new byte[]{0x1b, 0x19}),
        /** OpenCode global {@code session.line.down} ({@code Ctrl+Alt+E}). */
        LINE_DOWN(new byte[]{0x1b, 0x05}),
        PAGE_UP(KeyEvent.VK_PAGE_UP),
        PAGE_DOWN(KeyEvent.VK_PAGE_DOWN),
        /** OpenCode global {@code session.first} ({@code Ctrl+G}). */
        TOP(new byte[]{0x07}),
        /** OpenCode global {@code session.last} ({@code Ctrl+Alt+G}). */
        BOTTOM(new byte[]{0x1b, 0x07});

        final int vk;
        @Nullable final byte[] rawBytes;

        Scroll(int vk) {
            this.vk = vk;
            this.rawBytes = null;
        }

        Scroll(byte[] rawBytes) {
            this.vk = 0;
            this.rawBytes = rawBytes;
        }
    }

    private OpenCodeTerminalScrollSupport() {
    }

    /** Sends the TUI key sequence that performs {@code scroll} once. Safe no-op when the widget is gone. */
    public static void send(@Nullable ShellTerminalWidget widget, Scroll scroll) {
        send(widget, scroll, 1);
    }

    /**
     * Sends the TUI key sequence that performs {@code scroll}, repeated {@code repeat}
     * times in a single write. Safe no-op when the widget is gone or {@code repeat <= 0}.
     */
    public static void send(@Nullable ShellTerminalWidget widget, Scroll scroll, int repeat) {
        if (widget == null || scroll == null || repeat <= 0) return;
        try {
            byte[] unit;
            if (scroll.rawBytes != null) {
                unit = scroll.rawBytes;
            } else {
                Terminal terminal = widget.getTerminal();
                if (terminal == null) return;
                unit = terminal.getCodeForKey(scroll.vk, 0);
            }
            if (unit == null || unit.length == 0) return;
            TerminalOutputStream out = widget.getTerminalPanel().getTerminalOutputStream();
            if (out == null) return;
            byte[] payload;
            if (repeat == 1) {
                payload = unit;
            } else {
                payload = new byte[unit.length * repeat];
                for (int i = 0; i < repeat; i++) {
                    System.arraycopy(unit, 0, payload, i * unit.length, unit.length);
                }
            }
            out.sendBytes(payload, false);
        } catch (Throwable t) {
            LOG.debug("scroll key send failed", t);
        }
    }
}
