package com.edde746.tortuga.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.presentation.account.IdentityLinkOptions
import com.edde746.tortuga.presentation.account.IdentityLinkStep
import com.edde746.tortuga.presentation.account.IdentityLinkStore
import com.edde746.tortuga.presentation.account.IdentityLinkUiState
import com.edde746.tortuga.presentation.account.SignInOptionUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.rememberLiveStore
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * "Add a sign-in method" over [app] ([AppSession.linkIdentity], from the account settings): its [IdentityLinkStore]
 * lives while the overlay shows; linking or leaving closes it.
 */
@Composable
fun IdentityLinkRoute(app: AppSession) {
    val store = rememberLiveStore(IdentityLinkStore.KEY, app::identityLinkStore, IdentityLinkStore::release)
    val state by store.state.collectAsState()
    IdentityLinkScreen(state, onLink = store::link, onCancel = store::cancel, onRetry = store::retry, onClose = store::close)
}

/**
 * Linking another provider to the account: the providers not linked yet (or why there are none), a running flow's
 * progress or device code (the header's back button cancels it), and the last attempt's error. Every text comes from [state] or `T`.
 */
@Composable
fun IdentityLinkScreen(
    state: IdentityLinkUiState,
    onLink: (provider: String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = T.account.identityLink.title
    val intro = T.account.identityLink.intro
    when (val step = state.step) {
        is IdentityLinkStep.DeviceCode -> DeviceCodePage(title, intro, step.code, step.progress, onCancel, backProbe = "identityLink.back", modifier = modifier)
        is IdentityLinkStep.Working -> AccountPage(title, intro, modifier, onBack = onCancel, backProbe = "identityLink.back", focusBack = true) {
            ProgressLine(step.progress)
        }
        is IdentityLinkStep.Choose -> AccountPage(title, intro, modifier, onBack = onClose, backProbe = "identityLink.back", focusBack = false) {
            when (val options = step.options) {
                IdentityLinkOptions.Loading -> ProgressLine(null)
                is IdentityLinkOptions.Failed -> {
                    Text(options.text, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
                    TortugaButton(T.nav.retry, onRetry, Modifier.layoutProbe("identityLink.retry"), style = ButtonStyle.SECONDARY, icon = TortugaIcons.Refresh)
                }
                is IdentityLinkOptions.Empty -> Text(options.text, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
                is IdentityLinkOptions.Buttons -> Buttons(options.options, onLink)
            }
            step.error?.let { ErrorLine(it, Modifier.layoutProbe("identityLink.error")) }
        }
    }
}

/** The providers not linked yet; the first takes focus as they show. */
@Composable
private fun Buttons(options: List<SignInOptionUi>, onLink: (provider: String) -> Unit) {
    val first = remember { FocusRequester() }
    options.forEachIndexed { index, option ->
        TortugaButton(
            option.label,
            { onLink(option.provider) },
            Modifier.layoutProbe("identityLink.option.${option.provider}").then(if (index == 0) Modifier.focusRequester(first) else Modifier),
            style = if (index == 0) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
            icon = providerIcon(option.provider),
            logo = providerLogo(option.provider),
            fill = true,
        )
    }
    LaunchedEffect(options.isNotEmpty()) { if (options.isNotEmpty()) first.requestFocus() }
}
