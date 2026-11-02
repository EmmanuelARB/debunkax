import DebunkaxCore
import SwiftUI

func verdictLabel(_ verdict: Verdict) -> String {
    switch verdict {
    case .false: "Faux"
    case .misleading: "Trompeur"
    case .lacksContext: "Manque de contexte"
    case .unverifiable: "Invérifiable"
    case .accurate: "Exact"
    }
}

struct VerdictChip: View {
    let verdict: Verdict

    var body: some View {
        let colors = VerdictColors.pair(for: verdict)
        Text(verdictLabel(verdict))
            .font(.footnote.weight(.medium))
            .foregroundStyle(colors.onContainer)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .background(colors.container, in: Capsule())
    }
}

/// Who said a Claim, as shown to the user: the recognised Speaker, or unknown (ADR 0003).
func speakerLabel(_ speaker: String?) -> String { speaker ?? "Orateur non identifié" }
