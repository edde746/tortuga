package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.presentation.profiles.ParentPinStore
import com.edde746.tortuga.presentation.profiles.ParentPinUiState
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.rememberLiveStore
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * A parent's PIN asked over the app ([com.edde746.tortuga.app.AppOverlay.ParentPin]): its [ParentPinStore] lives while
 * the overlay shows; the store closes the overlay once the PIN unlocks or the check is cancelled.
 */
@Composable
fun ParentPinRoute(app: AppSession) {
    val store = rememberLiveStore("parentPin", app::parentPinStore, ParentPinStore::release)
    val state by store.state.collectAsState()
    ParentPinScreen(state, onDigit = store::digit, onBackspace = store::backspace, onCancel = store::cancel)
}

/**
 * The parent's PIN pad ([PinPad]) on every form factor: a lock, "Enter a parent's PIN", what it unlocks (allowing or
 * blocking a title, leaving the profile, opening settings), the dots, the wrong PIN or lockout line; the digit keys of
 * a keyboard or a remote type while it has focus, Back cancels. Every text comes from [state].
 */
@Composable
fun ParentPinScreen(
    state: ParentPinUiState,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PinPad(
        title = state.title,
        entered = state.entered,
        length = state.length,
        enteredText = state.enteredText,
        message = state.error,
        acceptsInput = state.acceptsInput,
        onDigit = onDigit,
        onBackspace = onBackspace,
        onCancel = onCancel,
        modifier = modifier,
        subtitle = state.subtitle,
        header = { Icon(TortugaIcons.Lock, contentDescription = null, Modifier.size(48.dp), tint = TortugaColors.TextMuted) },
    )
}
