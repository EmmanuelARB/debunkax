import AudioToolbox
import DebunkaxCore
import SwiftUI
import UIKit

struct ListenView: View {
    let model: ListenViewModel
    let onOpenHistory: () -> Void
    let onOpenSettings: () -> Void
    let onOpenClaim: (Int64) -> Void

    private var state: SessionState { model.state }
    private var listening: Bool { state.listening }

    private var background: Color { listening ? SessionPalette.background : Color(.systemBackground) }
    private var foreground: Color { listening ? SessionPalette.onBackground : Color(.label) }

    var body: some View {
        ZStack(alignment: .top) {
            background.ignoresSafeArea()

            VStack(spacing: 0) {
                VStack(spacing: 12) {
                    if let paused = state.checksPaused {
                        ChecksPausedBanner(paused: paused, listening: listening, onOpenSettings: onOpenSettings)
                    }
                    if let problem = state.speechProblem {
                        SpeechProblemBanner(problem: problem, progress: model.modelProgress, listening: listening)
                    }
                }
                .padding(.top, 12)

                ButtonAboveText {
                    BigListenButton(listening: listening, action: model.toggle)
                    belowButton
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if model.showPermissionRationale {
                    VStack(spacing: 8) {
                        Text(
                            "Le microphone est nécessaire pour transcrire la parole entendue et détecter les "
                                + "affirmations à vérifier. Aucun son n'est jamais enregistré ni conservé."
                        )
                        .font(.callout)
                        .multilineTextAlignment(.center)
                        if model.permissionDenied {
                            Button("Ouvrir les Réglages du téléphone") {
                                if let url = URL(string: UIApplication.openSettingsURLString) {
                                    UIApplication.shared.open(url)
                                }
                            }
                        }
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .foregroundStyle(foreground)
            // Calls dismiss on a tap that nothing under it handled while an Alert is showing. Buttons win over
            // this handler, so stopping never takes two taps.
            .contentShape(Rectangle())
            .onTapGesture {
                if state.alert != nil { model.dismissAlert() }
            }

            if let alert = state.alert {
                AlertOverlay(alert: alert, onDismiss: model.dismissAlert, onSeeMore: onOpenClaim)
                    .id(alert.claimId)
            }
        }
        .animation(.default, value: state.alert?.claimId)
        .navigationTitle("Debunkax")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(listening ? .dark : nil, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(action: onOpenHistory) { Image(systemName: "clock.arrow.circlepath") }
                    .accessibilityLabel("Historique")
                Button(action: onOpenSettings) { Image(systemName: "gearshape") }
                    .accessibilityLabel("Réglages")
            }
        }
        .task { await model.followModelDownload() }
        .onChange(of: state.alert?.claimId) { _, newValue in
            dingIfNew(newValue)
        }
    }

    private var belowButton: some View {
        VStack(spacing: 0) {
            if listening && !state.liveText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(speakerLabel(state.liveSpeaker))
                    .font(.footnote.weight(.medium))
                    .opacity(state.liveSpeaker != nil ? 0.9 : 0.5)
                Text(state.liveText)
                    .font(.caption)
                    .opacity(0.6)
                    .multilineTextAlignment(.center)
                Spacer().frame(height: 8)
            }

            Text(counterText).font(.footnote.weight(.medium))
            if let startedAt = state.startedAt {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(sessionTimeAndCost(startedAt: startedAt, now: context.date, spent: state.spent))
                        .font(.footnote.weight(.medium))
                        .opacity(0.6)
                }
            }
        }
    }

    private var counterText: String {
        if let limit = state.checkLimit {
            "Vérifications : \(state.checksUsed) / \(limit)"
        } else {
            "Vérifications : \(state.checksUsed)"
        }
    }

    /// Plays the phone's alert sound once per new Alert, following its volume and silent mode.
    /// The "already dinged" id lives in the view model, so leaving and re-entering the screen does not repeat it.
    private func dingIfNew(_ alertId: Int64?) {
        guard let alertId, alertId != model.lastDingedAlertId else { return }
        model.lastDingedAlertId = alertId
        AudioServicesPlaySystemSound(1007)
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}

/// Elapsed time, then what the session's Checks have cost so far.
private func sessionTimeAndCost(startedAt: Date, now: Date, spent: Spend?) -> String {
    let seconds = max(0, Int(now.timeIntervalSince(startedAt)))
    let time =
        seconds >= 3600
        ? String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String(format: "%d:%02d", seconds / 60, seconds % 60)
    guard let spent else { return time }
    let amount = String(format: "%.2f $", locale: Locale(identifier: "fr_FR"), spent.usd)
    return "\(time) · \(spent.estimated ? "≈ " : "")\(amount)"
}

// MARK: - Pieces

/// Puts the button's centre at 60 % of the height, with `below` under it. The button only moves up when `below`
/// would otherwise run off the bottom, so it stays put as the live transcript grows.
private struct ButtonAboveText: Layout {
    private static let buttonCenter: CGFloat = 0.6
    private static let gap: CGFloat = 24

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions(by: CGSize(width: 320, height: 480))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let button = subviews[0].sizeThatFits(.unspecified)
        let belowProposal = ProposedViewSize(width: bounds.width, height: nil)
        let below = subviews[1].sizeThatFits(belowProposal)
        var top = bounds.height * Self.buttonCenter - button.height / 2
        top = min(top, bounds.height - below.height - Self.gap - button.height)
        top = max(top, 0)
        subviews[0].place(
            at: CGPoint(x: bounds.midX, y: bounds.minY + top), anchor: .top, proposal: .unspecified)
        subviews[1].place(
            at: CGPoint(x: bounds.midX, y: bounds.minY + top + button.height + Self.gap), anchor: .top,
            proposal: belowProposal)
    }
}

private struct BigListenButton: View {
    let listening: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: listening ? "stop.fill" : "mic.fill")
                    .font(.system(size: 40))
                Text(listening ? "Arrêter" : "Démarrer l'écoute")
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(listening ? SessionPalette.onErrorContainer : Palette.onPrimary)
            .frame(width: 180, height: 180)
            .background(Circle().fill(listening ? SessionPalette.errorContainer : Palette.primary))
        }
        .buttonStyle(.plain)
    }
}

private struct ChecksPausedBanner: View {
    let paused: ChecksPaused
    let listening: Bool
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            Text(checksPausedMessage(paused)).font(.callout).multilineTextAlignment(.center)
            if case .noProvider = paused {
                Button("Réglages", action: onOpenSettings)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(listening ? SessionPalette.surfaceVariant : Palette.surfaceVariant, in: RoundedRectangle(cornerRadius: 12))
    }
}

private func checksPausedMessage(_ paused: ChecksPaused) -> String {
    switch paused {
    case .noProvider:
        return "Aucun fournisseur configuré."
    case .failed(let failure):
        switch failure {
        case .offline:
            return "Pas de connexion : les affirmations sont gardées et seront vérifiées plus tard."
        case .invalidKey:
            return "La clé du fournisseur est invalide : les affirmations sont gardées et seront vérifiées plus tard."
        case .noCredit:
            return "Crédit épuisé chez le fournisseur : les affirmations sont gardées et seront vérifiées plus tard."
        case .searchDisabled:
            return "La recherche web est désactivée sur ce compte : les affirmations sont gardées et seront vérifiées plus tard."
        case .rateLimited:
            return "Trop de requêtes envoyées au fournisseur : les affirmations sont gardées et seront vérifiées plus tard."
        case .budgetReached:
            return "Limite de vérifications de la session atteinte : les affirmations sont gardées et seront vérifiées plus tard."
        case .unexpected:
            return "Une erreur est survenue avec le fournisseur : les affirmations sont gardées et seront vérifiées plus tard."
        }
    }
}

private struct SpeechProblemBanner: View {
    let problem: SpeechFailure
    let progress: ModelDownloadProgress
    let listening: Bool

    var body: some View {
        VStack(spacing: 8) {
            Text(speechProblemMessage(problem)).font(.callout).multilineTextAlignment(.center)
            if case .modelDownloading = problem, case .downloading(let read, let total) = progress, total > 0 {
                ProgressView(value: Double(read), total: Double(total))
                Text("\(Int(Double(read) / Double(total) * 100)) %").font(.caption)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(listening ? SessionPalette.errorContainer : Palette.errorContainer, in: RoundedRectangle(cornerRadius: 12))
        .foregroundStyle(listening ? SessionPalette.onErrorContainer : Palette.onErrorContainer)
    }
}

private func speechProblemMessage(_ problem: SpeechFailure) -> String {
    switch problem {
    case .modelDownloading: "Téléchargement du modèle de reconnaissance vocale en cours…"
    case .unsupported: "La reconnaissance vocale n'est pas prise en charge sur cet appareil."
    case .engine: "Une erreur est survenue avec la reconnaissance vocale. Réessayez de démarrer l'écoute."
    }
}
