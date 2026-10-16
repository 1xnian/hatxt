package io.homeassistant.companion.android.onboarding.manualserver

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.generated.Close
import io.github.timoptr.mdiicons.rememberImageVector
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.compose.composable.HAAccentButton
import io.homeassistant.companion.android.common.compose.composable.HAPlainButton
import io.homeassistant.companion.android.common.compose.composable.HATextField
import io.homeassistant.companion.android.common.compose.composable.HATopBar
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATextStyle
import io.homeassistant.companion.android.common.compose.theme.HAThemeForPreview
import io.homeassistant.companion.android.common.compose.theme.LocalHAColorScheme
import io.homeassistant.companion.android.util.compose.HAPreviews
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private val delayBeforeError = 500.milliseconds

@Composable
internal fun ManualServerScreen(
    onBackClick: () -> Unit,
    onConnectTo: (URL) -> Unit,
    onHelpClick: suspend () -> Unit,
    viewModel: ManualServerViewModel,
    modifier: Modifier = Modifier,
) {
    val serverUrl by viewModel.serverUrlFlow.collectAsStateWithLifecycle()
    val isServerUrlValid by viewModel.isServerUrlValidFlow.collectAsStateWithLifecycle()
    val lookupFailure by viewModel.lookupFailureFlow.collectAsStateWithLifecycle()
    val isResolving by viewModel.isResolvingFlow.collectAsStateWithLifecycle()

    ManualServerScreen(
        onConnectClick = {
            viewModel.connectToUrl(serverUrl, onConnectTo)
        },
        onPlaintextConnectClick = {
            // Only reachable from the action rendered for PlaintextConfirmationRequired, i.e. after
            // the user was told the address does not serve HTTPS.
            viewModel.connectToUrlWithPlaintextConsent(serverUrl, onConnectTo)
        },
        onHelpClick = onHelpClick,
        onBackClick = onBackClick,
        serverUrl = serverUrl,
        onServerUrlChange = viewModel::onServerUrlChange,
        isServerUrlValid = isServerUrlValid,
        isResolving = isResolving,
        lookupFailure = lookupFailure,
        modifier = modifier,
    )
}

@Composable
internal fun ManualServerScreen(
    isServerUrlValid: Boolean,
    onBackClick: () -> Unit,
    onConnectClick: () -> Unit,
    onHelpClick: suspend () -> Unit,
    onServerUrlChange: (String) -> Unit,
    serverUrl: String,
    lookupFailure: ManualServerViewModel.LookupFailure? = null,
    isResolving: Boolean = false,
    onPlaintextConnectClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { HATopBar(onHelpClick = onHelpClick, onBackClick = onBackClick) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { contentPadding ->
        ManualServerContent(
            onConnectClick = onConnectClick,
            onPlaintextConnectClick = onPlaintextConnectClick,
            serverUrl = serverUrl,
            onServerUrlChange = onServerUrlChange,
            isServerUrlValid = isServerUrlValid,
            isResolving = isResolving,
            lookupFailure = lookupFailure,
            modifier = Modifier
                .padding(contentPadding),
        )
    }
}

@Composable
private fun ManualServerContent(
    isServerUrlValid: Boolean,
    onConnectClick: () -> Unit,
    onPlaintextConnectClick: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    serverUrl: String,
    isResolving: Boolean,
    lookupFailure: ManualServerViewModel.LookupFailure?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HADimens.SPACE4)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HADimens.SPACE6),
    ) {
        Image(
            modifier = Modifier
                .padding(top = HADimens.SPACE6),
            // Use painterResource instead of vector resource for API < 24 since it has gradients
            painter = painterResource(R.drawable.ic_manual_server),
            contentDescription = null,
        )
        Text(
            text = stringResource(commonR.string.manual_server_title),
            style = HATextStyle.Headline,
        )

        ServerUrlTextField(
            serverUrl = serverUrl,
            onServerUrlChange = onServerUrlChange,
            isServerUrlValid = isServerUrlValid,
            lookupFailure = lookupFailure,
            onConnectClick = onConnectClick,
        )

        if (isResolving) {
            Text(
                text = stringResource(commonR.string.manual_server_dynamic_lookup_resolving),
                style = HATextStyle.BodyMedium,
                color = LocalHAColorScheme.current.colorOnNeutralNormal,
            )
        }

        // A record that answers only over plaintext is never used silently. The address that was
        // confirmed to answer is shown by the field's supporting text, and this action is the
        // consent that lets it be used.
        if (lookupFailure is ManualServerViewModel.LookupFailure.PlaintextConfirmationRequired) {
            HAPlainButton(
                text = stringResource(commonR.string.manual_server_dynamic_lookup_plaintext_accept),
                onClick = onPlaintextConnectClick,
                enabled = !isResolving,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        HAAccentButton(
            text = stringResource(commonR.string.manual_server_connect),
            onClick = onConnectClick,
            enabled = isServerUrlValid && !isResolving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = HADimens.SPACE6),
        )
    }
}

@Composable
private fun ServerUrlTextField(
    isServerUrlValid: Boolean,
    onConnectClick: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    serverUrl: String,
    lookupFailure: ManualServerViewModel.LookupFailure?,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var isError by remember { mutableStateOf(serverUrl.isNotEmpty() && !isServerUrlValid) }

    val lookupFailureMessage = when (lookupFailure) {
        is ManualServerViewModel.LookupFailure.Message ->
            stringResource(commonR.string.manual_server_dynamic_lookup_failed, lookupFailure.detail)

        is ManualServerViewModel.LookupFailure.PlaintextConfirmationRequired ->
            stringResource(commonR.string.manual_server_dynamic_lookup_plaintext, lookupFailure.url)

        ManualServerViewModel.LookupFailure.InvalidResult ->
            stringResource(commonR.string.manual_server_wrong_url)

        null -> null
    }

    // The field accepts a DNS record reference, which is not a URL users would guess on their own.
    // The hint is shown while the field is empty and while a reference is being typed.
    val isRecordReference = serverUrl.startsWith("txt://", ignoreCase = true) ||
        serverUrl.startsWith("srv://", ignoreCase = true)

    HATextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        trailingIcon = {
            if (serverUrl.isNotEmpty()) {
                IconButton(onClick = { onServerUrlChange("") }) {
                    Icon(
                        imageVector = Mdi.Close.rememberImageVector(),
                        contentDescription = stringResource(commonR.string.clear_text),
                    )
                }
            }
        },
        placeholder = {
            Text(
                text = "http://homeassistant.local",
                style = HATextStyle.UserInput,
                color = LocalHAColorScheme.current.colorOnNeutralNormal,
            )
        },
        // Uri keyboard without autocorrection: keyboards with aggressive autocorrect rewrite
        // hostnames while they are typed (e.g. homeassistant.internal to home assistant.internal)
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusRequester.freeFocus()
                if (isServerUrlValid) {
                    onConnectClick()
                }
                // This is going to hide the keyboard and clear focus on the text field
                defaultKeyboardAction(ImeAction.Done)
            },
        ),
        supportingText = {
            val message = lookupFailureMessage
                ?: stringResource(commonR.string.manual_server_dynamic_lookup_hint)
                    .takeIf { serverUrl.isEmpty() || isRecordReference }
                ?: stringResource(commonR.string.manual_server_wrong_url).takeIf { isError }
            if (message != null) {
                Text(
                    text = message,
                    style = HATextStyle.BodyMedium.copy(
                        color = if (lookupFailureMessage != null || isError) {
                            LocalHAColorScheme.current.colorBorderDangerNormal
                        } else {
                            LocalHAColorScheme.current.colorOnNeutralNormal
                        },
                    ),
                )
            }
        },
        isError = isError || lookupFailureMessage != null,
        maxLines = 1,
        modifier = modifier.focusRequester(focusRequester),
    )
    // Request focus on the text field when the screen is shown
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // delay the display of the error if the user is typing
    LaunchedEffect(serverUrl) {
        if (serverUrl.isEmpty() || isServerUrlValid) {
            isError = false
            return@LaunchedEffect
        }
        delay(delayBeforeError)
        isError = true
    }
}

@HAPreviews
@Composable
private fun ManualServerScreenPreview() {
    HAThemeForPreview {
        ManualServerScreen(
            onConnectClick = {},
            onHelpClick = {},
            onBackClick = {},
            serverUrl = "hello",
            onServerUrlChange = {},
            isServerUrlValid = true,
        )
    }
}
