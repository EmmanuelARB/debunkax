import DebunkaxCore
import SwiftUI

/// The Alert card, at the top of the Écoute screen. A newer Alert replaces it (the caller keys it by claim id);
/// swiping it away or tapping elsewhere dismisses it. No scrim: it would swallow the first tap on "Arrêter".
struct AlertOverlay: View {
    let alert: ActiveAlert
    let onDismiss: () -> Void
    let onSeeMore: (Int64) -> Void

    @State private var offset: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VerdictChip(verdict: alert.result.verdict)
            Spacer().frame(height: 8)
            Text(speakerLabel(alert.speaker)).font(.headline)
            Text("« \(alert.claimText) »").font(.body)
            Spacer().frame(height: 4)
            Text(alert.result.context).font(.subheadline)
            Spacer().frame(height: 12)
            Button("En savoir plus") { onSeeMore(alert.claimId) }
            Spacer().frame(height: 4)
            AiDisclaimer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .shadow(radius: 4)
        .padding(16)
        .offset(x: offset)
        .opacity(1 - min(abs(offset) / 300, 0.6))
        // A tap on the card itself must not reach the "tap elsewhere dismisses" handler of the screen.
        .onTapGesture {}
        .gesture(
            DragGesture(minimumDistance: 12)
                .onChanged { offset = $0.translation.width }
                .onEnded { value in
                    if abs(value.translation.width) > 80 {
                        onDismiss()
                    } else {
                        withAnimation { offset = 0 }
                    }
                }
        )
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}
