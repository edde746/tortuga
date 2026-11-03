import SwiftUI

/// A question the store words before a change it can't undo (removing an addon, signing out, ending a room), with the
/// identifiers of its answers: confirm and cancel, and where the question offers one, an `alternative` before confirm
/// that is never destructive (Keep synced's Keep downloads beside Delete downloads). An empty `message` shows the title
/// alone.
struct Confirmation: Identifiable {
    let title: String
    let message: String
    let confirmLabel: String
    let cancelLabel: String
    var destructive = true
    let confirmId: String
    let cancelId: String
    var alternative: (label: String, id: String)?

    var id: String { "\(confirmId)\u{0}\(title)" }
}

extension View {
    /// Asks `confirmation` while it is set, in the system alert: its `alternative` (`onAlternative`) first where it has
    /// one, then confirm (`onConfirm`) and Cancel. Dismissing it (Cancel, the remote's Menu, Escape) is `onCancel`.
    func confirmation(
        _ confirmation: Confirmation?,
        onConfirm: @escaping () -> Void,
        onAlternative: @escaping () -> Void = {},
        onCancel: @escaping () -> Void
    ) -> some View {
        alert(
            confirmation?.title ?? "",
            isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { onCancel() } }),
            presenting: confirmation
        ) { confirmation in
            if let alternative = confirmation.alternative {
                Button(alternative.label, action: onAlternative)
                    .accessibilityIdentifier(alternative.id)
            }
            Button(confirmation.confirmLabel, role: confirmation.destructive ? .destructive : nil, action: onConfirm)
                .accessibilityIdentifier(confirmation.confirmId)
            Button(confirmation.cancelLabel, role: .cancel, action: onCancel)
                .accessibilityIdentifier(confirmation.cancelId)
        } message: { confirmation in
            if !confirmation.message.isEmpty { Text(confirmation.message) }
        }
    }
}
