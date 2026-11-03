package com.edde746.tortuga.watchnext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import com.edde746.tortuga.TortugaApplication

/**
 * The home screen's word that the user removed one of this app's Watch Next rows
 * ([TvContract.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED]): the row goes and its item stays off the home screen
 * until it is watched again ([WatchNext.removedByUser]).
 */
class WatchNextRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContract.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED) return
        val programId = intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1)
        val watchNext = (context.applicationContext as TortugaApplication).platform.homeScreen
        if (programId < 0 || watchNext == null) return
        val pending = goAsync()
        watchNext.removedByUser(programId, pending::finish)
    }
}
