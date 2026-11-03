package com.edde746.tortuga.data.sync

import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPickRepository
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a stream pick's record weighs, from the stream lists in `fixtures/streams`: every window of the streams
 * a pick keeps ([StreamPicks.ABOVE] above the chosen one, [StreamPicks.BELOW] below) of each list, the whole list when
 * it is shorter. A typical pick stays under 4 KiB, and a profile's picks (three kinds of device, each keeping
 * [StreamPickRepository.KEEP]) under an eighth of the server's default account quota, which counts a record's payload,
 * its id sealed ([SEALED_ID_OVERHEAD] more bytes) and [ROW_BYTES] for its row. The numbers are printed.
 */
class StreamPickRecordSizeTest {
    private val key = RecordKey.StreamPick("0f6c2d7e-6a51-4c1b-9d55-2b8e3f1a7c40", "5a3e9b12-8c47-4f0e-a1d6-73c2b9e4f058")
    private val hlc = Hlc(1_790_000_000_000, 0, "0123456789abcdef")

    private fun payload(candidates: List<PickedStream>, firstRow: Int): String = SyncRecord(
        key,
        hlc,
        StreamPickBody(
            metaId = "ex0014",
            videoId = "ex0014:1:1",
            runtimeSeconds = 1440,
            pickedAt = 1_790_000_000_000,
            deviceClass = "tv",
            metered = false,
            hdrDisplay = true,
            dolbyVisionDisplay = false,
            hardwareCodecs = listOf("HEVC", "AVC"),
            source = "list",
            candidates = candidates,
            chosen = minOf(StreamPicks.ABOVE, candidates.size - 1),
            firstRow = firstRow,
            outcome = "good",
            outcomeAt = 1_790_000_300_000,
        ),
    ).encode()

    private fun quotaBytes(payload: String) =
        payload.encodeToByteArray().size + key.recordId().encodeToByteArray().size + SEALED_ID_OVERHEAD + ROW_BYTES

    @Test
    fun aPickFitsARecordAndAProfilesPicksFitTheAccountQuota() {
        val lists = FixtureCorpus.files
            .filterKeys { it.startsWith("streams/") && it != "streams/index.json" }
            .values
            .mapNotNull { text -> (AddonResponse.decode(text).content as? ResourceContent.Streams)?.items }
            .filter { it.isNotEmpty() }
            .map { items -> items.map { stream -> StreamBadgeParser.parse(stream).let { PickedStream.of(it, StreamFilter.sizeOf(stream, it)) } } }
        val keep = StreamPicks.ABOVE + 1 + StreamPicks.BELOW
        val payloads = lists.flatMap { streams ->
            (0..maxOf(0, streams.size - keep)).map { start -> payload(streams.subList(start, minOf(streams.size, start + keep)), start) }
        }.sortedBy { it.encodeToByteArray().size }
        val typical = payloads[payloads.size / 2]
        val largest = payloads.last()
        val typicalBytes = typical.encodeToByteArray().size
        val largestBytes = largest.encodeToByteArray().size

        val largestWrite = RecordWrite(key.recordId(), 1, payload = Json.parseToJsonElement(largest).jsonObject)
        val pushBytes = SyncProtocol.json.encodeToString(PushRequest.serializer(), PushRequest(List(SyncProtocol.MAX_WRITES_PER_PUSH) { largestWrite }))
            .encodeToByteArray().size
        val profileBytes = 3L * StreamPickRepository.KEEP * quotaBytes(typical)
        val tombstoneBytes = quotaBytes(SyncRecord(key, hlc, null).encode())
        println(
            "stream pick record: ${payloads.size} picks from ${lists.size} lists; typical $typicalBytes B, largest $largestBytes B; " +
                "${SyncProtocol.MAX_WRITES_PER_PUSH} of the largest in one push $pushBytes B; quota: a profile's 3 x " +
                "${StreamPickRepository.KEEP} typical picks ${profileBytes / 1024} KiB of ${DEFAULT_ACCOUNT_QUOTA_BYTES / 1024} KiB, " +
                "a tombstone $tombstoneBytes B",
        )

        assertTrue(typicalBytes < 4 * 1024, "a typical pick: $typicalBytes B")
        assertTrue(largestBytes < SyncProtocol.MAX_RECORD_BYTES, "the largest pick: $largestBytes B")
        assertTrue(pushBytes < SyncProtocol.MAX_REQUEST_BYTES, "a push of the largest: $pushBytes B")
        assertTrue(profileBytes < DEFAULT_ACCOUNT_QUOTA_BYTES / 8, "a profile's picks: $profileBytes B")
    }

    private companion object {
        /** RecordCipher's format byte, 12-byte nonce and 16-byte tag around a sealed record id (server). */
        const val SEALED_ID_OVERHEAD = 29

        /** SyncRecords.ROW_BYTES (server). */
        const val ROW_BYTES = 256

        /** ServerConfig.accountQuotaBytes's default (server). */
        const val DEFAULT_ACCOUNT_QUOTA_BYTES = 64L * 1024 * 1024
    }
}
