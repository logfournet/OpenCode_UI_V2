plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "2.0.3"

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
            sinceBuild = "242"
        }

        changeNotes = """
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
