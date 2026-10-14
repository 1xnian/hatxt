package io.homeassistant.companion.android.onboarding.manualserver

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URL
import javax.inject.Inject
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
internal class ManualServerViewModel @Inject constructor() : ViewModel() {
    private val serverUrlMutableFlow = MutableStateFlow("")
    val serverUrlFlow = serverUrlMutableFlow.asStateFlow()

    private val isServerUrlValidMutableFlow = MutableStateFlow(false)
    val isServerUrlValidFlow = isServerUrlValidMutableFlow.asStateFlow()

    fun onServerUrlChange(url: String) {
        serverUrlMutableFlow.update { url }
        validateServerUrl(url)
    }

    /** Resolves a TXT-based server address and returns the resulting URL to the onboarding flow. */
    fun connectToUrl(input: String, onResolved: (URL) -> Unit) {
        viewModelScope.launch {
            val resolved = runCatching {
                withContext(Dispatchers.IO) { TxtRecordUrlResolver.resolveIfTxt(input.trim()) }
            }.onFailure { Timber.e(it, "Unable to resolve Home Assistant TXT URL") }
                .getOrNull() ?: return@launch
            runCatching { URL(resolved) }
                .onSuccess(onResolved)
                .onFailure { Timber.e(it, "Resolved Home Assistant URL is invalid") }
        }
    }

    private fun validateServerUrl(url: String) {
        val trimmed = url.trim()
        isServerUrlValidMutableFlow.update {
            if (trimmed.startsWith("txt://", ignoreCase = true)) {
                trimmed.removePrefix("txt://").isNotBlank() && !trimmed.removePrefix("txt://").contains('/')
            } else {
                URLUtil.isValidUrl(trimmed) && runCatching { URL(trimmed) }.isSuccess
            }
        }
    }
}
