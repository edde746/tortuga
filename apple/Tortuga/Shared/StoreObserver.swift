import Observation
import TortugaKit

/// A shared store's `StateFlow` as SwiftUI state. SKIE exports the flow as an `AsyncSequence` (`SkieSwiftStateFlow`);
/// this one generic wrapper mirrors it into an `@Observable` property, so no screen needs glue of its own. A view reads
/// `state` and runs `observe()` from `.task`:
///
/// ```swift
/// Text(store.state.title)
///     .task { await store.observe() }
/// ```
///
/// SwiftUI cancels that task when the view disappears, which cancels the collection on the Kotlin side, and starts
/// it again when the view reappears. While no view observes, `state` is the flow's value itself, so a view's first
/// frame never renders an empty placeholder, nor what the flow held when a view last observed it (a tab or a retained
/// screen coming back, before its `observe()` starts).
@MainActor
@Observable
final class StoreObserver<State> {
    /// The flow's latest value.
    var state: State {
        // Read either way: a view that read the flow's own value still updates once `observe()` mirrors it.
        let mirrored = mirrored
        return observers == 0 ? flow.value : mirrored
    }

    /// The flow's value as `observe()` last mirrored it.
    private var mirrored: State
    @ObservationIgnored private var observers = 0
    @ObservationIgnored private let flow: SkieSwiftStateFlow<State>

    init(_ flow: SkieSwiftStateFlow<State>) {
        self.flow = flow
        mirrored = flow.value
    }

    /// Mirrors the flow into `state` until the calling task is cancelled. A StateFlow's collector starts with the
    /// current value, so nothing emitted while no view observed is lost.
    func observe() async {
        observers += 1
        defer { observers -= 1 }
        mirrored = flow.value
        for await value in flow {
            mirrored = value
        }
    }
}
