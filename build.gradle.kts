import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

val pluginDescription = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
    val lines = readme.lines()
    val start = lines.indexOf("<!-- Plugin description -->")
    val end = lines.indexOf("<!-- Plugin description end -->")
    require(start in 0..<end) { "README.md must contain the plugin description markers" }
    markdownToHTML(lines.subList(start + 1, end).joinToString("\n"))
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        description = pluginDescription
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

val layoutInterchangeTestTasks = mapOf(
    "testLayoutInterchange2025_3" to ("2025.3.6" to "253.33813.25"),
    "testLayoutInterchange2026_1" to ("2026.1.4" to "261.26222.65"),
    "testLayoutInterchange2026_2" to ("2026.2.0.1" to "262.8665.337"),
)

intellijPlatformTesting {
    testIde {
        layoutInterchangeTestTasks.forEach { (taskName, versions) ->
            val (ideVersion, testFrameworkVersion) = versions
            register(taskName) {
                type = IntelliJPlatformType.IntellijIdea
                version = ideVersion
                testFramework(TestFrameworkType.Platform, testFrameworkVersion)
                task {
                    filter {
                        includeTestsMatching(
                            "io.github.khopland.LayoutProfilePlatformTest.test*Import*",
                        )
                        includeTestsMatching(
                            "io.github.khopland.LayoutProfilePlatformTest.testSelectedProfileCanBeExported",
                        )
                    }
                }
            }
        }
    }
}

tasks.register("testLayoutInterchangeAll") {
    group = "verification"
    description = "Runs native layout import/export tests against every supported IntelliJ release line."
    dependsOn(layoutInterchangeTestTasks.keys)
}

tasks.processResources {
    from("LICENSE") {
        into("META-INF")
    }
}
