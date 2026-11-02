import DebunkaxCore
import SwiftUI

struct TrustedSourcesView: View {
    var body: some View {
        List {
            Text(
                "Cette liste est fixe et identique pour tous les utilisateurs : la recherche web de chaque "
                    + "vérification est strictement limitée à ces sources, sans aucun recours à une recherche plus large."
            )
            .font(.callout)
            .listRowSeparator(.hidden)

            ForEach(TrustedSources.all, id: \.domain) { source in
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.name).font(.headline)
                    Text(source.domain).font(.caption)
                    Text(source.reason).font(.callout)
                }
                .padding(.vertical, 4)
            }
        }
        .listStyle(.plain)
        .navigationTitle("Sources de confiance")
        .navigationBarTitleDisplayMode(.inline)
    }
}
