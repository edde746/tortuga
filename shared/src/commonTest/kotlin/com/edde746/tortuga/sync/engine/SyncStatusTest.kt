package com.edde746.tortuga.sync.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStatusTest {
    @Test
    fun onlyAFailureOrNoConnectionAsksForAttention() {
        val kinds = listOf(SyncStatus.Kind.Idle, SyncStatus.Kind.Syncing, SyncStatus.Kind.Offline, SyncStatus.Kind.SignedOut) +
            SyncStatus.Failure.entries.map { SyncStatus.Kind.Error(it) }
        assertEquals(
            listOf(SyncStatus.Kind.Offline) + SyncStatus.Failure.entries.map { SyncStatus.Kind.Error(it) },
            kinds.filter { it.needsAttention },
        )
    }
}
