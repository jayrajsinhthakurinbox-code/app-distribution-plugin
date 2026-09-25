package io.github.jayrajsinh.appdistribution

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

/**
 * Asks for the release keystore, like Android Studio's "Generate Signed
 * App Bundle / APK" wizard, and checks the keystore password before saving.
 */
class SigningDialog(
    private val project: Project,
    initial: Pair<String, String>?
) : DialogWrapper(project) {

    private val storeFile = TextFieldWithBrowseButton().apply {
        text = initial?.first.orEmpty()
        addBrowseFolderListener(
            project,
            FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Choose Keystore")
                .withDescription("The .jks or .keystore file used to sign release builds")
        )
    }

    private val storePassword = JBPasswordField()
    private val keyAlias = JBTextField(initial?.second.orEmpty())
    private val keyPassword = JBPasswordField()

    var result: ReleaseSigning? = null
        private set

    init {
        title = "Release Signing"
        setOKButtonText("Save and Build")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text(
                "This project doesn't sign release builds in Gradle, so enter " +
                    "the keystore you use in <b>Build › Generate Signed App Bundle / APK</b>."
            )
        }
        row("Keystore:") {
            cell(storeFile).align(AlignX.FILL)
        }
        row("Keystore password:") {
            cell(storePassword).align(AlignX.FILL)
        }
        row("Key alias:") {
            cell(keyAlias).align(AlignX.FILL)
        }
        row("Key password:") {
            cell(keyPassword).align(AlignX.FILL)
        }
        row {
            comment("Passwords are stored securely in the IDE password store for this project only.")
        }
    }.apply {
        preferredSize = java.awt.Dimension(520, preferredSize.height)
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (storeFile.text.isBlank()) storeFile.textField else storePassword

    override fun doValidate(): ValidationInfo? = when {
        !File(storeFile.text).isFile ->
            ValidationInfo("Keystore file not found", storeFile)
        storePassword.password.isEmpty() ->
            ValidationInfo("Enter the keystore password", storePassword)
        keyAlias.text.isBlank() ->
            ValidationInfo("Enter the key alias", keyAlias)
        keyPassword.password.isEmpty() ->
            ValidationInfo("Enter the key password", keyPassword)
        else -> null
    }

    override fun doOKAction() {
        val signing = ReleaseSigning(
            storeFile = storeFile.text.trim(),
            storePassword = String(storePassword.password),
            keyAlias = keyAlias.text.trim(),
            keyPassword = String(keyPassword.password)
        )

        val error = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously<String?, Exception>(
                { verify(signing) },
                "Checking Keystore",
                false,
                project
            )

        if (error != null) {
            setErrorText(error, storePassword)
            return
        }

        result = signing
        super.doOKAction()
    }

    /** Opens the keystore and looks up the alias with keytool. Null if OK. */
    private fun verify(signing: ReleaseSigning): String? {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")

        if (!keytool.canExecute()) {
            // Can't check here; Gradle will report a bad keystore itself
            return null
        }

        return try {
            val process = ProcessBuilder(
                keytool.path,
                "-list",
                "-keystore", signing.storeFile,
                // Password via environment, not arguments
                "-storepass:env", "APPDIST_STORE_PASSWORD",
                "-alias", signing.keyAlias
            )
                .redirectErrorStream(true)
                .apply { environment()["APPDIST_STORE_PASSWORD"] = signing.storePassword }
                .start()

            val output = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }

            when {
                process.exitValue() == 0 -> null
                output.contains("password was incorrect", ignoreCase = true) ||
                    output.contains("keystore password", ignoreCase = true) ->
                    "Keystore password is incorrect"
                output.contains("does not exist", ignoreCase = true) ->
                    "Key alias \"${signing.keyAlias}\" not found in this keystore"
                else ->
                    output.lines().firstOrNull { it.isNotBlank() }
                        ?.removePrefix("keytool error:")
                        ?.trim()
                        ?: "Could not open the keystore"
            }
        } catch (e: Exception) {
            null
        }
    }
}
