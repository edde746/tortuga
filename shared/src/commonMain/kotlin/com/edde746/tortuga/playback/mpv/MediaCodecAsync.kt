package com.edde746.tortuga.playback.mpv

/**
 * Whether an Android device's MediaCodec decoders run asynchronously (`async=1` in mpv's `vd-lavc-o`, an option of the
 * bundled FFmpeg's MediaCodec decoder).
 *
 * A synchronous decoder is polled for its buffers, an asynchronous one calls back when it has one. Playing a 1080p24
 * H.264 film, a Fire TV Stick 4K Max (2nd generation) spent 23 % of a core in the app with the synchronous decoder and
 * 17 % with the asynchronous one, its threads woken 1,640 and 810 times a second; a SHIELD TV Pro (2019) 11.4 % and
 * 8.4 %, 1,590 and 710.
 *
 * From [TRUSTED_SDK] on every device decodes asynchronously. Below it asynchronous MediaCodec has faults of single
 * devices, so only the devices it was checked on get it ([VERIFIED]), each at the API level it was checked at: a system
 * update brings another MediaCodec.
 */
object MediaCodecAsync {
    /** The API level from which asynchronous MediaCodec is trusted on every device: Media3's threshold for its default. */
    const val TRUSTED_SDK = 31

    /** A device (`Build.DEVICE`) at the API level asynchronous decoding was checked on it. */
    private data class Device(val name: String, val sdk: Int)

    private val VERIFIED = setOf(
        // NVIDIA SHIELD TV Pro (2019) on Android 9.
        Device("mdarcy", 28),
        // Amazon Fire TV Stick 4K Max (2nd generation) on Fire OS 8.
        Device("karat", 30),
    )

    /** Whether the device named [device] (`Build.DEVICE`) on API level [sdk] decodes asynchronously. */
    fun enabled(sdk: Int, device: String): Boolean = sdk >= TRUSTED_SDK || Device(device, sdk) in VERIFIED
}
