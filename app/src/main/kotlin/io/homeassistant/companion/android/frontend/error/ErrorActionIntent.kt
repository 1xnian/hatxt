package io.homeassistant.companion.android.frontend.error

/**
 * An action a user can take from the connection-error screen.
 *
 * Produced by [errorActions] and handled by the frontend ViewModel / host.
 */
sealed interface ErrorActionIntent {
    /** Remove the current server and relaunch the app from scratch. */
    data object RemoveServerAndRelaunch : ErrorActionIntent

    /** Clear the stored TLS client credentials (keychain) and relaunch the app from scratch. */
    data object ClearKeychainAndRelaunch : ErrorActionIntent

    /** Reload the server connection. */
    data object Refresh : ErrorActionIntent

    /**
     * Force a fresh dynamic-address lookup (TXT/SRV record) for the server and then reload.
     *
     * Used when the server is configured with a dynamic (TXT/SRV) endpoint so the user can re-read
     * the current port/IP without diving into the settings screen. Differs from [Refresh] in that it
     * always re-queries the DNS record instead of just reloading the previously resolved URL.
     */
    data object RefreshEndpoint : ErrorActionIntent

    /** Dismiss the error and keep waiting for the frontend's external-bus handshake. */
    data object Wait : ErrorActionIntent

    /** Open the app settings. */
    data object GoToSettings : ErrorActionIntent

    /** Open the OS security settings where the user can install a client certificate. */
    data object OpenSecuritySettings : ErrorActionIntent

    /** Open the store/app page for the device's current WebView provider so it can be updated. */
    data object UpdateWebView : ErrorActionIntent
}
