package io.homeassistant.companion.android.onboarding.manualserver

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.data.endpoint.DnsClient
import io.homeassistant.companion.android.common.data.endpoint.EndpointProbe
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
 *
 * ## Plaintext connections
 *
 * A record that points at a server without HTTPS (`117.183.5.29:13481`, `tcp://…`) is **not**
 * silently turned into an `http` address. The lookup fails with
 * [LookupFailure.PlaintextConfirmationRequired], carrying the endpoint that actually answered over
 * plaintext, and the screen offers to use it. Only when the user taps that action does
 * [connectToUrlWithPlaintextConsent] run the lookup again with consent, and the consent is passed
 * to the resolver for that one request. Nothing is stored, because the plain `http` URL that comes
 * out becomes the server address the app uses from then on.
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

    /**
     * True while a record lookup is in flight.
     *
     * A lookup can legitimately take a few seconds, because several resolvers may have to be tried
     * before one answers. The screen disables its actions instead of looking frozen.
     */
    private val isResolvingMutableFlow = MutableStateFlow(false)
    val isResolvingFlow = isResolvingMutableFlow.asStateFlow()

    private val resolver = EndpointResolver(dnsClient = dnsClient, probe = endpointProbe)

    fun onServerUrlChange(url: String) {
        serverUrlMutableFlow.update { url }
        lookupFailureMutableFlow.update { null }
        validateServerUrl(url)
    }

    /** Resolves a record-based address and hands the resulting URL to the onboarding flow. */
    fun connectToUrl(input: String, onResolved: (URL) -> Unit) {
        resolveAndConnect(input, plaintextConsent = false, onResolved = onResolved)
    }

    /**
     * Retries the lookup with an explicit plaintext consent.
     *
     * Only ever called from the action the screen shows for
     * [LookupFailure.PlaintextConfirmationRequired], i.e. after the user was told that the address
     * does not serve HTTPS and accepted that.
     */
    fun connectToUrlWithPlaintextConsent(input: String, onResolved: (URL) -> Unit) {
        resolveAndConnect(input, plaintextConsent = true, onResolved = onResolved)
    }

    private fun resolveAndConnect(
        input: String,
        plaintextConsent: Boolean,
        onResolved: (URL) -> Unit,
    ) {
        viewModelScope.launch {
            if (isResolvingMutableFlow.value) return@launch
            isResolvingMutableFlow.update { true }
            try {
                val trimmed = input.trim()
                val mode = resolutionModeOf(trimmed)
                val resolved = if (mode == null) {
                    trimmed
                } else {
                    when (val outcome = resolveRecord(mode, trimmed.substringAfter("://").trim(), plaintextConsent)) {
                        is RecordResolution.Resolved -> outcome.url

                        is RecordResolution.NeedsPlaintextConsent -> {
                            lookupFailureMutableFlow.update {
                                LookupFailure.PlaintextConfirmationRequired(outcome.url, outcome.detail)
                            }
                            return@launch
                        }

                        is RecordResolution.Failed -> {
                            lookupFailureMutableFlow.update { LookupFailure.Message(outcome.detail) }
                            return@launch
                        }
                    }
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
            } finally {
                isResolvingMutableFlow.update { false }
            }
        }
    }

    /**
     * Resolves [recordName] with the shared resolver.
     *
     * Onboarding runs before any server exists, so there is no stored plaintext consent. A plaintext
     * endpoint is therefore reported back as [RecordResolution.NeedsPlaintextConsent] and the user
     * decides, instead of the app deciding for them.
     */
    private suspend fun resolveRecord(
        mode: EndpointResolutionMode,
        recordName: String,
        plaintextConsent: Boolean,
    ): RecordResolution {
        val settings = EndpointSettings(
            mode = mode,
            txtRecordName = if (mode == EndpointResolutionMode.TXT) recordName else "",
            srvRecordName = if (mode == EndpointResolutionMode.SRV) recordName else "",
            allowHttpFallback = true,
        )
        val result = withContext(Dispatchers.IO) {
            resolver.resolve(settings, plaintextConsent = plaintextConsent)
        }
        result.endpoint?.let { return RecordResolution.Resolved(it.url) }

        val error = result.error
        // The reason, the resolver trail and the record name are all safe to log: embedded
        // credentials are rejected while a record is parsed, so a record value never holds a secret.
        Timber.e(
            "Unable to resolve %s record %s: %s [%s]",
            mode,
            recordName,
            error?.reason?.name,
            error?.diagnostics ?: error?.message,
        )

        result.pendingPlaintextEndpoint?.let { plaintext ->
            return RecordResolution.NeedsPlaintextConsent(plaintext.url, error?.message)
        }
        return RecordResolution.Failed(error?.message ?: recordName)
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

    /** Outcome of one record lookup. */
    private sealed interface RecordResolution {
        data class Resolved(val url: String) : RecordResolution

        /**
         * The address answers, but only over plaintext. @property url is that address, so the screen
         * can offer it verbatim.
         */
        data class NeedsPlaintextConsent(val url: String, val detail: String?) : RecordResolution

        data class Failed(val detail: String) : RecordResolution
    }

    /** Why a record lookup could not produce an address. */
    internal sealed interface LookupFailure {
        data class Message(val detail: String) : LookupFailure

        /**
         * @property url plaintext address that was confirmed to answer.
         * @property detail why HTTPS could not be used.
         */
        data class PlaintextConfirmationRequired(val url: String, val detail: String?) : LookupFailure

        data object InvalidResult : LookupFailure
    }

    private companion object {
        const val TXT_SCHEME = "txt://"
        const val SRV_SCHEME = "srv://"
    }
}
