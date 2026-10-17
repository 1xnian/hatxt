package io.homeassistant.companion.android.frontend.error

import androidx.annotation.StringRes
import io.homeassistant.companion.android.common.R as commonR

/**
 * A single button shown on the connection-error screen.
 *
 * @property labelRes The button label.
 * @property style How prominently to render the button.
 * @property intent What happens when the button is tapped.
 */
data class ErrorAction(@param:StringRes val labelRes: Int, val style: Style, val intent: ErrorActionIntent) {
    enum class Style { Primary, Secondary }
}

/**
 * Maps a [FrontendConnectionError] to the ordered list of actions the error screen should offer.
 *
 * The first action is the recommended recovery action; the rest are secondary. "Go to Settings"
 * is always offered as the universal escape hatch.
 *
 * @param isInternalConnection whether the active connection is the internal one, used to label the
 *   [ErrorActionIntent.Refresh] action.
 * @param hasDynamicEndpoint whether the server resolves its address from a TXT/SRV record, used to
 *   offer the [ErrorActionIntent.RefreshEndpoint] action that re-reads that record on demand.
 */
fun errorActions(
    error: FrontendConnectionError,
    isInternalConnection: Boolean,
    hasDynamicEndpoint: Boolean = false,
): List<ErrorAction> {
    val settings = ErrorAction(
        labelRes = commonR.string.open_settings,
        style = ErrorAction.Style.Secondary,
        intent = ErrorActionIntent.GoToSettings,
    )

    // Only connection-level failures benefit from re-reading the dynamic record; a TLS or
    // unrecoverable error would not be fixed by a fresh port/IP.
    val dynamicRefresh: ErrorAction? = if (hasDynamicEndpoint) {
        ErrorAction(
            labelRes = commonR.string.endpoint_force_refresh_now,
            style = ErrorAction.Style.Primary,
            intent = ErrorActionIntent.RefreshEndpoint,
        )
    } else {
        null
    }

    return when (error) {
        is FrontendConnectionError.TlsCertNotFound -> listOf(
            installCertificate(),
            refresh(isInternalConnection, ErrorAction.Style.Secondary),
            removeServer(style = ErrorAction.Style.Secondary),
            settings,
        )

        is FrontendConnectionError.TlsCertExpired -> listOf(
            installCertificate(),
            ErrorAction(
                labelRes = commonR.string.error_action_clear_credentials,
                style = ErrorAction.Style.Secondary,
                intent = ErrorActionIntent.ClearKeychainAndRelaunch,
            ),
            refresh(isInternalConnection, ErrorAction.Style.Secondary),
            settings,
        )

        is FrontendConnectionError.AuthRevoked -> listOf(removeServer(), settings)

        is FrontendConnectionError.Unrecoverable.WebViewCreationError -> listOf(
            ErrorAction(
                labelRes = commonR.string.error_action_update_webview,
                style = ErrorAction.Style.Primary,
                intent = ErrorActionIntent.UpdateWebView,
            ),
            settings,
        )

        is FrontendConnectionError.SslError,
        is FrontendConnectionError.Unrecoverable,
        -> listOf(settings.copy(style = ErrorAction.Style.Primary))

        is FrontendConnectionError.ExternalBusTimeout -> listOfNotNull(
            dynamicRefresh,
            refresh(isInternalConnection),
            settings,
            ErrorAction(
                labelRes = commonR.string.wait,
                style = ErrorAction.Style.Secondary,
                intent = ErrorActionIntent.Wait,
            ),
        )

        is FrontendConnectionError.Unknown -> listOfNotNull(
            dynamicRefresh,
            settings.copy(style = ErrorAction.Style.Primary),
            refresh(isInternalConnection, ErrorAction.Style.Secondary),
        )

        is FrontendConnectionError.Timeout,
        is FrontendConnectionError.Unreachable,
        -> listOfNotNull(
            dynamicRefresh ?: refresh(isInternalConnection),
            if (dynamicRefresh != null) refresh(isInternalConnection, ErrorAction.Style.Secondary) else null,
            settings,
        )
    }
}

private fun refresh(isInternalConnection: Boolean, style: ErrorAction.Style = ErrorAction.Style.Primary) = ErrorAction(
    labelRes = if (isInternalConnection) commonR.string.refresh_internal else commonR.string.refresh_external,
    style = style,
    intent = ErrorActionIntent.Refresh,
)

private fun installCertificate(style: ErrorAction.Style = ErrorAction.Style.Primary) = ErrorAction(
    labelRes = commonR.string.error_action_install_certificate,
    style = style,
    intent = ErrorActionIntent.OpenSecuritySettings,
)

private fun removeServer(style: ErrorAction.Style = ErrorAction.Style.Primary) = ErrorAction(
    labelRes = commonR.string.error_action_remove_server,
    style = style,
    intent = ErrorActionIntent.RemoveServerAndRelaunch,
)
