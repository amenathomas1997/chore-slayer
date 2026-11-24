import SwiftUI
import UIKit

// AAA-contrast palette (matches the Android app; verified ≥ 7:1 with white text).
// Text colours adapt to dark mode. Light values are unchanged; dark values are
// ≥ 7:1 on #000 and #1C1C1E (and ≥ 4.5:1 on the dark task-row fills).
// The *Fill colours stay dark in both modes because they sit behind white text
// (prominent buttons, selected chips).
extension Color {
    /// Links, tinted text and strokes. Dark: #A8C7FA (≈ 9.9:1 on #1C1C1E).
    static let brand = Color.adaptive(light: 0x0D47A1, dark: 0xA8C7FA)
    /// Behind white text in both modes (8.6:1).
    static let brandFill = Color(hex: "#0D47A1")
    static let brandPurple = Color(red: 0x45 / 255.0, green: 0x27 / 255.0, blue: 0xA0 / 255.0)
    /// Secondary text. Dark: #BDBDBD (≈ 9:1 on #1C1C1E, ≈ 6.6:1 on task-row fills).
    static let muted = Color.adaptive(light: 0x545454, dark: 0xBDBDBD)
    /// Error / destructive text. Dark: #FF8A80 (≈ 7.4:1 on #1C1C1E).
    static let danger = Color.adaptive(light: 0xB00020, dark: 0xFF8A80)
    /// Behind white text in both modes (7.3:1).
    static let dangerFill = Color(hex: "#B00020")
    /// Success text and the "complete" row border. Dark: #81C995 (≈ 8.7:1 on #1C1C1E).
    static let goodGreen = Color.adaptive(light: 0x1B5E20, dark: 0x81C995)
    /// Behind white text in both modes (7.9:1).
    static let goodGreenFill = Color(hex: "#1B5E20")
    static let errorBg = Color.adaptive(light: 0xFFE3E3, dark: 0x3B1214)
    static let errorInk = Color.adaptive(light: 0x6E1414, dark: 0xFFB4AB) // 9.8:1 light, 9.6:1 dark on errorBg
    /// Row fills use primary text: dark fills give ≥ 12:1 with white text.
    static let taskDone = Color.adaptive(light: 0xD7F5DD, dark: 0x1E3B24)
    static let taskPartial = Color.adaptive(light: 0xFFF3CD, dark: 0x3D3410)

    /// A colour that switches with the light/dark appearance.
    static func adaptive(light: UInt32, dark: UInt32) -> Color {
        func ui(_ v: UInt32) -> UIColor {
            UIColor(red: CGFloat((v >> 16) & 0xFF) / 255.0,
                    green: CGFloat((v >> 8) & 0xFF) / 255.0,
                    blue: CGFloat(v & 0xFF) / 255.0, alpha: 1)
        }
        let l = ui(light), d = ui(dark)
        return Color(UIColor { $0.userInterfaceStyle == .dark ? d : l })
    }

    /// Build a Color from a "#rrggbb" hex string.
    init(hex: String) {
        let s = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        var v: UInt64 = 0
        Scanner(string: s).scanHexInt64(&v)
        self = Color(
            red: Double((v >> 16) & 0xFF) / 255.0,
            green: Double((v >> 8) & 0xFF) / 255.0,
            blue: Double(v & 0xFF) / 255.0)
    }
}

func colorName(_ hex: String) -> String {
    switch hex.lowercased() {
    case "#5b8def": return "Blue"
    case "#e8618c": return "Pink"
    case "#33b679": return "Green"
    case "#f5a623": return "Orange"
    case "#6d4fc0": return "Purple"
    case "#16a3b8": return "Teal"
    default: return "Color"
    }
}

// MARK: - Buttons

extension View {
    /// A filled primary button: white text on a fill that stays dark in both modes.
    func primaryButton(_ fill: Color = .brandFill) -> some View {
        buttonStyle(.borderedProminent).tint(fill)
    }
}

/// Lays children out in a row, or in a column at accessibility text sizes.
struct AdaptiveStack<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var spacing: CGFloat = 12
    @ViewBuilder let content: () -> Content
    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing))
            : AnyLayout(HStackLayout(spacing: spacing))
        layout { content() }
    }
}

// MARK: - VoiceOver helpers

enum Announcer {
    /// Speak a status message. Queued, so it waits for VoiceOver to finish the
    /// current utterance (e.g. a value change or a heading) instead of cutting it off.
    static func say(_ text: String, after delay: Double = 0) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            let s = NSAttributedString(string: text, attributes: [.accessibilitySpeechQueueAnnouncement: true])
            UIAccessibility.post(notification: .announcement, argument: s)
        }
    }
}

/// The main heading of a screen. When the screen appears, VoiceOver focus moves here.
private struct ScreenHeading: ViewModifier {
    @AccessibilityFocusState private var focused: Bool
    func body(content: Content) -> some View {
        content
            .accessibilityAddTraits(.isHeader)
            .accessibilityFocused($focused)
            .onAppear {
                guard UIAccessibility.isVoiceOverRunning else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { focused = true }
            }
    }
}

extension View {
    func screenHeading() -> some View { modifier(ScreenHeading()) }
}
