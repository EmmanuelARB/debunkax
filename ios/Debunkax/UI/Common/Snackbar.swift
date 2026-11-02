import SwiftUI

extension View {
    /// Shows `message` in a capsule at the bottom for a few seconds, then clears it (the Android snackbar).
    func snackbar(_ message: Binding<String?>) -> some View {
        overlay(alignment: .bottom) {
            if let text = message.wrappedValue {
                Text(text)
                    .font(.subheadline)
                    .foregroundStyle(Color(.systemBackground))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color(.label).opacity(0.9), in: RoundedRectangle(cornerRadius: 12))
                    .padding(16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: text) {
                        try? await Task.sleep(for: .seconds(4))
                        if message.wrappedValue == text { message.wrappedValue = nil }
                    }
            }
        }
        .animation(.default, value: message.wrappedValue)
    }
}
