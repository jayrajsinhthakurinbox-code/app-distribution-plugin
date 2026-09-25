package io.github.jayrajsinh.appdistribution

import com.intellij.openapi.application.PathManager
import java.io.File

/**
 * Blocking runner for the App Distribution CLI bundled in this plugin. Call off the
 * EDT.
 *
 * The CLI jars ship in <plugin>/cli and run on the IDE's bundled Java, so
 * nothing needs installing.
 *
 * Gradle needs a JDK too, but not necessarily the IDE's (Android Studio may
 * bundle a Java newer than a project's Gradle supports). Callers that build
 * pass the project's Gradle JDK via [GradleJdk]; otherwise JAVA_HOME falls
 * back to the user's own, then the IDE's.
 *
 * For CLI development, set APPDIST_CLI to an executable (e.g. the CLI's
 * build/install/app/bin/app) to use it instead of the bundled copy.
 */
object DistributionCli {

    private const val MAIN_CLASS = "io.github.jayrajsinh.appdistribution.cli.MainKt"

    class Result(
        val exitCode: Int,
        val output: List<String>,
        val cancelled: Boolean = false
    )

    /**
     * Handle for cancelling a running command. Stops the whole process tree
     * (the CLI plus the Gradle / Firebase processes it started); Gradle
     * cancels the build when its client goes away.
     */
    class Task {
        @Volatile
        private var process: Process? = null

        @Volatile
        var isCancelled = false
            private set

        fun cancel() {
            isCancelled = true
            process?.let(::destroyTree)
        }

        internal fun attach(process: Process) {
            this.process = process
            // Cancelled before the process existed
            if (isCancelled) destroyTree(process)
        }

        private fun destroyTree(process: Process) {
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }
    }

    fun run(
        args: List<String>,
        directory: File? = null,
        environment: Map<String, String> = emptyMap(),
        task: Task? = null,
        onLine: (String) -> Unit = {}
    ): Result {

        val output = mutableListOf<String>()

        val exitCode = try {
            val process = ProcessBuilder(command() + args)
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    val env = environment()

                    // Never prompt on stdin; report via markers instead
                    env["APPDIST_NON_INTERACTIVE"] = "1"

                    if (env["JAVA_HOME"].isNullOrBlank()) {
                        env["JAVA_HOME"] = javaHome()
                    }

                    env.putAll(environment)
                }
                .start()

            task?.attach(process)

            process.outputStream.close()

            process.inputStream
                .bufferedReader()
                .forEachLine { line ->
                    output += line
                    println("[appdist] $line")
                    onLine(line)
                }

            process.waitFor()
        } catch (e: Exception) {
            output += "Could not start the App Distribution CLI: ${e.message}"
            -1
        }

        return Result(exitCode, output, cancelled = task?.isCancelled == true)
    }

    private fun command(): List<String> {

        System.getenv("APPDIST_CLI")
            ?.takeIf { it.isNotBlank() }
            ?.let { return listOf(it) }

        val cliDirectory = pluginDirectory().resolve("cli")

        if (!cliDirectory.isDirectory) {
            error("Bundled App Distribution CLI not found at $cliDirectory")
        }

        return listOf(
            File(javaHome(), "bin/java").path,
            "-cp",
            // Java expands dir/* to every jar in the directory
            "${cliDirectory.path}${File.separator}*",
            MAIN_CLASS
        )
    }

    /** <plugin> directory: this class lives in <plugin>/lib/<jar>. */
    private fun pluginDirectory(): File {
        val jar = File(PathManager.getJarPathForClass(DistributionCli::class.java)!!)
        return jar.parentFile.parentFile
    }

    private fun javaHome(): String = System.getProperty("java.home")
}
