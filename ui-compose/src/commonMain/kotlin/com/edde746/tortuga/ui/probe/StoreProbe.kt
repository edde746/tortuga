package com.edde746.tortuga.ui.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Test builds' view of the stores of the account screens on screen (sign-in, identity link, profiles, profile editor),
 * for the test report: what [TortugaNavigator.retained][com.edde746.tortuga.presentation.navigation.TortugaNavigator.retained]
 * is for the back stack, for the screens outside it. Provided through [LocalStoreProbe] only in those builds (Android's
 * test launch, the desktop harness launcher); called on the main thread.
 */
fun interface StoreProbe {
    /** The screen [name] shows [store], or has left (null). */
    fun shown(name: String, store: Any?)
}

val LocalStoreProbe = staticCompositionLocalOf<StoreProbe?> { null }

/**
 * A screen's store for as long as the screen is composed: made by [create] on first composition, shown to the
 * [LocalStoreProbe] under [name] meanwhile, and released by [release] when the screen leaves.
 */
@Composable
fun <S : Any> rememberLiveStore(name: String, create: () -> S, release: (S) -> Unit): S {
    val probe = LocalStoreProbe.current
    val store = remember { create() }
    DisposableEffect(store) {
        probe?.shown(name, store)
        onDispose {
            probe?.shown(name, null)
            release(store)
        }
    }
    return store
}
