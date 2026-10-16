package io.homeassistant.companion.android.settings

/**
 * Switches that hide settings entries which are not useful for a mainland-China deployment.
 *
 * This is deliberately a **reversible** cut: nothing is deleted, every consumer only flips an
 * `isVisible` flag, and restoring the stock upstream behaviour is a matter of changing a single
 * `false` back to `true` here. No preference key, fragment, presenter path or dependency is
 * removed, so switching a flag back on restores the entry exactly as upstream had it.
 *
 * The entries are hidden rather than deleted because the code behind them (Wear OS onboarding,
 * the assistant-app suggestion banner, the notification history screen and the Assist category)
 * is shared with the rest of the settings screen; deleting it would mean carrying a permanent
 * diff against upstream and would risk breaking the surrounding navigation.
 */
object SettingsFeatureFlags {
    /**
     * Wear OS settings entry (`wear_settings`).
     *
     * Upstream only shows it when a paired Wear node is detected. Google Play Services based
     * Wear onboarding is not reachable for most users in the target environment, so the entry
     * is kept hidden even when a node happens to be visible.
     */
    const val showWearSettings: Boolean = false

    /**
     * Home-screen suggestion banner (`settings_suggestion`).
     *
     * Upstream picks a random suggestion from "set Home Assistant as the assistant app" and
     * "enable notifications", and keeps showing one until the user dismisses it. This is a
     * first-run nudge rather than a setting, and it is hidden here to keep the settings screen
     * limited to entries the user actually needs.
     */
    const val showSettingsSuggestion: Boolean = false

    /**
     * Notification history entry (`notification_history`).
     *
     * Upstream always shows it. The history duplicates the notification shade and is not needed
     * for connection, login or DNS resolution, so it is hidden.
     */
    const val showNotificationHistory: Boolean = false

    /**
     * Assist category (`assist`).
     *
     * Covers "Assist for Android" and the headphone-button intent switch. Google's assistant
     * integration and its voice pipeline are not usable in the target environment by default,
     * so the whole category is hidden on phones. It was already hidden on Automotive.
     */
    const val showAssistCategory: Boolean = false
}
