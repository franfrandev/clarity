import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.intellijPlatformBase)
}

group = "com.github.clarity.rust"
version = "1.0.0"

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

dependencies {
    intellijPlatform {
        rustRover("2026.1")

        bundledPlugins("com.jetbrains.rust", "org.toml.lang", "intellij.json")
        bundledModules("intellij.json.backend", "intellij.toml.json")

        testFramework(TestFrameworkType.Platform)

        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        changeNotes = """
            Initial release:
            <ul>
              <li>Adds a Usage View filter to exclude usages inside Rust <code>#[test]</code> functions.</li>
              <li>Shows the filter toggle only for Rust usage searches.</li>
            </ul>
        """.trimIndent()
    }
    instrumentCode =
        false // TODO: Execution failed for task ':instrumentCode' (registered by plugin class 'org.jetbrains.intellij.platform.gradle.plugins.project.IntelliJPlatformModulePlugin').
}

tasks {
    test {
        useJUnit()
        inputs.dir("src/test/testData")
        systemProperty("testDataPath", project.projectDir.resolve("src/test/testData").absolutePath)
    }
}
