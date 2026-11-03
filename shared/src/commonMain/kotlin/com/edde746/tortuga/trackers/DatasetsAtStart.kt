package com.edde746.tortuga.trackers

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A downloadable dataset as the app's start handles it: [updateDiskCopy] brings its disk copy up to date without
 * loading it; [load] loads it into memory, which happens early only once [needed] is true.
 */
internal class DatasetAtStart(
    val updateDiskCopy: suspend () -> Unit,
    val needed: Flow<Boolean>,
    val load: suspend () -> Unit,
)

/**
 * The datasets at the app's start. On every device, each time the app has been in the foreground for [after] (the
 * first screen and Home's catalog requests go first), each dataset's disk copy is brought up to date in turn, unless
 * the network is down or metered then; nothing stays in memory. A process that lives for days (a TV, a suspended app)
 * so checks each time it comes back, which each dataset's own interval keeps to one request a day at most. Coming to
 * the foreground again before [after] has passed starts the wait over. Each dataset loads into memory as soon as its
 * [needed] says this device will use it, whatever the network, as a first lookup would load it.
 */
internal fun CoroutineScope.startDatasets(
    datasets: List<DatasetAtStart>,
    foreground: StateFlow<Boolean>,
    network: StateFlow<NetworkState>,
    after: Duration = START_DELAY,
) {
    for (dataset in datasets) {
        launch {
            dataset.needed.first { it }
            dataset.load()
        }
    }
    launch {
        foreground.filter { it }.collectLatest {
            delay(after)
            val now = network.value
            if (!now.online || now.metered) {
                Log.d(TAG) { "datasets: not updated (${if (now.online) "metered" else "offline"})" }
                return@collectLatest
            }
            // Outside collectLatest, so the next foreground doesn't cancel an update halfway; a second one meanwhile
            // waits for it and finds its check done.
            this@startDatasets.launch { for (dataset in datasets) dataset.updateDiskCopy() }
        }
    }
}

/** From each time the app comes to the foreground. */
internal val START_DELAY: Duration = 10.seconds

private const val TAG = "Datasets"
