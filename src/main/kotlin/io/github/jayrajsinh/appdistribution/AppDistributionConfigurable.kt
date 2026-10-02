package io.github.jayrajsinh.appdistribution

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | App Distribution for Firebase */
class AppDistributionConfigurable : BoundConfigurable("App Distribution for Firebase") {

    private val settings = AppDistributionSettings.getInstance()

    private val status = JBLabel()
    private val setUpLink = ActionLink("") { openDialog() }
    private val removeLink = ActionLink("Remove") { removeWebhook() }

    override fun createPanel(): DialogPanel = panel {
        group("Slack") {
            row {
                text(
                    "Post builds to a Slack channel through an Incoming Webhook. " +
                        "When it's set up, tick <b>Post this build to Slack</b> on the Distribute step."
                )
            }
            row {
                cell(status)
                cell(setUpLink)
                cell(removeLink)
            }
        }
    }.also { render() }

    private fun render() {
        val saved = settings.hasSlackWebhook

        status.icon = if (saved) AllIcons.General.InspectionsOK else AllIcons.General.Information
        status.text = if (saved) "Webhook saved" else "Not set up"
        setUpLink.text = if (saved) "Change…" else "Set Up…"
        removeLink.isVisible = saved
    }

    private fun openDialog() {
        SlackWebhookDialog(null, null).showAndGet()
        render()
    }

    private fun removeWebhook() {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { settings.slackWebhookUrl = null },
            "Removing Slack Webhook",
            false,
            null
        )
        render()
    }
}
