package com.edde746.tortuga.app

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.SubtitleColor
import com.edde746.tortuga.data.settings.SubtitleOutline
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.players.CustomPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DevSettingTest {
    @Test
    fun switchesTakeTrueOrFalseAndTheCacheASizeInItsRangeOrAuto() {
        assertEquals(DevSetting.Switch(DeviceSetting.HardwareDecoding, false), DevSetting.parse("video.hardwareDecoding=false"))
        assertEquals(DevSetting.Switch(DeviceSetting.MatchFrameRate, true), DevSetting.parse("video.matchFrameRate=true"))
        assertEquals(DevSetting.Switch(DeviceSetting.AudioNormalization, true), DevSetting.parse("audio.normalization=true"))
        assertEquals(DevSetting.Switch(DeviceSetting.DiscordPresence, true), DevSetting.parse("discord.presence=true"))
        assertEquals(DevSetting.CacheSize(512), DevSetting.parse("playback.cacheSizeMegabytes=512"))
        assertEquals(DevSetting.CacheSize(16), DevSetting.parse("playback.cacheSizeMegabytes=16"))
        assertEquals(DevSetting.CacheSize(null), DevSetting.parse("playback.cacheSizeMegabytes=auto"))
    }

    @Test
    fun thePlayersChoicesTakeTheirValuesByNameInAnyCase() {
        assertEquals(DevSetting.Value(DeviceSetting.VideoFit, VideoFit.COVER, "cover"), DevSetting.parse("video.fit=cover"))
        assertEquals(DevSetting.Value(DeviceSetting.AudioChannels, AudioChannels.SURROUND_51, "surround_51"), DevSetting.parse("audio.channels=surround_51"))
        assertEquals(DevSetting.Later(DevSetting.Value(DeviceSetting.VideoFit, VideoFit.FILL, "FILL"), 10.0), DevSetting.parse("video.fit=FILL@10"))
    }

    @Test
    fun theSubtitleStyleTakesItsSettingsValuesNumbersInTheirRangesAndNamesInAnyCase() {
        assertEquals(DevSetting.Value(DeviceSetting.SubtitleSize, 150, "150"), DevSetting.parse("subtitles.sizePercent=150"))
        assertEquals(DevSetting.Value(DeviceSetting.SubtitleTextColor, SubtitleColor.YELLOW, "yellow"), DevSetting.parse("subtitles.textColor=yellow"))
        assertEquals(DevSetting.Value(DeviceSetting.SubtitleOutlineThickness, SubtitleOutline.THICK, "THICK"), DevSetting.parse("subtitles.outline=THICK"))
        assertEquals(DevSetting.Value(DeviceSetting.SubtitleBackground, 0, "0"), DevSetting.parse("subtitles.backgroundPercent=0"))
        assertEquals(DevSetting.Value(DeviceSetting.SubtitleStyledOverride, true, "true"), DevSetting.parse("subtitles.styledOverride=true"))
        assertEquals(
            DevSetting.Later(DevSetting.Value(DeviceSetting.SubtitleSize, 200, "200"), 60.0),
            DevSetting.parse("subtitles.sizePercent=200@60"),
        )
    }

    @Test
    fun theRelayTakesARelayUrlStoredCanonicalOrPublic() {
        assertEquals(
            DevSetting.Value(DeviceSetting.WatchRelay, "http://127.0.0.1:18180", "HTTP://127.0.0.1:18180/"),
            DevSetting.parse("watch.relay=HTTP://127.0.0.1:18180/"),
        )
        assertEquals(DevSetting.Value(DeviceSetting.WatchRelay, "https://relay.example/tortuga", "https://relay.example:443/tortuga"), DevSetting.parse("watch.relay=https://relay.example:443/tortuga"))
        assertEquals(DevSetting.Value<String?>(DeviceSetting.WatchRelay, null, "public"), DevSetting.parse("watch.relay=public"))
        assertEquals(DevSetting.Value<String?>(DeviceSetting.WatchRelay, null, ""), DevSetting.parse("watch.relay="))
    }

    @Test
    fun thePlayerTakesAnIdAndTheCustomPlayerANameACommandAndArguments() {
        assertEquals(DevSetting.Value<String?>(DeviceSetting.Player, "vlc", "vlc"), DevSetting.parse("playback.player=vlc"))
        assertEquals(DevSetting.Value<String?>(DeviceSetting.Player, "custom:dev", "custom:dev"), DevSetting.parse("playback.player=custom:dev"))
        assertEquals(DevSetting.Value<String?>(DeviceSetting.Player, null, "tortuga"), DevSetting.parse("playback.player=tortuga"))
        assertEquals(
            DevSetting.CustomPlayers(CustomPlayer("dev", "My mpv", "mpv", "--fs --start={start} {url}")),
            DevSetting.parse("playback.customPlayer=My mpv;mpv;--fs --start={start} {url}"),
        )
        // A URL template: no arguments, and an `@` of its own.
        val template = DevSetting.parse("playback.customPlayer=Handoff;http://user@192.168.1.5:8765/handoff?url={url}")
        assertEquals(DevSetting.CustomPlayers(CustomPlayer("dev", "Handoff", "http://user@192.168.1.5:8765/handoff?url={url}", "")), template)
        assertEquals("playback.customPlayer", template.key)
        for (bad in listOf("playback.customPlayer=mpv", "playback.customPlayer=;mpv;{url}", "playback.customPlayer=")) {
            assertFailsWith<IllegalArgumentException>(bad) { DevSetting.parse(bad) }
        }
    }

    @Test
    fun offlineAndFastTimersTakeTrueOrFalse() {
        assertEquals(DevSetting.Offline(true), DevSetting.parse("offline=true"))
        assertEquals(DevSetting.Later(DevSetting.Offline(false), 30.0), DevSetting.parse("offline=false@30"))
        assertFailsWith<IllegalArgumentException> { DevSetting.parse("offline=yes") }
        assertEquals(DevSetting.FastTimers(true), DevSetting.parse("player.fastTimers=true"))
        assertEquals(DevSetting.FastTimers(false), DevSetting.parse("player.fastTimers=false"))
        assertEquals(DevSetting.Later(DevSetting.FastTimers(true), 5.0), DevSetting.parse("player.fastTimers=true@5"))
        assertFailsWith<IllegalArgumentException> { DevSetting.parse("player.fastTimers=yes") }
    }

    @Test
    fun thePosterTemplateTakesTheTemplateAsGiven() {
        assertEquals(
            DevSetting.PosterTemplate("http://127.0.0.1:8766/art/poster/{type}-{imdb_id}.png"),
            DevSetting.parse("appearance.posterTemplate=http://127.0.0.1:8766/art/poster/{type}-{imdb_id}.png"),
        )
        assertEquals(DevSetting.PosterTemplate(""), DevSetting.parse("appearance.posterTemplate="))
        assertEquals(
            DevSetting.PosterTemplate("https://images.example/{imdb_id}@2x.png"),
            DevSetting.parse("appearance.posterTemplate=https://images.example/{imdb_id}@2x.png"),
        )
        assertEquals(DevSetting.PosterTemplate("https://images.example/{imdb_id}@60"), DevSetting.parse("appearance.posterTemplate=https://images.example/{imdb_id}@60"))
    }

    @Test
    fun anythingElseIsRefused() {
        for (bad in listOf(
            "video.hardwareDecoding", "video.hardwareDecoding=off", "video.hdr=", "playback.cacheSizeMegabytes=15",
            "playback.cacheSizeMegabytes=4097", "playback.cacheSizeMegabytes=big", "app.activeProfileId=a", "hwdec=no",
            "subtitles.sizePercent=40", "subtitles.liftPercent=51", "subtitles.textColor=magenta", "subtitles.outline=",
            "subtitles.bold=yes", "subtitles.sizePercent=200@", "subtitles.sizePercent=200@-1", "subtitles.sizePercent=200@1@2",
            "video.fit=zoom", "video.fit=", "audio.channels=7.1", "audio.normalization=on", "video.output=metal",
            "watch.relay=ws://relay.example", "watch.relay=relay.example", "watch.relay=https://relay.example/?a=1",
            "watch.relay=https://user@relay.example", "player.fastTimers=on", "player.fastTimers=",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { DevSetting.parse(bad) }
        }
    }
}
