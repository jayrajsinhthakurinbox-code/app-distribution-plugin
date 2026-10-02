package io.github.jayrajsinh.appdistribution

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-wide settings. The Slack webhook URL is a secret (anyone with
 * it can post to the channel), so it lives in the IDE password store, not
 * in the settings file. Whether one is saved is kept here too, so the UI can
 * check without touching the password store on the UI thread.
 */
@Service(Service.Level.APP)
@State(
    name = "AppDistributionForFirebase",
    storages = [Storage("appDistributionForFirebase.xml")]
)
class AppDistributionSettings :
    SimplePersistentStateComponent<AppDistributionSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var slackWebhookSaved by property(false)
    }

    /** Whether a Slack webhook is saved. Cheap; safe on the EDT. */
    val hasSlackWebhook: Boolean
        get() = state.slackWebhookSaved

    /** Reads the password store: call off the EDT. */
    var slackWebhookUrl: String?
        get() = PasswordSafe.instance.getPassword(SLACK_WEBHOOK)
        set(value) {
            val url = value?.trim()?.ifEmpty { null }
            PasswordSafe.instance.setPassword(SLACK_WEBHOOK, url)
            state.slackWebhookSaved = url != null
        }

    /**
     * Re-reads the password store to bring [hasSlackWebhook] up to date (for
     * webhooks saved by earlier versions). Call off the EDT.
     */
    fun refreshSlackWebhookSaved(): Boolean {
        val saved = !slackWebhookUrl.isNullOrBlank()
        state.slackWebhookSaved = saved
        return saved
    }

    /**
     * Environment for `appdist distribute`. Passed via environment, not
     * arguments, so the URL never shows up in `ps`. Call off the EDT.
     */
    fun distributeEnvironment(postToSlack: Boolean): Map<String, String> {
        if (!postToSlack) return emptyMap()

        val url = slackWebhookUrl
        return if (url.isNullOrBlank()) emptyMap() else mapOf(SLACK_WEBHOOK_ENV to url)
    }

    companion object {
        const val SLACK_WEBHOOK_ENV = "APPDIST_SLACK_WEBHOOK_URL"

        /** Where Slack users create a new app (and its incoming webhook). */
        const val SLACK_NEW_APP_URL = "https://api.slack.com/apps?new_app=1"

        private val SLACK_WEBHOOK = CredentialAttributes(
            generateServiceName("App Distribution for Firebase", "Slack webhook")
        )

        fun getInstance(): AppDistributionSettings = service()

        fun isValidWebhook(url: String) = url.trim().startsWith("https://hooks.slack.com/")
    }
}
