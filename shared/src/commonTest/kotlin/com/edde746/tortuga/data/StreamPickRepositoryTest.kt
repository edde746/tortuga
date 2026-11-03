package com.edde746.tortuga.data

import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.streams.StreamPickRepository
import com.edde746.tortuga.playback.streams.ResolutionBadge
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each profile keeps its newest picks of the last year per kind of device, read together; a deleted profile's go with
 * it.
 */
class StreamPickRepositoryTest {
    private fun pick(id: String, deviceClass: DeviceClass = DeviceClass.HANDHELD) = StreamPick(
        id = id,
        metaId = "tt1",
        videoId = "tt1",
        runtimeSeconds = 6000,
        pickedAt = 0,
        context = PickContext(deviceClass, metered = true, hdrDisplay = false, dolbyVisionDisplay = false, hardwareCodecs = emptySet()),
        source = PickSource.LIST,
        candidates = listOf(PickedStream(resolution = ResolutionBadge.FHD_1080), PickedStream(resolution = ResolutionBadge.HD_720)),
        chosen = 1,
        outcome = null,
    )

    @Test
    fun theNewestPicksOfTheLastYearAreKeptPerKindOfDevice() = runTest {
        val clock = TestClock()
        val data = testData(clock)
        val profile = data.profiles.create("Ann", null).id
        data.streamPicks.record(profile, pick("old"))
        data.streamPicks.record(profile, pick("old-tv", DeviceClass.TV))
        clock.now += 1000
        data.streamPicks.record(profile, pick("tv", DeviceClass.TV))
        clock.now += 366L * 24 * 60 * 60 * 1000
        repeat(StreamPickRepository.KEEP + 1) {
            clock.now += 1000
            data.streamPicks.record(profile, pick("p$it"))
        }
        val kept = data.streamPicks.picks(profile)
        assertEquals((1..StreamPickRepository.KEEP).map { "p$it" }, kept.filter { it.context.deviceClass == DeviceClass.HANDHELD }.map { it.id })
        assertEquals(emptyList(), kept.filter { it.context.deviceClass == DeviceClass.TV }.map { it.id }, "a year old on the TV too")
        assertEquals(true, kept.first().context.metered)
    }

    @Test
    fun aPhonesPicksDontCrowdOutTheTvs() = runTest {
        val clock = TestClock()
        val data = testData(clock)
        val profile = data.profiles.create("Ann", null).id
        data.streamPicks.record(profile, pick("tv", DeviceClass.TV))
        repeat(StreamPickRepository.KEEP + 1) {
            clock.now += 1000
            data.streamPicks.record(profile, pick("p$it"))
        }
        val kept = data.streamPicks.picks(profile)
        assertEquals(StreamPickRepository.KEEP + 1, kept.size)
        assertEquals("tv", kept.first().id, "oldest first, every kind of device")
    }

    @Test
    fun picksOfEveryKindOfDeviceAreReadTogetherAndGoWithTheirProfile() = runTest {
        val clock = TestClock()
        val data = testData(clock)
        val profile = data.profiles.create("Ann", null).id
        val kids = data.profiles.create("Bo", null, kids = true).id
        data.streamPicks.record(profile, pick("phone"))
        clock.now += 1000
        data.streamPicks.record(profile, pick("tv", DeviceClass.TV))
        data.streamPicks.record(kids, pick("kids"))
        assertEquals(listOf("phone" to DeviceClass.HANDHELD, "tv" to DeviceClass.TV), data.streamPicks.picks(profile).map { it.id to it.context.deviceClass })
        assertEquals(listOf("kids"), data.streamPicks.picks(kids).map { it.id }, "a kids profile records as any other")

        data.profiles.delete(profile)
        assertEquals(emptyList(), data.streamPicks.picks(profile))
        assertEquals(listOf("kids"), data.streamPicks.picks(kids).map { it.id })
    }
}
