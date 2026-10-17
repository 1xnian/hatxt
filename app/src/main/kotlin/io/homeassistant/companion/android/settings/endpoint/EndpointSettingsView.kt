package io.homeassistant.companion.android.settings.endpoint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.endpoint.EndpointRefreshPolicy
import io.homeassistant.companion.android.common.data.endpoint.EndpointResolutionMode
import io.homeassistant.companion.android.common.data.endpoint.EndpointResolutionStatus
import io.homeassistant.companion.android.common.data.endpoint.EndpointSnapshot
import io.homeassistant.companion.android.common.data.endpoint.EndpointStalePolicy
import io.homeassistant.companion.android.util.compose.HaAlertWarning
import io.homeassistant.companion.android.util.compose.RadioButtonRow
import io.homeassistant.companion.android.util.safeBottomPaddingValues
import java.text.DateFormat
import java.util.Date

/**
 * The dynamic address section of the settings.
 *
 * The screen never shows credentials, tokens or query strings: [EndpointSnapshot] only carries the
 * address, the record name and a coarse error classification.
 */
@Composable
fun EndpointSettingsView(
    state: EndpointSettingsUiState,
    nowMillis: Long,
    onModeChange: (EndpointResolutionMode) -> Unit,
    onTxtRecordNameChange: (String) -> Unit,
    onSrvRecordNameChange: (String) -> Unit,
    onUseFixedHostChange: (Boolean) -> Unit,
    onFixedHostChange: (String) -> Unit,
    onPresetIntervalChange: (Long) -> Unit,
    onCustomIntervalChange: (String) -> Unit,
    onPeriodicRefreshChange: (Boolean) -> Unit,
    onPreferHttpsChange: (Boolean) -> Unit,
    onAllowHttpFallbackChange: (Boolean) -> Unit,
    onStalePolicyChange: (EndpointStalePolicy) -> Unit,
    onDefaultPortChange: (String) -> Unit,
    onRefreshNow: () -> Unit,
    onGrantPlaintextConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Box(modifier = modifier.verticalScroll(scrollState)) {
        Column(
            modifier = Modifier
                .padding(safeBottomPaddingValues(applyHorizontal = false))
                .padding(16.dp),
        ) {
            if (state.isUnavailable) {
                Text(stringResource(commonR.string.endpoint_setting_summary))
                return@Column
            }
            if (!state.loaded) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                return@Column
            }

            val settings = state.settings
            Text(
                text = stringResource(commonR.string.endpoint_setting_description),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SectionTitle(commonR.string.endpoint_mode_title)
            RadioButtonRow(
                text = "${stringResource(commonR.string.endpoint_mode_fixed)}\n" +
                    stringResource(commonR.string.endpoint_mode_fixed_summary),
                selected = settings.mode == EndpointResolutionMode.FIXED,
                onClick = { onModeChange(EndpointResolutionMode.FIXED) },
            )
            RadioButtonRow(
                text = "${stringResource(commonR.string.endpoint_mode_txt)}\n" +
                    stringResource(commonR.string.endpoint_mode_txt_summary),
                selected = settings.mode == EndpointResolutionMode.TXT,
                onClick = { onModeChange(EndpointResolutionMode.TXT) },
            )
            RadioButtonRow(
                text = "${stringResource(commonR.string.endpoint_mode_srv)}\n" +
                    stringResource(commonR.string.endpoint_mode_srv_summary),
                selected = settings.mode == EndpointResolutionMode.SRV,
                onClick = { onModeChange(EndpointResolutionMode.SRV) },
            )

            when (settings.mode) {
                EndpointResolutionMode.TXT -> OutlinedTextField(
                    value = settings.txtRecordName,
                    onValueChange = onTxtRecordNameChange,
                    label = { Text(stringResource(commonR.string.endpoint_txt_name_title)) },
                    isError = state.recordNameError,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                EndpointResolutionMode.SRV -> OutlinedTextField(
                    value = settings.srvRecordName,
                    onValueChange = onSrvRecordNameChange,
                    label = { Text(stringResource(commonR.string.endpoint_srv_name_title)) },
                    placeholder = { Text(stringResource(commonR.string.endpoint_srv_name_hint)) },
                    isError = state.recordNameError,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

                EndpointResolutionMode.FIXED -> Unit
            }
            if (state.recordNameError && settings.mode.usesDnsRecord) {
                Text(
                    text = stringResource(commonR.string.endpoint_record_name_invalid),
                    color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Only offered for the record based modes: it changes what the record is asked for, from
            // the whole address down to just the port.
            if (settings.mode.usesDnsRecord) {
                SwitchRow(
                    text = stringResource(commonR.string.endpoint_use_fixed_host),
                    checked = settings.useFixedHost,
                    onCheckedChange = onUseFixedHostChange,
                )
                Text(
                    text = stringResource(commonR.string.endpoint_use_fixed_host_summary),
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (settings.useFixedHost) {
                    OutlinedTextField(
                        value = settings.fixedHost,
                        onValueChange = onFixedHostChange,
                        label = { Text(stringResource(commonR.string.endpoint_fixed_host_title)) },
                        placeholder = { Text(stringResource(commonR.string.endpoint_fixed_host_hint)) },
                        isError = state.fixedHostError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.fixedHostError) {
                        Text(
                            text = stringResource(commonR.string.endpoint_fixed_host_invalid),
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        text = stringResource(commonR.string.endpoint_fixed_host_summary),
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (settings.mode.usesDnsRecord) {
                OutlinedTextField(
                    value = state.defaultPortInput,
                    onValueChange = onDefaultPortChange,
                    label = { Text(stringResource(commonR.string.endpoint_default_port_title)) },
                    isError = state.portError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = stringResource(commonR.string.endpoint_default_port_summary),
                    style = MaterialTheme.typography.caption,
                )

                SectionTitle(commonR.string.endpoint_refresh_title)
                Text(
                    text = stringResource(commonR.string.endpoint_refresh_description),
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SwitchRow(
                    text = stringResource(commonR.string.endpoint_enable_periodic_refresh),
                    checked = settings.periodicRefreshEnabled,
                    onCheckedChange = onPeriodicRefreshChange,
                )
                if (settings.periodicRefreshEnabled) {
                    EndpointRefreshPolicy.PRESET_INTERVALS_SECONDS.forEach { seconds ->
                        RadioButtonRow(
                            text = intervalLabel(seconds),
                            selected = settings.refreshIntervalSeconds == seconds &&
                                state.customIntervalInput.isEmpty(),
                            onClick = { onPresetIntervalChange(seconds) },
                        )
                    }
                    RadioButtonRow(
                        text = stringResource(commonR.string.endpoint_interval_custom),
                        selected = state.customIntervalInput.isNotEmpty(),
                        // Selecting "Custom…" seeds the text field with the interval that is active
                        // right now, so the user edits a real value instead of an empty box. A
                        // non-empty customIntervalInput is also what marks the custom row as
                        // selected, which is why the callback takes the seeded value.
                        onClick = { onCustomIntervalChange(settings.refreshIntervalSeconds.toString()) },
                    )
                    if (state.customIntervalInput.isNotEmpty()) {
                        OutlinedTextField(
                            value = state.customIntervalInput,
                            onValueChange = onCustomIntervalChange,
                            label = { Text(stringResource(commonR.string.endpoint_interval_custom_hint)) },
                            isError = state.intervalError,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                commonR.string.endpoint_interval_too_short,
                                EndpointRefreshPolicy.MIN_INTERVAL_SECONDS,
                            ),
                            style = MaterialTheme.typography.caption,
                        )
                    }
                    if (EndpointRefreshPolicy.isBackgroundIntervalLimited(settings.refreshIntervalSeconds)) {
                        HaAlertWarning(
                            message = stringResource(commonR.string.endpoint_refresh_description),
                            action = null,
                            onActionClicked = null,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                } else {
                    Text(
                        text = stringResource(commonR.string.endpoint_refresh_periodic_disabled),
                        style = MaterialTheme.typography.caption,
                    )
                }
            }

            SectionTitle(commonR.string.endpoint_security_title)
            SwitchRow(
                text = stringResource(commonR.string.endpoint_prefer_https),
                checked = settings.preferHttps,
                onCheckedChange = onPreferHttpsChange,
            )
            Text(
                text = stringResource(commonR.string.endpoint_prefer_https_summary),
                style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SwitchRow(
                text = stringResource(commonR.string.endpoint_allow_http_fallback),
                checked = settings.allowHttpFallback,
                onCheckedChange = onAllowHttpFallbackChange,
                enabled = settings.preferHttps,
            )
            Text(
                text = stringResource(commonR.string.endpoint_allow_http_fallback_summary),
                style = MaterialTheme.typography.caption,
            )
            if (state.snapshot.downgradedToHttp) {
                Spacer(modifier = Modifier.height(8.dp))
                HaAlertWarning(
                    message = stringResource(commonR.string.endpoint_plaintext_warning),
                    action = null,
                    onActionClicked = null,
                )
            }

            SectionTitle(commonR.string.endpoint_stale_policy_title)
            RadioButtonRow(
                text = stringResource(commonR.string.endpoint_stale_policy_use_last),
                selected = settings.staleAddressPolicy == EndpointStalePolicy.USE_LAST_VALIDATED,
                onClick = { onStalePolicyChange(EndpointStalePolicy.USE_LAST_VALIDATED) },
            )
            RadioButtonRow(
                text = stringResource(commonR.string.endpoint_stale_policy_strict),
                selected = settings.staleAddressPolicy == EndpointStalePolicy.STRICT,
                onClick = { onStalePolicyChange(EndpointStalePolicy.STRICT) },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onRefreshNow,
                    enabled = !state.refreshing && settings.mode.usesDnsRecord,
                ) {
                    Text(
                        stringResource(
                            if (state.refreshing) {
                                commonR.string.endpoint_refresh_running
                            } else {
                                commonR.string.endpoint_refresh_now
                            },
                        ),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                state.feedback?.let { FeedbackText(it) }
            }
            val consentRequest = state.feedback
            if (consentRequest is EndpointFeedback.PlaintextConsentRequired) {
                Spacer(modifier = Modifier.height(8.dp))
                HaAlertWarning(
                    message = consentRequest.message,
                    action = stringResource(commonR.string.endpoint_allow_plaintext),
                    onActionClicked = onGrantPlaintextConsent,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            StatusSection(state = state.snapshot, nowMillis = nowMillis)
        }
    }
}

@Composable
private fun FeedbackText(feedback: EndpointFeedback) {
    val text = when (feedback) {
        EndpointFeedback.Succeeded -> stringResource(commonR.string.endpoint_refresh_succeeded)
        EndpointFeedback.Unchanged -> stringResource(commonR.string.endpoint_refresh_unchanged)
        is EndpointFeedback.Updated -> stringResource(commonR.string.endpoint_refresh_updated, feedback.url)
        is EndpointFeedback.Failed -> stringResource(commonR.string.endpoint_refresh_failed, feedback.message)
        EndpointFeedback.Concurrent -> stringResource(commonR.string.endpoint_refresh_concurrent)
        EndpointFeedback.Offline -> stringResource(commonR.string.endpoint_refresh_offline)
        EndpointFeedback.NotConfigured -> stringResource(commonR.string.endpoint_refresh_not_configured)
        EndpointFeedback.NotDue -> stringResource(commonR.string.endpoint_refresh_not_due)
        is EndpointFeedback.PlaintextConsentRequired -> feedback.message
    }
    val isError = feedback is EndpointFeedback.Failed
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
    )
}

@Composable
private fun StatusSection(state: EndpointSnapshot, nowMillis: Long) {
    SectionTitle(commonR.string.endpoint_status_title)
    val none = stringResource(commonR.string.endpoint_none)
    val unknown = stringResource(commonR.string.endpoint_unknown)

    StatusRow(
        label = stringResource(commonR.string.endpoint_status_mode),
        value = state.mode.name,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_record),
        value = state.recordName ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_ip),
        value = state.current?.ip ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_port),
        value = state.current?.port?.toString() ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_url),
        value = state.current?.url ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_scheme),
        value = state.current?.scheme?.urlScheme ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_last_success),
        value = state.lastResolvedAtMillis?.let { formatTime(it) } ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_next_refresh),
        value = state.nextRefreshAtMillis?.let { formatTime(it) } ?: none,
    )
    StatusRow(
        label = stringResource(commonR.string.endpoint_status_cache_state),
        value = when {
            state.status == EndpointResolutionStatus.RESOLVING -> null
            state.lastError == null -> stringResource(commonR.string.endpoint_cache_fresh)
            else -> stringResource(
                commonR.string.endpoint_cache_stale,
                state.lastResolvedAtMillis?.let { "${formatTime(it)} (${relativeAge(it, nowMillis)})" } ?: unknown,
            )
        },
    )
    if (state.lastError != null) {
        StatusRow(
            label = stringResource(commonR.string.endpoint_status_last_error),
            value = state.lastError,
        )
    }
}

@Composable
private fun SectionTitle(stringRes: Int) {
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(stringRes),
        style = MaterialTheme.typography.subtitle1,
        fontWeight = FontWeight.Bold,
    )
    Divider(modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun StatusRow(label: String, value: String?) {
    // A null value means "still loading", the row is simply left out so a stale value is never
    // rendered as if it were current.
    if (value == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun intervalLabel(seconds: Long): String = when (seconds) {
    in 0..59 -> stringResource(commonR.string.endpoint_interval_30s)
    60L -> stringResource(commonR.string.endpoint_interval_1m)
    5L * 60L -> stringResource(commonR.string.endpoint_interval_5m)
    10L * 60L -> stringResource(commonR.string.endpoint_interval_10m)
    30L * 60L -> stringResource(commonR.string.endpoint_interval_30m)
    60L * 60L -> stringResource(commonR.string.endpoint_interval_1h)
    else -> stringResource(commonR.string.endpoint_interval_custom_hint) + " $seconds"
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))

@Composable
private fun relativeAge(thenMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 90L -> stringResource(commonR.string.endpoint_ago_seconds, seconds)
        seconds < 90L * 60L -> stringResource(commonR.string.endpoint_ago_minutes, seconds / 60L)
        seconds < 36L * 60L * 60L -> stringResource(commonR.string.endpoint_ago_hours, seconds / 3600L)
        else -> stringResource(commonR.string.endpoint_ago_days, seconds / (24L * 3600L))
    }
}
