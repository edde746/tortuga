package com.edde746.tortuga.playback

import kotlinx.serialization.Serializable

/**
 * The most channels decoded audio plays with (the Audio Channels setting, `AudioChannelLimit`), as mpv's
 * `audio-channels` ([mpv]); this device keeps the last one chosen (`DeviceSetting.AudioChannels`).
 */
@Serializable
enum class AudioChannels(val mpv: String) {
    /** Every channel of the track (mpv's default, `auto-safe`). */
    ORIGINAL("auto-safe"),

    /**
     * 7.1 mixed down to 5.1 for TVs and receivers that only take 5.1 PCM; stereo stays stereo (a list, so mpv picks
     * the closest layout rather than padding). Passthrough is unaffected.
     */
    SURROUND_51("stereo,5.1"),

    /** Mixed down to two channels for stereo speakers and headphones; the audio is decoded, never passed through. */
    STEREO("stereo"),
}

/**
 * Loudness normalization on desktop and Apple, as mpv's `af`: lavfi `loudnorm` at streaming loudness, then
 * mpv's own `format` filter back to 48 kHz float, since `loudnorm` outputs 192 kHz doubles that the audio output would
 * otherwise convert on its deadline. Android uses [ANDROID_LOUDNESS_NORMALIZATION_FILTER]. Normalized audio is decoded,
 * never passed through.
 */
const val LOUDNESS_NORMALIZATION_FILTER = "loudnorm=I=-14:TP=-3:LRA=4,format=srate=48000:format=floatp"

/**
 * Android's loudness normalization, as mpv's `af`: `acompressor` as a slow gain control toward -14 LUFS, then
 * `alimiter` as a -3 dB ceiling with 1 ms of look-ahead. `link=maximum` drives every channel's gain from the loudest,
 * so 5.1 keeps its balance without a fold to stereo, and the chain stays at the source sample rate.
 * [LOUDNESS_NORMALIZATION_FILTER] is too heavy there: it runs on mpv's core thread, which hands Android's video frames
 * on, and its 192 kHz pass made them late on 32-bit TV boxes. The platform's DynamicsProcessing on the AudioTrack's
 * session (mpv-build's `audiotrack-normalize`) is no substitute: on a Galaxy Z Fold6 it silenced 5.1 and stuttered the
 * video (its ring buffer overflowed and the audio HAL spun), and it left a Fold3's spatialized 5.1
 * untouched. Needs mpv-build `957086a` or later, whose Android FFmpeg has both filters. Normalized audio is decoded,
 * never passed through.
 */
const val ANDROID_LOUDNESS_NORMALIZATION_FILTER =
    "acompressor=threshold=-34dB:ratio=4:knee=6dB:attack=30:release=300:makeup=15dB:link=maximum," +
        "alimiter=limit=-3dB:attack=1:release=60:level=false"

/**
 * mpv's `audio-spdif` for passthrough of [codecs] (mpv's names: `ac3`, `eac3`, `dts`, `dts-hd`, `truehd`; the formats
 * the platform's output takes undecoded): empty while anything needs decoded PCM. A filter can't
 * process a bitstream, so normalized audio is decoded; [AudioChannels.STEREO] promises two channels for everything that
 * plays; and mpv can't change the tempo of a bitstream, so any [speed] but 1 decodes. [AudioChannels.SURROUND_51] only
 * shapes decoded audio, so a bitstream passes it.
 */
fun passthroughSpdif(codecs: List<String>, normalization: Boolean, channels: AudioChannels, speed: Double): String =
    if (normalization || channels == AudioChannels.STEREO || speed != 1.0) "" else codecs.joinToString(",")

/**
 * An audio output mpv can play to (an entry of its `audio-device-list`): [name] is what `audio-device` takes ("auto"
 * for the system's choice), [description] what a menu shows.
 */
data class AudioDevice(val name: String, val description: String)
