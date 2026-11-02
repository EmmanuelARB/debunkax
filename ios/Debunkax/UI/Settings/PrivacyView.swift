import SwiftUI

struct PrivacyView: View {
    var body: some View {
        ScrollView {
            Text(
                "Aucun son ni aucune transcription n'est jamais enregistré : l'audio est traité en direct puis "
                    + "immédiatement écarté. Seules les affirmations détectées et leurs verdicts sont conservés "
                    + "sur le téléphone, dans l'Historique.\n\n"
                    + "L'orateur est reconnu à sa voix directement sur le téléphone, en la comparant aux voix "
                    + "de 62 personnalités publiques (responsables politiques et journalistes), enregistrées à partir "
                    + "d'interventions publiques. "
                    + "Dans le doute, il reste « non identifié ».\n\n"
                    + "Pour chaque vérification, seuls l'affirmation, son orateur s'il est reconnu et environ les "
                    + "30 dernières secondes du contexte entendu sont envoyés à OpenRouter, avec votre propre clé. "
                    + "La clé est conservée dans le trousseau du téléphone, sur cet appareil uniquement.\n\n"
                    + "La reconnaissance vocale se fait sur le téléphone. Son modèle est téléchargé une seule fois, "
                    + "par Apple ou depuis la page publique de sherpa-onnx sur GitHub : ce téléchargement n'envoie "
                    + "aucune donnée vous concernant.\n\n"
                    + "Debunkax n'a pas de serveur : rien ne transite par un serveur Debunkax.\n\n"
                    + "Vous pouvez effacer l'historique à tout moment depuis l'écran Historique."
            )
            .font(.callout)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .navigationTitle("Confidentialité")
        .navigationBarTitleDisplayMode(.inline)
    }
}
