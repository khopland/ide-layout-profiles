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

val layoutAdapterTestTasks = mapOf(
    "testLayoutAdapter2025_3" to ("2025.3.6" to "253.33813.25"),
    "testLayoutAdapter2026_1" to ("2026.1.4" to "261.26222.65"),
    "testLayoutAdapter2026_2" to ("2026.2.0.1" to "262.8665.337"),
)

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
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            layoutAdapterTestTasks.values.forEach { (ideVersion, _) ->
                create(IntelliJPlatformType.IntellijIdea, ideVersion)
            }
            create(IntelliJPlatformType.WebStorm, "2026.2")
        }
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

intellijPlatformTesting {
    testIde {
        layoutAdapterTestTasks.forEach { (taskName, versions) ->
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
                            "io.github.khopland.LayoutProfilePlatformTest.test*Export*",
                        )
                        includeTestsMatching("io.github.khopland.LayoutProfilePlatformTest.testSaveAndApplyRoundTrip")
                        includeTestsMatching("io.github.khopland.LayoutProfilePlatformTest.testAnyProfileCanBeUpdatedById")
                        includeTestsMatching(
                            "io.github.khopland.LayoutProfilePlatformTest.testMoreThanTenProfilesCanBeSavedAndApplied",
                        )
                        includeTestsMatching(
                            "io.github.khopland.LayoutProfilePlatformTest.testActiveProfileCanBeAppliedToEveryProject",
                        )
                        includeTestsMatching(
                            "io.github.khopland.LayoutProfilePlatformTest.testApplyToAllContinuesAfterAProjectFailure",
                        )
                        includeTestsMatching(
                            "io.github.khopland.LayoutProfilePlatformTest.testStartupActivityReportsMissingNativeLayout",
                        )
                    }
                }
            }
        }
    }
}

tasks.register("testLayoutAdapterAll") {
    group = "verification"
    description = "Runs native layout save, apply, import, export, and rollback tests against every supported IntelliJ release line."
    dependsOn(layoutAdapterTestTasks.keys)
}

tasks.register("testLayoutInterchangeAll") {
    group = "verification"
    description = "Compatibility alias for testLayoutAdapterAll."
    dependsOn("testLayoutAdapterAll")
}

tasks.processResources {
    from("LICENSE") {
        into("META-INF")
    }
}
