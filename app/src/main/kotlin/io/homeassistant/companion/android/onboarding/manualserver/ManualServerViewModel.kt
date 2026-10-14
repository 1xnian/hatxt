package io.homeassistant.companion.android.onboarding.manualserver

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.data.endpoint.DnsClient
import io.homeassistant.companion.android.common.data.endpoint.EndpointProbe
import io.homeassistant.companion.android.common.data.endpoint.EndpointRejectionReason
import io.homeassistant.companion.android.common.data.endpoint.EndpointResolutionMode
import io.homeassistant.companion.android.common.data.endpoint.EndpointResolver
import io.homeassistant.companion.android.common.data.endpoint.EndpointSettings
import io.homeassistant.companion.android.common.data.endpoint.EndpointUrlFactory
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Backs the "enter your server address" onboarding step.
 *
 * Besides a plain URL the field accepts a DNS record reference:
 *
 * * `txt://ha.example.com` reads the address from a TXT record.
 * * `srv://_8123._tcp.example.com` reads priority, weight, port and target from an SRV record.
 *
 * Both are handled by the shared [EndpointResolver], so onboarding and the settings screen cannot
 * drift apart and there is exactly one implementation of the record formats.
 */
@HiltViewModel
internal class ManualServerViewModel @Inject constructor(
    dnsClient: DnsClient,
    endpointProbe: EndpointProbe,
) : ViewModel() {
    private val serverUrlMutableFlow = MutableStateFlow("")
    val serverUrlFlow = serverUrlMutableFlow.asStateFlow()

    private val isServerUrlValidMutableFlow = MutableStateFlow(false)
    val isServerUrlValidFlow = isServerUrlValidMutableFlow.asStateFlow()

    /** Non-null when the last record lookup failed. Rendered as the field's supporting text. */
    private val lookupFailureMutableFlow = MutableStateFlow<LookupFailure?>(null)
    val lookupFailureFlow = lookupFailureMutableFlow.asStateFlow()

    private val resolver = EndpointResolver(dnsClient = dnsClient, probe = endpointProbe)

    fun onServerUrlChange(url: String) {
        serverUrlMutableFlow.update { url }
        lookupFailureMutableFlow.update { null }
        validateServerUrl(url)
    }

    /** Resolves a record-based address and hands the resulting URL to the onboarding flow. */
    fun connectToUrl(input: String, onResolved: (URL) -> Unit) {
        viewModelScope.launch {
            val trimmed = input.trim()
            val mode = resolutionModeOf(trimmed)
            val resolved = if (mode == null) {
                trimmed
            } else {
                resolveRecord(mode, trimmed.substringAfter("://").trim()) ?: return@launch
            }

            runCatching { URL(resolved) }
                .onSuccess { url ->
                    lookupFailureMutableFlow.update { null }
                    onResolved(url)
                }
                .onFailure {
                    Timber.e(it, "Resolved Home Assistant URL is invalid")
                    lookupFailureMutableFlow.update { LookupFailure.InvalidResult }
                }
        }
    }

    /**
     * Resolves [recordName] with the shared resolver.
     *
     * Onboarding runs before any server exists, so there is no stored plaintext consent. An
     * HTTPS endpoint therefore has to be confirmed by the probe; when it cannot be, the lookup
     * fails and the user is asked to type the `http://` address explicitly. That keeps a downgrade
     * an explicit decision instead of something the app does behind the user's back.
     */
    private suspend fun resolveRecord(mode: EndpointResolutionMode, recordName: String): String? {
        val settings = EndpointSettings(
            mode = mode,
            txtRecordName = if (mode == EndpointResolutionMode.TXT) recordName else "",
            srvRecordName = if (mode == EndpointResolutionMode.SRV) recordName else "",
            allowHttpFallback = true,
        )
        val result = withContext(Dispatchers.IO) { resolver.resolve(settings, plaintextConsent = false) }
        result.endpoint?.let { return it.url }

        val error = result.error
        Timber.e("Unable to resolve %s record %s: %s", mode, recordName, error?.message)
        lookupFailureMutableFlow.update {
            if (error?.reason == EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED) {
                LookupFailure.PlaintextConfirmationRequired
            } else {
                LookupFailure.Message(error?.message ?: recordName)
            }
        }
        return null
    }

    private fun resolutionModeOf(input: String): EndpointResolutionMode? = when {
        input.startsWith(TXT_SCHEME, ignoreCase = true) -> EndpointResolutionMode.TXT
        input.startsWith(SRV_SCHEME, ignoreCase = true) -> EndpointResolutionMode.SRV
        else -> null
    }

    private fun validateServerUrl(url: String) {
        val trimmed = url.trim()
        isServerUrlValidMutableFlow.update {
            when (resolutionModeOf(trimmed)) {
                EndpointResolutionMode.TXT ->
                    EndpointUrlFactory.isValidHostname(trimmed.substringAfter("://"))

                EndpointResolutionMode.SRV ->
                    EndpointUrlFactory.isValidSrvQueryName(trimmed.substringAfter("://"))

                else -> URLUtil.isValidUrl(trimmed) && runCatching { URL(trimmed) }.isSuccess
            }
        }
    }

    /** Why a record lookup could not produce an address. */
    internal sealed interface LookupFailure {
        data class Message(val detail: String) : LookupFailure
        data object PlaintextConfirmationRequired : LookupFailure
        data object InvalidResult : LookupFailure
    }

    private companion object {
        const val TXT_SCHEME = "txt://"
        const val SRV_SCHEME = "srv://"
    }
}
