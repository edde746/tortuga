import SwiftUI
import TortugaKit

/// A profile's avatar: its colour as a circle with the name's first letter (`profileInitial`) in the avatar's
/// foreground colour, sized to the circle (`profile.initial`: it keeps its size at every text size, as the circle does).
struct ProfileAvatarTile: View {
    let initial: String
    let avatar: ProfileAvatar
    let size: CGFloat

    var body: some View {
        Circle()
            .fill(Color(argb: avatar.background))
            .frame(width: size, height: size)
            .overlay {
                Text(initial)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(Color(argb: avatar.foreground))
                    .accessibilityIdentifier("profile.initial")
            }
            .accessibilityHidden(true)
    }
}

/// A round tile (a profile, "Add profile", an avatar choice, a PIN key) as a button: on tvOS it grows and casts a
/// shadow when focused and shrinks a little while clicked (`tvPressed`); elsewhere it dims while pressed (the label
/// draws its focus ring for keyboard focus).
struct TileButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Tile(configuration: configuration)
    }

    private struct Tile: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            #if os(tvOS)
            configuration.label
                .scaleEffect(focused ? 1.1 : 1)
                .shadow(color: .black.opacity(focused ? 0.5 : 0), radius: 16, y: 8)
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
            #else
            configuration.label
                .opacity(configuration.isPressed ? 0.7 : 1)
            #endif
        }
    }
}

/// A four-digit PIN pad (entering a profile's PIN, or setting one): `title`, one dot per digit with `entered` filled
/// (spoken as the store's `enteredText`), `message` (a wrong PIN, the lockout countdown, a mismatch), digits 0–9 and
/// backspace, and Cancel (not on tvOS, where the remote's Back is the way out). Digit and Delete keys work from a
/// keyboard; Back and Escape cancel. While `acceptsInput` is false (checking, locked out) the keys stay focusable, so
/// focus stays on the pad, and show dimmed; the store ignores them. In a tvOS `TrailingPanel` it lies on the panel's
/// surface; focus goes to 1 when it shows (from the screen under the panel too). The message is announced as it shows
/// (`announce`), the lockout's countdown as it starts, not at each second.
struct PinPad<Header: View>: View {
    let title: String
    let entered: Int
    let length: Int
    let enteredText: String
    let message: String?
    let acceptsInput: Bool
    let onDigit: (Int) -> Void
    let onBackspace: () -> Void
    let onCancel: () -> Void
    @ViewBuilder var header: () -> Header

    @Environment(\.tortugaDimens) private var dimens
    #if os(tvOS)
    @Environment(\.isInSheet) private var inSheet
    #endif
    @FocusState private var focusedKey: Int?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header()
                Text(title).font(.title2.weight(.semibold)).multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                HStack(spacing: 16) {
                    ForEach(0..<length, id: \.self) { index in
                        Circle()
                            .fill(index < entered ? Palette.onBackground : Palette.surfaceContainerHighest)
                            .frame(width: 16, height: 16)
                    }
                }
                .accessibilityElement()
                .accessibilityLabel(enteredText)
                Text(message ?? " ")
                    .foregroundStyle(Palette.error)
                    .multilineTextAlignment(.center)
                    // The store clears its message as a digit is typed, so a second wrong PIN shows (and is spoken) anew.
                    .onChange(of: message != nil, initial: true) { _, shown in
                        if shown, let message { announce(message) }
                    }
                VStack(spacing: 12) {
                    ForEach([[1, 2, 3], [4, 5, 6], [7, 8, 9]], id: \.self) { row in
                        HStack(spacing: 16) {
                            ForEach(row, id: \.self) { digit in
                                digitKey(digit)
                            }
                        }
                    }
                    HStack(spacing: 16) {
                        Color.clear.frame(width: dimens.pinKey, height: dimens.pinKey)
                        digitKey(0)
                        Button(action: onBackspace) {
                            Image(systemName: "delete.left")
                                .font(.title2)
                                .frame(width: dimens.pinKey, height: dimens.pinKey)
                                .contentShape(Circle())
                        }
                        .buttonStyle(TileButtonStyle())
                        #if os(macOS)
                        .tooltip(T.shared.profiles.pin.backspace)
                        #endif
                        .accessibilityLabel(T.shared.profiles.pin.backspace)
                        .accessibilityIdentifier("pin.backspace")
                    }
                }
                .focusSectionIfAvailable()
                #if !os(tvOS)
                ActionButton(title: T.shared.profiles.pin.cancel, id: "pin.cancel", prominence: .plain, fullWidth: false, action: onCancel)
                    .keyboardShortcut(.cancelAction)
                #endif
            }
            .padding(dimens.screenPadding)
            .frame(maxWidth: .infinity)
        }
        .background(inPanel ? Color.clear : Palette.background)
        .backAction(onCancel)
        #if !os(tvOS)
        .onKeyPress(characters: .decimalDigits) { press in
            guard let digit = Int(press.characters) else { return .ignored }
            onDigit(digit)
            return .handled
        }
        .onKeyPress(.delete) {
            onBackspace()
            return .handled
        }
        #endif
        .onAppear { focusedKey = 1 }
        #if os(tvOS)
        .task { await FocusRestore.restore($focusedKey, to: 1) }
        #endif
    }

    /// In a tvOS `TrailingPanel`, on its surface.
    private var inPanel: Bool {
        #if os(tvOS)
        inSheet
        #else
        false
        #endif
    }

    private func digitKey(_ digit: Int) -> some View {
        Button { onDigit(digit) } label: {
            Text(String(digit))
                .font(.title.weight(.semibold))
                .foregroundStyle(acceptsInput ? Palette.onBackground : Palette.onSurfaceVariant)
                .frame(width: dimens.pinKey, height: dimens.pinKey)
                .background(Palette.surfaceContainerHigh, in: Circle())
                .focusRing(radius: dimens.pinKey / 2)
        }
        .buttonStyle(TileButtonStyle())
        .focused($focusedKey, equals: digit)
        .accessibilityIdentifier("pin.digit.\(digit)")
    }
}

/// A parent's PIN over a kids profile (`AppOverlay.ParentPin`): the profile PIN pad, saying what it is for. The shared
/// `AppSession` makes its store while the overlay shows; the store ignores digits while it is checking or locked out.
struct ParentPinRoute: View {
    let app: AppModel

    var body: some View {
        LiveStore(
            create: { app.session.parentPinStore() },
            state: { $0.state },
            release: { $0.release() }
        ) { store, state in
            PinPad(
                title: state.title,
                entered: Int(state.entered),
                length: Int(state.length),
                enteredText: state.enteredText,
                message: state.error,
                acceptsInput: true,
                onDigit: { store.digit(digit: Int32($0)) },
                onBackspace: store.backspace,
                onCancel: store.cancel
            ) {
                Text(state.subtitle).multilineTextAlignment(.center)
            }
        }
    }
}
