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

// MARK: - Spoken text

/// Text with decorative emoji, arrows, bullets and separators removed, for
/// VoiceOver labels ("🎉 See my day" → "See my day", "a · b" → "a, b").
func plainSpoken(_ s: String) -> String {
    var out = s.replacingOccurrences(of: " · ", with: ", ")
        .replacingOccurrences(of: " → ", with: " to ")
    out = String(out.filter { c in
        if "←→↳•·★☆".contains(c) { return false }
        return !c.unicodeScalars.contains { $0.properties.isEmojiPresentation || $0.value == 0xFE0F }
    })
    return out.split(separator: " ", omittingEmptySubsequences: true).joined(separator: " ")
        .trimmingCharacters(in: .whitespaces)
}

// MARK: - Dates and times

/// The server's "yyyy-MM-dd" days, read as local midnight (for DatePicker and display).
private let ymdFormatter: DateFormatter = {
    let f = DateFormatter()
    f.calendar = Calendar(identifier: .gregorian)
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyy-MM-dd"
    return f
}()
private let hmFormatter: DateFormatter = {
    let f = DateFormatter()
    f.calendar = Calendar(identifier: .gregorian)
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "HH:mm"
    return f
}()

func dateFromYMD(_ s: String) -> Date? { ymdFormatter.date(from: s) }
func ymdString(_ d: Date) -> String { ymdFormatter.string(from: d) }

/// "Tuesday, November 24" instead of "2026-11-24", on screen and for VoiceOver.
func displayDay(_ ymd: String) -> String {
    guard let d = dateFromYMD(ymd) else { return ymd }
    return d.formatted(.dateTime.weekday(.wide).month(.wide).day())
}

/// "7:30 AM" (or the 24-hour form, by the device's setting) instead of "07:30".
func displayTime(_ hm: String) -> String {
    guard let d = hmFormatter.date(from: String(hm.prefix(5))) else { return hm }
    return d.formatted(date: .omitted, time: .shortened)
}

/// A DatePicker binding over a "yyyy-MM-dd" string.
func dayBinding(_ s: Binding<String>, fallback: String) -> Binding<Date> {
    Binding(
        get: { dateFromYMD(s.wrappedValue) ?? dateFromYMD(fallback) ?? Date() },
        set: { s.wrappedValue = ymdString($0) })
}

/// A DatePicker binding over an "HH:MM" string.
func timeBinding(_ s: Binding<String>) -> Binding<Date> {
    Binding(
        get: { hmFormatter.date(from: String(s.wrappedValue.prefix(5))) ?? hmFormatter.date(from: "08:00")! },
        set: { s.wrappedValue = hmFormatter.string(from: $0) })
}

/// A suggested time when "Set a specific time" is first turned on.
func defaultTime(forSlot slot: String) -> String {
    switch slot {
    case "morning": return "07:30"
    case "midday": return "12:00"
    case "afternoon": return "15:30"
    case "evening": return "18:30"
    default: return "08:00"
    }
}

// MARK: - Form fields

/// A visible label above a field (not only a placeholder). The label is hidden
/// from VoiceOver because the field itself carries the same name.
struct LabeledField<Content: View>: View {
    let title: String
    var hint: String? = nil
    /// Shown under the field, next to the control that caused it.
    var error: String? = nil
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // The visible label is hidden from VoiceOver so it isn't read twice, so the
            // field itself must carry the name: a field with an empty placeholder
            // (prompt: Text("")) is otherwise read with no name at all.
            Text(title).font(.subheadline.weight(.semibold)).accessibilityHidden(true)
            content()
                .accessibilityLabel(Text(title))
                .accessibilityHint(Text(hint ?? ""))
            if let hint {
                Text(hint).font(.footnote).foregroundStyle(Color.muted).accessibilityHidden(true)
            }
            if let error {
                Text("Error: \(error)").font(.footnote.weight(.semibold)).foregroundStyle(Color.danger)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A text field with a visible label above it.
struct LabeledTextField: View {
    let title: String
    @Binding var text: String
    var hint: String? = nil
    var error: String? = nil
    var axis: Axis = .horizontal
    var body: some View {
        LabeledField(title: title, hint: hint, error: error) {
            TextField(title, text: $text, prompt: Text(""), axis: axis)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(title)
                .accessibilityHint(hint ?? "")
        }
    }
}

// MARK: - Choice groups

extension View {
    /// A named group of single- or multi-choice chips ("Time of day").
    func choiceGroup(_ name: String) -> some View {
        accessibilityElement(children: .contain).accessibilityLabel(name)
    }
}

/// Adds the iOS 17 toggle trait where available.
struct ToggleTrait: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 17, *) {
            content.accessibilityAddTraits(.isToggle)
        } else {
            content
        }
    }
}
