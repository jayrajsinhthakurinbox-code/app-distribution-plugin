package io.github.jayrajsinh.appdistribution

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.service.GradleInstallationManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.File

/**
 * The JDK Android Studio uses to run Gradle for a project (Settings | Build
 * Tools | Gradle | Gradle JDK), resolving #JAVA_HOME, GRADLE_LOCAL_JAVA_HOME,
 * named JDKs, etc. exactly as the IDE's own builds do.
 */
object GradleJdk {

    /** JAVA_HOME for the project's Gradle build, or null if unresolved. */
    fun javaHome(project: Project): String? = try {
        ReadAction.nonBlocking<String?> {
            val linkedProjectPath = GradleSettings.getInstance(project)
                .linkedProjectsSettings
                .firstOrNull()
                ?.externalProjectPath
                ?: project.basePath
                ?: return@nonBlocking null

            // Looked up as a service, not GradleInstallationManager.getInstance():
            // the class became Kotlin in 2025.x, so getInstance() compiles to a
            // Companion access that doesn't exist in 2024.3
            service<GradleInstallationManager>()
                .getGradleJvmPath(project, linkedProjectPath)
                ?.takeIf { File(it, "bin/java").exists() }
        }.executeSynchronously()
    } catch (e: Exception) {
        null
    }

    fun environment(project: Project): Map<String, String> =
        javaHome(project)
            ?.let { mapOf("JAVA_HOME" to it) }
            ?: emptyMap()
}
