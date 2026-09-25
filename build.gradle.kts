import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        // Local Android Studio to compile and run against; override with
        // -PandroidStudioPath=/path/to/Android Studio.app/Contents
        local(
            providers.gradleProperty("androidStudioPath")
                .getOrElse("/Applications/Android Studio.app/Contents")
        )

        // Resolves each project's configured Gradle JDK for the release build
        bundledPlugin("com.intellij.gradle")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")

        ideaVersion {
            // Android Studio Meerkat (2024.3) / IntelliJ IDEA 2024.3 and newer
            sinceBuild = "243"
            untilBuild = provider { null }
        }

        // "What's new" on the Marketplace, from CHANGELOG.md
        val changelog = project.changelog // local variable for configuration cache compatibility
        changeNotes = providers.gradleProperty("version").map { version ->
            with(changelog) {
                renderItem(
                    (getOrNull(version) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML
                )
            }
        }
    }

    // Marketplace signing: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace upload token: https://plugins.jetbrains.com/author/me/tokens
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = "https://github.com/jayrajsinhthakurinbox-code/app-distribution-plugin"
}

// ---------------------------------------------------------------------------
// Bundled CLI
//
// The appdist CLI (../app-distribution-cli) is built and shipped inside the
// plugin under <plugin>/cli, where the plugin runs it with the IDE's bundled
// Java. Users don't need to install the CLI or Java separately.
// ---------------------------------------------------------------------------

val cliDir = layout.projectDirectory.dir(
    providers.gradleProperty("appDistributionCliDir").getOrElse("../app-distribution-cli")
)
val cliLibs = cliDir.dir("build/install/appdist/lib")

val buildCli = tasks.register<Exec>("buildCli") {
    description = "Builds the appdist CLI so it can be bundled into the plugin."

    workingDir = cliDir.asFile
    commandLine("./gradlew", "installDist", "--quiet")

    inputs.dir(cliDir.dir("src/main"))
    inputs.files(
        cliDir.file("build.gradle.kts"),
        cliDir.file("settings.gradle.kts"),
        cliDir.file("gradle.properties")
    )
    outputs.dir(cliLibs)
}

tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(buildCli)

    from(cliLibs) {
        into(pluginName.map { "$it/cli" })
    }

    // Gemini's indexer can deadlock the sandbox IDE on startup; not needed here
    disabledPlugins.add("com.google.tools.ij.aiplugin")
}
