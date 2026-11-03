package com.edde746.tortuga.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.engine.SyncStatus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SyncLineTest {
    /** A phone's sync trouble, the line across the top of the screen, is spoken as it shows and as it changes. */
    @Test
    fun syncTroubleIsSpokenAsItShowsAndChanges() = runComposeUiTest {
        var kind by mutableStateOf<SyncStatus.Kind>(SyncStatus.Kind.Offline)
        setContent {
            TortugaTheme { SyncLine(if (kind == SyncStatus.Kind.Offline) T.account.status.offline else T.account.status.failed.server, kind) }
        }
        onNodeWithText(T.account.status.offline).assert(spoken)
        kind = SyncStatus.Kind.Error(SyncStatus.Failure.SERVER)
        onNodeWithText(T.account.status.failed.server).assert(spoken)
    }

    private val spoken = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
}
