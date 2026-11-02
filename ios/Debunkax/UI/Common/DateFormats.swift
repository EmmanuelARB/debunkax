import Foundation

private func formatter(_ pattern: String) -> DateFormatter {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "fr_FR")
    formatter.dateFormat = pattern
    return formatter
}

// Read from the main actor only, after being built once.
nonisolated(unsafe) private let timeFormat = formatter("HH:mm")
nonisolated(unsafe) private let sessionDayFormat = formatter("EEE d MMM")
nonisolated(unsafe) private let fullDateTimeFormat = formatter("d MMMM yyyy, HH:mm")

func formatTime(_ date: Date) -> String { timeFormat.string(from: date) }

func formatFullDateTime(_ date: Date) -> String { fullDateTimeFormat.string(from: date) }

/// e.g. "mar. 23 sept., 21:02 – 22:47", or "… – en cours" while the session hasn't ended.
func formatSessionHeader(startedAt: Date, endedAt: Date?) -> String {
    let day = sessionDayFormat.string(from: startedAt)
    let end = endedAt.map(formatTime) ?? "en cours"
    return "\(day), \(formatTime(startedAt)) – \(end)"
}
