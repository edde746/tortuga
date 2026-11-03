package com.edde746.tortuga.trackers

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.playback.resolve.FakeHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The title's meta the trackers, the downloads and Watch together take from the profile's addons ([firstMeta]). */
class FirstMetaTest {
    @Test
    fun anEarlierAddonStillAnsweringIsWaitedFor() = runTest {
        val graph = testGraph(
            http = FakeHttp(this) { request ->
                val name = request.url.host.substringBefore('.')
                if (name == "first") delay(5.seconds)
                json("""{"meta":{"id":"tt1","type":"movie","name":"$name"}}""")
            }.client,
        )
        val profile = graph.data.profiles.create("Ann", null)
        graph.data.addons.replaceAll(
            profile.id,
            listOf("first", "second").map { name ->
                AddonDescriptor(
                    "https://$name.example.com/manifest.json",
                    """{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["movie"],"resources":["meta"]}""",
                )
            },
        )

        // The second addon answers first; the first one in the installed order is Detail's pick.
        assertEquals("first", graph.firstMeta(profile.id, "movie", "tt1")?.name)
    }
}
