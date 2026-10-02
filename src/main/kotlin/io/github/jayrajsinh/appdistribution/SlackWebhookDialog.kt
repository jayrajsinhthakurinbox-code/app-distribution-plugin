package io.github.jayrajsinh.appdistribution

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Walks the user through creating a Slack Incoming Webhook, lets them test
 * it, and saves it to the IDE password store.
 */
class SlackWebhookDialog(
    private val project: Project?,
    initialUrl: String?
) : DialogWrapper(project) {

    private val urlField = JBTextField(initialUrl.orEmpty()).apply {
        emptyText.text = "https://hooks.slack.com/services/…"
    }

    private val testResult = JBLabel()

    init {
        title = "Connect Slack"
        setOKButtonText("Save")
        init()

        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                setErrorText(null)
                testResult.text = ""
                testResult.icon = null
            }
        })
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            text(
                "Post each build to a Slack channel so testers know it's ready. " +
                    "You need an <b>Incoming Webhook</b> for that channel. It takes " +
                    "about two minutes:"
            )
        }
        row {
            text(
                """
                <ol>
                  <li>Open Slack Apps and click <b>Create New App</b> › <b>From scratch</b>.
                      Name it (e.g. "App Distribution") and pick your workspace.</li>
                  <li>Open <b>Incoming Webhooks</b> and switch it <b>On</b>.</li>
                  <li>Click <b>Add New Webhook to Workspace</b>, choose the channel, and click <b>Allow</b>.</li>
                  <li>Copy the webhook URL and paste it below.</li>
                </ol>
                """.trimIndent()
            )
        }
        row {
            button("Open Slack Apps") {
                BrowserUtil.browse(AppDistributionSettings.SLACK_NEW_APP_URL)
            }
        }
        row("Webhook URL:") {
            cell(urlField).align(AlignX.FILL).resizableColumn()
        }.topGap(TopGap.SMALL)
        row {
            button("Send Test Message") { sendTestMessage() }
            cell(testResult)
        }
        row {
            comment(
                "Stored securely in the IDE password store. Anyone with this URL can post " +
                    "to the channel, so keep it private."
            )
        }
    }.apply {
        preferredSize = Dimension(560, preferredSize.height)
    }

    override fun getPreferredFocusedComponent(): JComponent = urlField

    // Save stays enabled after an error: this dialog only validates on Save
    override fun doValidate(): ValidationInfo? =
        if (AppDistributionSettings.isValidWebhook(urlField.text)) {
            null
        } else {
            ValidationInfo("Paste a Slack webhook URL starting with https://hooks.slack.com/", urlField)
                .withOKEnabled()
        }

    override fun doOKAction() {
        // Validate here too: the platform doesn't always run doValidate()
        // before Save, and an invalid URL must never be stored
        doValidate()?.let {
            setErrorInfoAll(listOf(it))
            return
        }

        val url = currentUrl()

        // The password store can be slow (keychain), so save off the EDT
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { AppDistributionSettings.getInstance().slackWebhookUrl = url },
            "Saving Slack Webhook",
            false,
            project
        )

        super.doOKAction()
    }

    private fun currentUrl() = urlField.text.trim()

    private fun sendTestMessage() {
        if (!AppDistributionSettings.isValidWebhook(currentUrl())) {
            setErrorInfoAll(listOf(doValidate()!!))
            return
        }

        val url = currentUrl()
        var result: DistributionCli.Result? = null

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                result = DistributionCli.run(
                    args = listOf("slack", "test"),
                    environment = mapOf(AppDistributionSettings.SLACK_WEBHOOK_ENV to url)
                )
            },
            "Sending Slack Test Message",
            false,
            project,
            urlField
        )

        if (result?.exitCode == 0) {
            testResult.icon = AllIcons.General.InspectionsOK
            testResult.text = "Sent. Check the channel in Slack."
        } else {
            testResult.icon = AllIcons.General.Error
            testResult.text = result?.output?.lastOrNull()
                ?.removePrefix("✗")
                ?.trim()
                ?.ifEmpty { null }
                ?: "Slack didn't accept the message."
        }
    }
}
