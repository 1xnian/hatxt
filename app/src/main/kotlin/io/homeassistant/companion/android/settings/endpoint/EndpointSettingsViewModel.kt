package io.homeassistant.companion.android.settings.endpoint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.data.endpoint.EndpointRefreshOutcome
import io.homeassistant.companion.android.common.data.endpoint.EndpointRefreshPolicy
import io.homeassistant.companion.android.common.data.endpoint.EndpointRejectionReason
import io.homeassistant.companion.android.common.data.endpoint.EndpointRepository
import io.homeassistant.companion.android.common.data.endpoint.EndpointResolutionMode
import io.homeassistant.companion.android.common.data.endpoint.EndpointSettings
import io.homeassistant.companion.android.common.data.endpoint.EndpointSnapshot
import io.homeassistant.companion.android.common.data.endpoint.EndpointStalePolicy
import io.homeassistant.companion.android.common.data.endpoint.EndpointUpdate
import io.homeassistant.companion.android.common.data.endpoint.EndpointUrlFactory
import io.homeassistant.companion.android.common.data.servers.ServerManager
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the settings screen shows after a manual refresh. Mapped to a string by the view. */
sealed interface EndpointFeedback {
    data object Succeeded : EndpointFeedback
    data object Unchanged : EndpointFeedback
    data class Updated(val url: String) : EndpointFeedback
    data class Failed(val message: String) : EndpointFeedback
    data object Concurrent : EndpointFeedback
    data object Offline : EndpointFeedback
    data object NotConfigured : EndpointFeedback
    data object NotDue : EndpointFeedback

    /** HTTPS could not be confirmed and connecting in clear text needs the user's consent. */
    data class PlaintextConsentRequired(val message: String) : EndpointFeedback
}

/**
 * State of the dynamic address settings screen.
 *
 * @property available false when the screen has no server to configure, for example when no server
 *   has been added yet.
 * @property loaded true once the persisted settings were read. Until then the form is not rendered
 *   so a user cannot overwrite stored values with defaults.
 */
data class EndpointSettingsUiState(
    val available: Boolean = true,
    val loaded: Boolean = false,
    val settings: EndpointSettings = EndpointSettings(),
    val snapshot: EndpointSnapshot = EndpointSnapshot(),
    val refreshing: Boolean = false,
    val feedback: EndpointFeedback? = null,
    val customIntervalInput: String = "",
    val defaultPortInput: String = EndpointSettings.DEFAULT_HOME_ASSISTANT_PORT.toString(),
    val recordNameError: Boolean = false,
    val intervalError: Boolean = false,
    val portError: Boolean = false,
) {
    /** True while the app has no server, so the screen shows an explanation instead of a form. */
    val isUnavailable: Boolean get() = !available
}

/**
 * Backs the "dynamic address" section of the settings.
 *
 * All persistence and all DNS work happens in [EndpointRepository]; this view model only translates
 * user input into settings updates and renders the observable snapshot.
 */
@HiltViewModel
class EndpointSettingsViewModel @Inject constructor(
    private val serverManager: ServerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(EndpointSettingsUiState())
    val state: StateFlow<EndpointSettingsUiState> = _state.asStateFlow()

    private var serverId: Int? = null
    private var observeJob: Job? = null
    private var repository: EndpointRepository? = null

    /**
     * Starts observing [serverId]. Calling it twice for the same server is a no-op so a fragment
     * recreation cannot end up with two collectors writing to the same state.
     */
    fun observe(serverId: Int) {
        if (this.serverId == serverId) return
        this.serverId = serverId

        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            val repo = try {
                serverManager.endpointRepository(serverId)
            } catch (e: IllegalStateException) {
                Timber.w(e, "No server with id %d, dynamic address settings unavailable", serverId)
                _state.update { it.copy(available = false, loaded = true) }
                return@launch
            }
            repository = repo

            launch {
                repo.snapshot.collect { snapshot ->
                    _state.update { it.copy(snapshot = snapshot, refreshing = snapshot.isResolving) }
                }
            }

            repo.reload()
            val settings = repo.settings()
            _state.update {
                it.copy(
                    available = true,
                    loaded = true,
                    settings = settings,
                    customIntervalInput = if (settings.refreshIntervalSeconds in
                        EndpointRefreshPolicy.PRESET_INTERVALS_SECONDS
                    ) {
                        ""
                    } else {
                        settings.refreshIntervalSeconds.toString()
                    },
                    defaultPortInput = settings.defaultPort.toString(),
                    portError = !EndpointUrlFactory.isValidPort(settings.defaultPort),
                )
            }
        }
    }

    fun setMode(mode: EndpointResolutionMode) = updateSettings { it.copy(mode = mode) }

    fun setTxtRecordName(value: String) = updateSettings {
        it.copy(txtRecordName = value).also { updated -> validateRecordName(updated) }
    }

    fun setSrvRecordName(value: String) = updateSettings {
        it.copy(srvRecordName = value).also { updated -> validateRecordName(updated) }
    }

    /** Applies one of [EndpointRefreshPolicy.PRESET_INTERVALS_SECONDS]. Clears a custom value. */
    fun setPresetInterval(seconds: Long) {
        _state.update { it.copy(customIntervalInput = "", intervalError = false) }
        updateSettings { it.copy(refreshIntervalSeconds = EndpointRefreshPolicy.sanitizeInterval(seconds)) }
    }

    fun setCustomInterval(input: String) {
        val seconds = input.trim().toLongOrNull()
        _state.update {
            it.copy(
                customIntervalInput = input,
                intervalError = seconds == null ||
                    seconds < EndpointRefreshPolicy.MIN_INTERVAL_SECONDS ||
                    seconds > EndpointRefreshPolicy.MAX_INTERVAL_SECONDS,
            )
        }
        if (seconds == null) return
        updateSettings { it.copy(refreshIntervalSeconds = EndpointRefreshPolicy.sanitizeInterval(seconds)) }
    }

    fun setPeriodicRefreshEnabled(enabled: Boolean) = updateSettings { it.copy(periodicRefreshEnabled = enabled) }

    fun setPreferHttps(enabled: Boolean) = updateSettings { it.copy(preferHttps = enabled) }

    fun setAllowHttpFallback(enabled: Boolean) = updateSettings { it.copy(allowHttpFallback = enabled) }

    fun setStaleAddressPolicy(policy: EndpointStalePolicy) = updateSettings { it.copy(staleAddressPolicy = policy) }

    fun setDefaultPort(input: String) {
        val port = input.trim().toIntOrNull()
        _state.update { it.copy(defaultPortInput = input, portError = port == null || !EndpointUrlFactory.isValidPort(port)) }
        if (port == null || !EndpointUrlFactory.isValidPort(port)) return
        updateSettings { it.copy(defaultPort = port) }
    }

    /** Records that the user accepts an unencrypted connection, then retries the lookup. */
    fun grantPlaintextConsent() {
        viewModelScope.launch {
            repository?.setPlaintextConsent(true)
            refreshNow()
        }
    }

    /**
     * Runs an immediate lookup.
     *
     * The outcome is reported verbatim: a failure keeps the previous address and shows the error,
     * it never clears the address.
     */
    fun refreshNow() {
        val repo = repository ?: return
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, feedback = null) }
        viewModelScope.launch {
            val outcome = runCatching { repo.refreshNow() }
                .getOrElse { error ->
                    Timber.e(error, "Manual endpoint refresh failed")
                    _state.update { it.copy(refreshing = false, feedback = EndpointFeedback.Failed(error.message ?: "")) }
                    return@launch
                }

            val feedback = outcome.toFeedback()
            _state.update { it.copy(refreshing = false, feedback = feedback) }
        }
    }

    private fun EndpointRefreshOutcome.toFeedback(): EndpointFeedback = when (this) {
        is EndpointRefreshOutcome.Applied -> when (val result = update) {
            is EndpointUpdate.Resolved -> EndpointFeedback.Updated(result.endpoint.url)
            is EndpointUpdate.Changed -> EndpointFeedback.Updated(result.endpoint.url)
            is EndpointUpdate.Unchanged -> EndpointFeedback.Unchanged
            is EndpointUpdate.IgnoredStale -> EndpointFeedback.Unchanged
            is EndpointUpdate.Failed ->
                if (result.error.reason == EndpointRejectionReason.PLAINTEXT_CONFIRMATION_REQUIRED) {
                    EndpointFeedback.PlaintextConsentRequired(result.error.message)
                } else {
                    EndpointFeedback.Failed(result.error.message)
                }
        }

        EndpointRefreshOutcome.SkippedConcurrent -> EndpointFeedback.Concurrent
        EndpointRefreshOutcome.SkippedOffline -> EndpointFeedback.Offline
        EndpointRefreshOutcome.SkippedNotConfigured -> EndpointFeedback.NotConfigured
        EndpointRefreshOutcome.NotDue -> EndpointFeedback.NotDue
    }

    private fun updateSettings(transform: (EndpointSettings) -> EndpointSettings) {
        val repo = repository ?: return
        val updated = transform(_state.value.settings)
        _state.update { it.copy(settings = updated, recordNameError = !isRecordNameValid(updated)) }
        viewModelScope.launch {
            runCatching { repo.updateSettings(updated) }
                .onFailure { Timber.e(it, "Could not persist the dynamic address settings") }
        }
    }

    private fun validateRecordName(settings: EndpointSettings) {
        _state.update { it.copy(recordNameError = !isRecordNameValid(settings)) }
    }

    /**
     * A blank record name is accepted while the user is still typing; what is rejected is a name
     * that is clearly not a DNS name, so a typo is visible before the first lookup fails.
     */
    private fun isRecordNameValid(settings: EndpointSettings): Boolean {
        if (!settings.mode.usesDnsRecord) return false
        val name = settings.activeRecordName.ifEmpty { return false }
        return EndpointUrlFactory.isValidHostname(name)
    }
}
