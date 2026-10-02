package io.github.jayrajsinh.appdistribution

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import java.awt.event.ActionEvent
import javax.swing.Action

class SlackCheckboxTest : BasePlatformTestCase() {

    private val settings get() = AppDistributionSettings.getInstance()

    override fun tearDown() {
        try {
            settings.slackWebhookUrl = null
        } finally {
            super.tearDown()
        }
    }

    /** Opens the Distribute step and returns its Slack checkbox and link. */
    private fun distributeStep(): Pair<JBCheckBox, ActionLink> {
        val panel = AppDistributionToolWindowFactory.ReleasePanel(project)

        // ReleaseManifest(projectPath, apkPath, applicationId, versionName, versionCode, appName, buildType)
        val manifestClass = Class.forName(
            "io.github.jayrajsinh.appdistribution.AppDistributionToolWindowFactory\$ReleaseManifest"
        )
        val manifest = manifestClass.declaredConstructors
            .first { it.parameterCount == 7 && !it.isSynthetic }
            .newInstance("/p", "/p/app.apk", "com.example", "1.0", 1, "Example", "release")

        AppDistributionToolWindowFactory.ReleasePanel::class.java
            .getDeclaredMethod("showDistributeStep", manifestClass)
            .apply { isAccessible = true }
            .invoke(panel, manifest)

        val checkbox = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
            .single { it.text == "Post this build to Slack" }
        val link = UIUtil.findComponentsOfType(panel, ActionLink::class.java)
            .single { it.text.contains("Slack") || it.text.contains("webhook") }

        return checkbox to link
    }

    fun testWithoutWebhookTheBoxIsUncheckedAndOffersSetup() {
        settings.slackWebhookUrl = null

        val (checkbox, link) = distributeStep()

        assertFalse(checkbox.isSelected)
        assertEquals("Set up Slack…", link.text)
    }

    fun testWithWebhookTheBoxIsOnAndRemembered() {
        settings.slackWebhookUrl = "https://hooks.slack.com/services/T000/B000/xxx"

        val (checkbox, link) = distributeStep()

        assertTrue("On by default once a webhook is saved", checkbox.isSelected)
        assertEquals("Change webhook…", link.text)

        checkbox.doClick()

        assertFalse(checkbox.isSelected)
        assertFalse(PropertiesComponent.getInstance(project).getBoolean("appdist.postToSlack", true))
    }

    fun testDialogRejectsInvalidUrlButKeepsSaveEnabled() {
        val dialog = SlackWebhookDialog(project, "not a webhook")
        try {
            clickSave(dialog)

            assertFalse(settings.hasSlackWebhook)
            assertTrue("Save must stay enabled", dialog.isOKActionEnabled)

            urlField(dialog).text = "https://hooks.slack.com/services/T000/B000/xxx"
            clickSave(dialog)

            assertTrue(settings.hasSlackWebhook)
            assertEquals("https://hooks.slack.com/services/T000/B000/xxx", settings.slackWebhookUrl)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun urlField(dialog: SlackWebhookDialog) =
        SlackWebhookDialog::class.java.getDeclaredField("urlField")
            .apply { isAccessible = true }.get(dialog) as JBTextField

    private fun clickSave(dialog: DialogWrapper) {
        val getOkAction = DialogWrapper::class.java.getDeclaredMethod("getOKAction")
        getOkAction.isAccessible = true
        (getOkAction.invoke(dialog) as Action).actionPerformed(ActionEvent(this, 0, "save"))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
