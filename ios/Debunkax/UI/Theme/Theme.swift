import DebunkaxCore
import SwiftUI
import UIKit

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(UIColor(hex: hex))
    }

    /// A colour that follows the light or dark appearance.
    static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light) })
    }
}

struct VerdictColorPair {
    let container: Color
    let onContainer: Color

    init(light: (UInt32, UInt32), dark: (UInt32, UInt32)) {
        container = .adaptive(light: light.0, dark: dark.0)
        onContainer = .adaptive(light: light.1, dark: dark.1)
    }
}

/// Container / on-container pairs from `ui/theme/Color.kt`, sized for AA contrast.
enum VerdictColors {
    private static let falseColors = VerdictColorPair(light: (0xF9DEDC, 0x601410), dark: (0x601410, 0xF2B8B5))
    private static let misleading = VerdictColorPair(light: (0xFFE0B2, 0x7A4100), dark: (0x5C3600, 0xFFCC80))
    private static let lacksContext = VerdictColorPair(light: (0xFFF3C4, 0x6B5300), dark: (0x4A3B00, 0xFFE082))
    private static let unverifiable = VerdictColorPair(light: (0xE0E0E0, 0x424242), dark: (0x424242, 0xE0E0E0))
    private static let accurate = VerdictColorPair(light: (0xC8E6C9, 0x1B5E20), dark: (0x1B5E20, 0xA5D6A7))

    static func pair(for verdict: Verdict) -> VerdictColorPair {
        switch verdict {
        case .false: falseColors
        case .misleading: misleading
        case .lacksContext: lacksContext
        case .unverifiable: unverifiable
        case .accurate: accurate
        }
    }
}

/// Dark, low-contrast palette used while a Listening Session is on screen, to avoid lighting up the room
/// (`SessionColorScheme` on Android).
enum SessionPalette {
    static let background = Color(hex: 0x0E0E0E)
    static let surfaceVariant = Color(hex: 0x1B1B1B)
    static let onBackground = Color(hex: 0x757575)
    static let onSurfaceVariant = Color(hex: 0x6B6B6B)
    static let primary = Color(hex: 0xB0B0B0)
    static let onPrimary = Color(hex: 0x0E0E0E)
    static let errorContainer = Color(hex: 0x8C1D18)
    static let onErrorContainer = Color(hex: 0xF9DEDC)
}

/// The app's regular colours: Material 3 baseline primary, system backgrounds otherwise.
enum Palette {
    static let primary = Color.adaptive(light: 0x6650A4, dark: 0xD0BCFF)
    static let onPrimary = Color.adaptive(light: 0xFFFFFF, dark: 0x381E72)
    static let errorContainer = Color.adaptive(light: 0xF9DEDC, dark: 0x8C1D18)
    static let onErrorContainer = Color.adaptive(light: 0x410E0B, dark: 0xF9DEDC)
    static let surfaceVariant = Color(.secondarySystemBackground)
}
