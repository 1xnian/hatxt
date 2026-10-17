import com.android.build.api.dsl.ApplicationExtension
import io.homeassistant.companion.android.getPluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

private const val APPLICATION_ID = "io.homeassistant.companion.android"

/**
 * A convention plugin that applies common configurations to Android application modules.
 * This centralizes configuration, preventing duplication across multiple modules.
 *
 * This plugin applies several Gradle plugins that are commonly used in all application modules,
 * including the [AndroidCommonConventionPlugin].
 *
 * After applying this plugin, the configured values can be overridden if necessary. However,
 * if extensive overrides are required, it may indicate that the configuration should be moved
 * out of this convention plugin.
 *
 * The application's `versionCode` can be set via the `VERSION_CODE` environment variable.
 *
 * A `release` signing configuration is automatically created. The keystore information can be
 * provided through the following environment variables:
 * - `KEYSTORE_PATH`: The path to the keystore file.
 * - `KEYSTORE_PASSWORD`: The password for the keystore.
 * - `KEYSTORE_ALIAS`: The alias for the key within the keystore.
 * - `KEYSTORE_ALIAS_PASSWORD`: The password for the key alias.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = libs.plugins.android.application.getPluginId())
            apply(plugin = libs.plugins.ksp.getPluginId())
            apply(plugin = libs.plugins.hilt.getPluginId())
            apply(plugin = libs.plugins.aboutlibraries.getPluginId())
            apply(plugin = libs.plugins.google.services.getPluginId())
            AndroidCommonConventionPlugin().apply(target)
            AndroidComposeConventionPlugin().apply(target)

            extensions.configure<ApplicationExtension> {
                namespace = APPLICATION_ID

                defaultConfig {
                    applicationId = APPLICATION_ID
                    targetSdk = libs.versions.androidSdk.target.get().toInt()

                    // Short, official-style version name, e.g. "2026.7.6-test17".
                    // `project.version` cannot be used here: the Reckon plugin is applied from
                    // settings.gradle.kts and infers a long git-derived version for every project
                    // ("0.0.1-beta.0.1+20261017T111527Z"), which it forced in place of any version
                    // set in the root build.gradle.kts. The readable base comes from the
                    // `appBaseVersion` Gradle property and each test build appends its own number.
                    val testBuildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
                    val baseVersion = project.findProperty("appBaseVersion")?.toString() ?: "2026.7.6"
                    versionName = "$baseVersion-test$testBuildNumber"
                    versionCode = testBuildNumber

                    val noStrictMode = project.findProperty("noStrictMode")?.toString()?.ifEmpty { "true" }
                        ?.toBoolean() ?: false
                    buildConfigField("Boolean", "NO_STRICT_MODE", noStrictMode.toString())
                }

                buildFeatures {
                    viewBinding = true
                }

                signingConfigs {
                    create("release") {
                        // A fixed test keystore is committed at app/keystore/ha-test.keystore and used
                        // by default (password "android", alias "hatest"). This makes every build —
                        // local and CI alike — share one signature, so testers can update over the
                        // existing install instead of uninstalling first. CI / a release engineer can
                        // still override every field via the KEYSTORE_* environment variables.
                        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore/ha-test.keystore")
                        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                        keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "hatest"
                        keyPassword = System.getenv("KEYSTORE_ALIAS_PASSWORD") ?: "android"
                        enableV1Signing = true
                        enableV2Signing = true
                    }
                }

                buildTypes {
                    named("debug").configure {
                        applicationIdSuffix = ".debug"
                    }
                    named("release").configure {
                        isDebuggable = false
                        isJniDebuggable = false
                        signingConfig = signingConfigs.getByName("release")
                    }
                }
            }
        }
    }
}
