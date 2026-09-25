package io.github.jayrajsinh.appdistribution

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import javax.swing.JCheckBox

/** Settings | Tools | App Distribution for Firebase */
class AppDistributionConfigurable : BoundConfigurable("App Distribution for Firebase") {

    private val settings = AppDistributionSettings.getInstance()

    private val webhookField = JBPasswordField()

    // The webhook lives in the password store, so it's tracked by hand
    private var loadedWebhook = ""

    override fun createPanel(): DialogPanel = panel {
        group("Slack") {
            lateinit var enabled: JCheckBox

            row {
                enabled = checkBox("Announce each distribution in Slack")
                    .bindSelected(settings::slackEnabled)
                    .component
            }

            indent {
                row("Incoming Webhook URL:") {
                    cell(webhookField)
                        .align(AlignX.FILL)
                        .resizableColumn()
                        .comment(
                            "Create one in Slack: <a href=\"https://api.slack.com/messaging/webhooks\">" +
                                "Sending messages using incoming webhooks</a>. It posts to the channel " +
                                "you pick when creating it. Stored in the IDE password store."
                        )
                }.enabledIf(enabled.selected)

                row {
                    button("Send Test Message") { sendTestMessage() }
                }.enabledIf(enabled.selected)
            }
        }
    }

    override fun reset() {
        super.reset()
        loadedWebhook = settings.slackWebhookUrl.orEmpty()
        webhookField.text = loadedWebhook
    }

    override fun isModified(): Boolean =
        super.isModified() || currentWebhook() != loadedWebhook

    override fun apply() {
        val webhook = currentWebhook()

        if (webhook.isNotEmpty() && !AppDistributionSettings.isValidWebhook(webhook)) {
            throw ConfigurationException("The webhook URL should start with https://hooks.slack.com/")
        }

        super.apply()

        if (webhook != loadedWebhook) {
            settings.slackWebhookUrl = webhook
            loadedWebhook = webhook
        }
    }

    private fun currentWebhook() = String(webhookField.password).trim()

    /** Tests the URL currently in the form, saved or not. */
    private fun sendTestMessage() {
        val webhook = currentWebhook()

        if (!AppDistributionSettings.isValidWebhook(webhook)) {
            Messages.showWarningDialog(
                webhookField,
                "Enter a Slack Incoming Webhook URL (https://hooks.slack.com/…) first.",
                "App Distribution for Firebase"
            )
            return
        }

        var result: DistributionCli.Result? = null

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                result = DistributionCli.run(
                    args = listOf("slack", "test"),
                    environment = mapOf(AppDistributionSettings.SLACK_WEBHOOK_ENV to webhook)
                )
            },
            "Sending Slack Test Message",
            false,
            null,
            webhookField
        )

        if (result?.exitCode == 0) {
            Messages.showInfoMessage(webhookField, "Test message sent. Check Slack.", "App Distribution for Firebase")
        } else {
            val reason = result?.output?.lastOrNull().orEmpty().removePrefix("✗").trim()
            Messages.showErrorDialog(webhookField, "Slack test failed.\n\n$reason", "App Distribution for Firebase")
        }
    }
}
