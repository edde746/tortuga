import TortugaKit

/// One playback's output on Apple platforms (the shared `PlaybackOutput` drives it: the policy is shared, the switching
/// native). tvOS's `AVDisplayManager` picks the TV's mode from the criteria the surface writes, and iOS hands an app no
/// display modes; iOS and macOS show HDR per layer (EDR) without a display switch. So the display reports its HDR as on
/// wherever it can show HDR, and modes only on the Mac (the refresh-rate variants of the window's display's current
/// mode, `DisplayModeSwitch`). Each plan goes to the surface, which turns its HDR and frame-rate decisions into criteria
/// (tvOS), EDR (iOS) or EDR and the plan's mode (macOS) for the video on it, and its passthrough to
/// the engine, which names the formats the system's outputs take undecoded.
@MainActor
final class ApplePlaybackOutput: @preconcurrency PlaybackOutputHost {
    private let surface: VideoSurface
    private let engine: MpvPlayerEngine

    init(surface: VideoSurface, engine: MpvPlayerEngine) {
        self.surface = surface
        self.engine = engine
    }

    /// The display (SKIE exposes the Kotlin `suspend fun display` as this async requirement), read on the main actor.
    nonisolated func __display() async throws -> DisplayInfo {
        #if os(macOS)
        let (hdr, modes) = await MainActor.run { (ApplePlatform.hdrOutputSupported, self.surface.modes()) }
        return DisplayInfo(modes: modes?.variants.map(\.displayMode) ?? [], current: modes?.current.displayMode, hdrSupported: hdr, hdrEnabled: hdr)
        #else
        let hdr = await MainActor.run { ApplePlatform.hdrOutputSupported }
        return DisplayInfo(modes: [], current: nil, hdrSupported: hdr, hdrEnabled: hdr)
        #endif
    }

    func apply(plan: OutputPlan) {
        surface.apply(plan)
        engine.setAudioPassthrough(plan.audioPassthrough)
    }

    func restore() {
        surface.resetDisplay()
    }
}
