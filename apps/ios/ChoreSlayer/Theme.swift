import SwiftUI

// AAA-contrast palette (matches the Android app; verified ≥ 7:1 with white text).
extension Color {
    static let brand = Color(red: 0x0D / 255.0, green: 0x47 / 255.0, blue: 0xA1 / 255.0)
    static let brandPurple = Color(red: 0x45 / 255.0, green: 0x27 / 255.0, blue: 0xA0 / 255.0)
    static let muted = Color(red: 0x54 / 255.0, green: 0x54 / 255.0, blue: 0x54 / 255.0)
    static let danger = Color(red: 0xB0 / 255.0, green: 0x00 / 255.0, blue: 0x20 / 255.0)
    static let goodGreen = Color(red: 0x1B / 255.0, green: 0x5E / 255.0, blue: 0x20 / 255.0)
    static let errorBg = Color(red: 1.0, green: 0xE3 / 255.0, blue: 0xE3 / 255.0)
    static let errorInk = Color(red: 0x6E / 255.0, green: 0x14 / 255.0, blue: 0x14 / 255.0) // 9.8:1 on errorBg
    static let taskDone = Color(red: 0xD7 / 255.0, green: 0xF5 / 255.0, blue: 0xDD / 255.0)
    static let taskPartial = Color(red: 0xFF / 255.0, green: 0xF3 / 255.0, blue: 0xCD / 255.0)

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
