package com.edde746.tortuga.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.RootScreen
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.ui.account.SignInRoute
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.profiles.ProfilesRoute

/**
 * The product app: the account shell of [root] on [platform], its screen chosen by the account gate.
 * Screens switch without a cross-fade: an app closed by a sign-out or a profile switch must not stay composed over
 * released stores.
 */
@Composable
fun TortugaRoot(root: RootSession, platform: TortugaPlatform, modifier: Modifier = Modifier) {
    TortugaFrame(root.graph, platform, modifier) {
        val screen by root.screen.collectAsState()
        when (val shown = screen) {
            RootScreen.Starting -> {
                val startup by root.startup.state.collectAsState()
                StartingScreen(startup.status)
            }
            RootScreen.SignIn -> SignInRoute(root)
            RootScreen.Profiles -> ProfilesRoute(root)
            is RootScreen.Main -> key(shown.app) { AppContent(shown.app) }
        }
    }
}

private val LoaderSize = 56.dp

/**
 * The splash, while the app starts: the loader in the middle of the screen and, under it, [status], the step the
 * start is on (`StartupUiState.status`: null until the start has taken half a second). The loader stays where it is
 * when the line comes. In the theme of the caller: the account shell's, or the desktop window's before the shell.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StartingScreen(status: String?) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(LoaderSize))
        if (status != null) {
            // The lower half of the screen, the line at its top just under the loader.
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(start = 32.dp, top = LoaderSize / 2 + 20.dp, end = 32.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        status,
                        Modifier.layoutProbe("startup.status"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
