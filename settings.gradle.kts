import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.10.5"
}

rootProject.name = "clarity"
include(":ui")
include(":rust")
include(":go")

dependencyResolutionManagement {
    repositories {
        mavenCentral()

        intellijPlatform {
            defaultRepositories()
        }
    }
}