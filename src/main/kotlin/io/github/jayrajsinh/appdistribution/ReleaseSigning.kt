package io.github.jayrajsinh.appdistribution

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import java.io.File

/**
 * Keystore used to sign release builds, for projects that don't define a
 * release signingConfig in Gradle (they sign through Android Studio's
 * "Generate Signed App Bundle / APK" wizard instead).
 *
 * It's handed to Gradle the same way that wizard does, through the
 * android.injected.signing.* properties, but as ORG_GRADLE_PROJECT_*
 * environment variables so passwords never appear in process arguments.
 *
 * Paths and aliases are stored per project; passwords live in the IDE
 * Password Safe (macOS Keychain by default).
 */
data class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
) {

    fun gradleEnvironment(): Map<String, String> = mapOf(
        "$GRADLE_PROPERTY_PREFIX.store.file" to storeFile,
        "$GRADLE_PROPERTY_PREFIX.store.password" to storePassword,
        "$GRADLE_PROPERTY_PREFIX.key.alias" to keyAlias,
        "$GRADLE_PROPERTY_PREFIX.key.password" to keyPassword
    )

    companion object {

        private const val GRADLE_PROPERTY_PREFIX =
            "ORG_GRADLE_PROJECT_android.injected.signing"

        private const val STORE_FILE_KEY = "appdist.signing.storeFile"
        private const val KEY_ALIAS_KEY = "appdist.signing.keyAlias"

        /** Saved signing for this project, or null. Reads Password Safe: call off the EDT. */
        fun load(project: Project): ReleaseSigning? {
            val (storeFile, keyAlias) = savedSummary(project) ?: return null

            val secret = PasswordSafe.instance.get(attributes(project))
                ?.getPasswordAsString()
                ?: return null

            return try {
                val json = JsonParser.parseString(secret).asJsonObject

                ReleaseSigning(
                    storeFile = storeFile,
                    storePassword = json["storePassword"].asString,
                    keyAlias = keyAlias,
                    keyPassword = json["keyPassword"].asString
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Keystore path and alias only, safe to read on the EDT. */
        fun savedSummary(project: Project): Pair<String, String>? {
            val properties = PropertiesComponent.getInstance(project)

            val storeFile = properties.getValue(STORE_FILE_KEY) ?: return null
            val keyAlias = properties.getValue(KEY_ALIAS_KEY) ?: return null

            return storeFile to keyAlias
        }

        /**
         * Saves the keystore for this project. Returns false if the IDE's
         * password store didn't keep the passwords (e.g. it's set to not
         * save passwords). Call off the EDT.
         */
        fun save(project: Project, signing: ReleaseSigning): Boolean {
            val properties = PropertiesComponent.getInstance(project)
            properties.setValue(STORE_FILE_KEY, signing.storeFile)
            properties.setValue(KEY_ALIAS_KEY, signing.keyAlias)

            // Both passwords in one entry, so it's a single keychain item
            val secret = JsonObject().apply {
                addProperty("storePassword", signing.storePassword)
                addProperty("keyPassword", signing.keyPassword)
            }.toString()

            PasswordSafe.instance.set(
                attributes(project),
                Credentials(signing.keyAlias, secret)
            )

            // Confirm it can be read back
            return load(project) == signing
        }

        fun clear(project: Project) {
            val properties = PropertiesComponent.getInstance(project)
            properties.unsetValue(STORE_FILE_KEY)
            properties.unsetValue(KEY_ALIAS_KEY)

            PasswordSafe.instance.set(attributes(project), null)
        }

        /**
         * Keystore path and alias last used in Android Studio's
         * "Generate Signed App Bundle / APK" wizard for this project.
         */
        fun wizardDefaults(project: Project): Pair<String, String>? = try {
            val workspace = File(project.basePath ?: "", ".idea/workspace.xml")

            JDOMUtil.load(workspace)
                .getChildren("component")
                .firstOrNull { it.getAttributeValue("name") == "GenerateSignedApkSettings" }
                ?.getChildren("option")
                ?.associate { it.getAttributeValue("name") to it.getAttributeValue("value") }
                ?.let { options ->
                    val path = options["KEY_STORE_PATH"]?.takeIf { it.isNotBlank() }
                    val alias = options["KEY_ALIAS"]?.takeIf { it.isNotBlank() }
                    if (path != null && alias != null) path to alias else null
                }
        } catch (e: Exception) {
            null
        }

        private fun attributes(project: Project) = CredentialAttributes(
            generateServiceName(
                "App Distribution for Firebase",
                "release signing ${project.locationHash}"
            )
        )
    }
}
