import DebunkaxCore
import SwiftUI
import UIKit

/// Debug-only "Qui parle ?" screen: live speaker identification through the microphone.
struct SpeakerTestView: View {
    @Bindable var model: SpeakerTestViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(
                "Test de reconnaissance des voix de \(model.speakerCount) responsables politiques. "
                    + "Rien n'est enregistré ni envoyé. Arrêtez l'écoute principale avant."
            )
            .font(.caption)
            if let error = model.error {
                Text(error).foregroundStyle(.red)
            }

            Text(headline(model.current)).font(.system(size: 32, weight: .bold))
            Text(detail(model.current)).font(.callout)
            if let level = model.level {
                Text(
                    level == 0
                        ? "Micro muet : il est peut-être utilisé par l'écoute principale."
                        : "Niveau du micro : \(String(format: "%.4f", locale: Locale(identifier: "fr_FR"), level))"
                )
                .font(.caption)
            }

            Text("Seuil : \(String(format: "%.2f", locale: Locale(identifier: "fr_FR"), model.threshold))")
            Slider(value: $model.threshold, in: 0.4...0.9)

            Button(action: model.toggle) {
                Text(model.running ? "Arrêter" : "Écouter").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            List(Array(model.log.enumerated()), id: \.offset) { entry in
                Text(entry.element).font(.caption)
            }
            .listStyle(.plain)
        }
        .padding(16)
        .navigationTitle("Qui parle ?")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.load() }
        .onDisappear { model.stop() }
        .onChange(of: model.running) { _, running in
            UIApplication.shared.isIdleTimerDisabled = running
        }
    }

    private func headline(_ guess: SpeakerGuess?) -> String {
        switch guess {
        case nil: "—"
        case .silence: "(silence)"
        case .named(let name, _): name
        case .unknown: "Inconnu"
        }
    }

    private func detail(_ guess: SpeakerGuess?) -> String {
        switch guess {
        case .named(_, let value): "score \(formatScore(value))"
        case .unknown(let closest, let value): "plus proche : \(closest) (\(formatScore(value)))"
        default: ""
        }
    }
}
