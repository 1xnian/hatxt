package io.homeassistant.companion.android.settings.endpoint

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.util.compose.HomeAssistantAppTheme

/**
 * Settings screen for the dynamic (TXT / SRV) server address.
 *
 * The fragment only wires the view to [EndpointSettingsViewModel]; the actual DNS work happens in
 * the common module so it can be shared with the background worker and the pre-connect gate.
 */
@AndroidEntryPoint
class EndpointSettingsFragment : Fragment() {

    companion object {
        const val EXTRA_SERVER = "server"
        const val TAG = "endpoint_settings_fragment"
    }

    private val viewModel: EndpointSettingsViewModel by viewModels()

    private var serverId = ServerManager.SERVER_ID_ACTIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { serverId = it.getInt(EXTRA_SERVER, serverId) }
        viewModel.observe(serverId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HomeAssistantAppTheme {
                    val state by viewModel.state.collectAsState()
                    // Re-read the wall clock whenever the observable state changes so the relative
                    // age of the cached address stays honest without a ticking timer.
                    val nowMillis = remember(state.snapshot) { System.currentTimeMillis() }

                    EndpointSettingsView(
                        state = state,
                        nowMillis = nowMillis,
                        onModeChange = viewModel::setMode,
                        onTxtRecordNameChange = viewModel::setTxtRecordName,
                        onSrvRecordNameChange = viewModel::setSrvRecordName,
                        onUseFixedHostChange = viewModel::setUseFixedHost,
                        onFixedHostChange = viewModel::setFixedHost,
                        onBypassSystemDnsChange = viewModel::setBypassSystemDnsCache,
                        onPresetIntervalChange = viewModel::setPresetInterval,
                        onCustomIntervalChange = viewModel::setCustomInterval,
                        onPeriodicRefreshChange = viewModel::setPeriodicRefreshEnabled,
                        onPreferHttpsChange = viewModel::setPreferHttps,
                        onAllowHttpFallbackChange = viewModel::setAllowHttpFallback,
                        onStalePolicyChange = viewModel::setStaleAddressPolicy,
                        onDefaultPortChange = viewModel::setDefaultPort,
                        onRefreshNow = viewModel::refreshNow,
                        onGrantPlaintextConsent = viewModel::grantPlaintextConsent,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(commonR.string.endpoint_setting_name)
    }
}
