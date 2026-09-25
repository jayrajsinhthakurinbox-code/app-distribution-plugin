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
 * in the settings file.
 */
@Service(Service.Level.APP)
@State(
    name = "AppDistributionForFirebase",
    storages = [Storage("appDistributionForFirebase.xml")]
)
class AppDistributionSettings :
    SimplePersistentStateComponent<AppDistributionSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var slackEnabled by property(false)
    }

    var slackEnabled: Boolean
        get() = state.slackEnabled
        set(value) {
            state.slackEnabled = value
        }

    /** Reads the password store: prefer calling off the EDT. */
    var slackWebhookUrl: String?
        get() = PasswordSafe.instance.getPassword(SLACK_WEBHOOK)
        set(value) {
            PasswordSafe.instance.setPassword(SLACK_WEBHOOK, value?.trim()?.ifEmpty { null })
        }

    /**
     * Environment for `appdist distribute`. Passed via environment, not
     * arguments, so the URL never shows up in `ps`. Call off the EDT.
     */
    fun distributeEnvironment(): Map<String, String> {
        val url = slackWebhookUrl

        return if (slackEnabled && !url.isNullOrBlank()) {
            mapOf(SLACK_WEBHOOK_ENV to url)
        } else {
            emptyMap()
        }
    }

    companion object {
        const val SLACK_WEBHOOK_ENV = "APPDIST_SLACK_WEBHOOK_URL"

        private val SLACK_WEBHOOK = CredentialAttributes(
            generateServiceName("App Distribution for Firebase", "Slack webhook")
        )

        fun getInstance(): AppDistributionSettings = service()

        fun isValidWebhook(url: String) = url.trim().startsWith("https://hooks.slack.com/")
    }
}
