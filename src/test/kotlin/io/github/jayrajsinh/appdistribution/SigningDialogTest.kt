package io.github.jayrajsinh.appdistribution

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBPasswordField
import java.awt.event.ActionEvent
import java.io.File
import java.nio.file.Files
import javax.swing.Action

class SigningDialogTest : BasePlatformTestCase() {

    private fun clickSave(dialog: SigningDialog) {
        val getOkAction = DialogWrapper::class.java.getDeclaredMethod("getOKAction")
        getOkAction.isAccessible = true
        (getOkAction.invoke(dialog) as Action).actionPerformed(ActionEvent(this, 0, "save"))

        // DialogWrapper applies errors (and the Save state) via invokeLater
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun passwords(dialog: SigningDialog, store: String, key: String) {
        fun field(name: String) = SigningDialog::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(dialog) as JBPasswordField
        field("storePassword").text = store
        field("keyPassword").text = key
    }

    private fun withDialog(path: String, alias: String, block: (SigningDialog) -> Unit) {
        val dialog = SigningDialog(project, path to alias)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testSaveStaysEnabledAfterMissingKeystore() = withDialog("/no/such/keystore.jks", "key0") { dialog ->
        passwords(dialog, "secret", "secret")
        clickSave(dialog)

        assertNull(dialog.result)
        assertTrue("Save must stay enabled after an error", dialog.isOKActionEnabled)
    }

    fun testSaveStaysEnabledAfterWrongPassword() {
        val keystore = createKeystore("rightpass")

        withDialog(keystore.path, "key0") { dialog ->
            passwords(dialog, "wrongpass", "wrongpass")
            clickSave(dialog)

            assertNull(dialog.result)
            assertTrue("Save must stay enabled after a wrong password", dialog.isOKActionEnabled)

            // Correcting the password then works
            passwords(dialog, "rightpass", "rightpass")
            clickSave(dialog)
            assertEquals(keystore.path, dialog.result?.storeFile)
        }
    }

    fun testPathWithSpacesAndQuotesIsAccepted() {
        val keystore = createKeystore("rightpass", folderName = "My Keys")

        withDialog("  \"${keystore.path}\"  ", "key0") { dialog ->
            passwords(dialog, "rightpass", "rightpass")
            clickSave(dialog)

            assertEquals(keystore.path, dialog.result?.storeFile)
        }
    }

    fun testNormalizePath() {
        val home = System.getProperty("user.home")
        assertEquals("/a/My Keys/k.jks", SigningDialog.normalizePath("  /a/My Keys/k.jks \n"))
        assertEquals("/a/k.jks", SigningDialog.normalizePath("'/a/k.jks'"))
        assertEquals("$home/keys/k.jks", SigningDialog.normalizePath("~/keys/k.jks"))
    }

    private fun createKeystore(password: String, folderName: String = "keys"): File {
        val dir = Files.createTempDirectory("signing-test").resolve(folderName).toFile().apply { mkdirs() }
        val keystore = File(dir, "test.jks")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path

        val exit = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", keystore.path,
            "-storepass", password, "-keypass", password, "-alias", "key0",
            "-keyalg", "RSA", "-dname", "CN=test", "-validity", "1"
        ).redirectErrorStream(true).start().waitFor()

        assertEquals(0, exit)
        return keystore
    }
}
