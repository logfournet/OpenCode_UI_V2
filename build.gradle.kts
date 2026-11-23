plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "2.0.12"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        // Optional: terminal integration (OpenCodeTerminalLauncher)
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // Gson is bundled with the IDE at runtime; we only need it on the
    // compile classpath so it is not packaged into the plugin jar.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }

        changeNotes = """
            <h2>2.0.12</h2>
            <ul>
                <li>Direct plain mouse-wheel scrolling over the OpenCode TUI panel
                    (no <code>Shift</code> required), using OpenCode's global
                    line-scroll bindings (<code>Ctrl+Alt+Y</code> /
                    <code>Ctrl+Alt+E</code>). <code>Shift</code>+wheel still
                    scrolls faster by one page per notch, and Scroll to Top /
                    Scroll to Bottom now send <code>Ctrl+G</code> /
                    <code>Ctrl+Alt+G</code> so they work even while the prompt is
                    focused. Plain shell output and <code>Ctrl</code>+wheel zoom
                    pass through untouched.</li>
                <li>JetBrains Platform runtime fixes: migrated startup hook to
                    <code>ProjectActivity</code>, moved every action's update to
                    <code>ActionUpdateThread.BGT</code>, gave every title-bar
                    button a distinct icon and tooltip, and stopped the
                    status-bar spinner timer while idle.</li>
                <li>OpenCode v2.3.2 API alignment: filtered
                    <code>/api/event?types=...</code> SSE lifecycle tracking,
                    directory-scoped non-archived top-level session discovery,
                    fixed poll-based auto-reconnect, and Local History start
                    labels for turns started in the TUI.</li>
            </ul>
            <h2>2.0.11</h2>
            <ul>
                <li>Scroll long agent output: Scroll to Top / Page Up / Page Down /
                    Scroll to Bottom buttons in the tool window title bar, plus
                    Shift+wheel. The TUI runs in the terminal's alternate screen,
                    where there is no terminal scrollback, so these forward the
                    TUI's own scroll bindings.</li>
                <li>Removed all deprecated and scheduled-for-removal platform API
                    usages (<code>TerminalView</code>,
                    <code>getTerminalStarter</code>, <code>ServiceManager</code>,
                    <code>VirtualFileManager.addVirtualFileListener</code>,
                    <code>FileEditorManager.openTextEditor</code>,
                    <code>StatusBarWidget.getPresentation(PlatformType)</code>).</li>
                <li>Minimum IDE raised to 2024.3 (build 243).</li>
            </ul>
            <h2>2.0.5</h2>
            <ul>
                <li>Fix the panel being replaced by "This view is not available until
                    indexes are built" while the project is indexing. The tool window
                    factory is now <code>DumbAware</code>, so the OpenCode terminal
                    stays available in dumb mode.</li>
            </ul>
            <h2>2.0.4</h2>
            <ul>
                <li>Fix the OpenCode panel showing up and then going blank in newer
                    platform builds (e.g. PhpStorm 2026.2.x). The terminal widget is
                    no longer re-parented out of the Terminal tool window; the
                    terminal Content is moved into the OpenCode tool window instead,
                    and the widget is created without stealing focus.</li>
            </ul>
            <h2>2.0.3</h2>
            <ul>
                <li>No line limit: the terminal scrollback is unbounded by default
                    (<code>terminal.buffer.max.lines.count</code>); a
                    <b>Terminal scrollback</b> setting can impose a finite cap.</li>
            </ul>
            <h2>2.0.2</h2>
            <ul>
                <li>Fix UI jank on long output (removed the destructive
                    <code>clearHistory</code> trim).</li>
            </ul>
            <h2>2.0.1</h2>
            <ul>
                <li>Keep OpenCode v2 TUI session tabs per project
                    (<code>tabs.scope=cwd</code>) so they no longer leak between
                    IDE windows.</li>
            </ul>
            <h2>2.0.0</h2>
            <ul>
                <li>Rewritten for the OpenCode 2.x background service
                    (<code>/api/*</code> HTTP surface + Basic Auth).</li>
                <li>Auto-discovers the running server via <code>service.json</code>.</li>
                <li>Native diff viewer with Accept / Reject for changed files.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }

    named("buildSearchableOptions") {
        enabled = false
    }

    named("prepareJarSearchableOptions") {
        enabled = false
    }
}
