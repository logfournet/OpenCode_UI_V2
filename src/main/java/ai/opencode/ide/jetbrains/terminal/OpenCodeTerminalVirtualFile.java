package ai.opencode.ide.jetbrains.terminal;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.ex.FakeFileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;

import java.util.UUID;

import javax.swing.Icon;

/**
 * Virtual file representing an OpenCode terminal tab in the editor area.
 * Mirrors the behaviour of the 1.x plugin: a stable tab in the editor area
 * whose editor wraps a real terminal widget.
 */
public final class OpenCodeTerminalVirtualFile extends LightVirtualFile {

    private final String terminalName;
    private final String uniqueId = UUID.randomUUID().toString();

    public OpenCodeTerminalVirtualFile(String terminalName) {
        super(terminalName);
        this.terminalName = terminalName;
    }

    @Override
    public FileType getFileType() {
        return OpenCodeTerminalFileType.INSTANCE;
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public String getPath() {
        return "opencode-terminal://" + terminalName + "/" + uniqueId;
    }

    @Override
    public String toString() {
        return "OpenCodeTerminalVirtualFile(" + terminalName + "-" + uniqueId + ")";
    }

    /** Fake file type used solely so editors for this virtual file are opened. */
    public static final class OpenCodeTerminalFileType extends FakeFileType {
        public static final OpenCodeTerminalFileType INSTANCE = new OpenCodeTerminalFileType();

        @Override
        public String getName() {
            return "OpenCode Terminal";
        }

        @Override
        public String getDescription() {
            return "OpenCode Terminal";
        }

        @Override
        public boolean isMyFileType(VirtualFile file) {
            return file instanceof OpenCodeTerminalVirtualFile;
        }

        @Override
        public Icon getIcon() {
            return null;
        }
    }
}