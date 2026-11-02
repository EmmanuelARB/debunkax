import DebunkaxCore
import SwiftUI

struct ClaimDetailView: View {
    let model: ClaimDetailViewModel

    var body: some View {
        Group {
            if let claim = model.claim {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(speakerLabel(claim.speaker)).font(.headline)
                        Text("« \(claim.text) »").font(.title2)
                        if claim.checkedText != claim.text {
                            Text("Vérifiée sous la forme : « \(claim.checkedText) »")
                                .font(.callout)
                                .padding(.top, 8)
                        }
                        Text(formatFullDateTime(claim.heardAt)).font(.callout).padding(.top, 8)
                        Spacer().frame(height: 16)

                        if let result = claim.result {
                            resultSection(result)
                        } else {
                            Text("Cette affirmation n'a pas encore été vérifiée. Elle le sera dès que possible.")
                                .font(.callout)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Détail de l'affirmation")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.startObserving() }
        .onDisappear { model.stopObserving() }
    }

    @ViewBuilder
    private func resultSection(_ result: CheckResult) -> some View {
        VerdictChip(verdict: result.verdict)
        Text(result.context).font(.body).padding(.top, 8)
        Spacer().frame(height: 16)

        if !result.sources.isEmpty {
            Text("Sources").font(.subheadline.weight(.semibold))
            ForEach(result.sources, id: \.self) { SourceLink(source: $0) }
            Spacer().frame(height: 16)
        }

        Text("Explication").font(.subheadline.weight(.semibold))
        Spacer().frame(height: 4)
        switch model.explanationState {
        case .idle:
            EmptyView()
        case .loading:
            ProgressView().padding(.vertical, 8)
        case .error(let message):
            Text(message).font(.callout)
            Button("Réessayer", action: model.retry)
        case .loaded(let explanation):
            Text(explanation.text).font(.callout)
            ForEach(explanation.sources, id: \.self) { SourceLink(source: $0) }
        }

        Spacer().frame(height: 16)
        AiDisclaimer()
    }
}

private struct SourceLink: View {
    let source: Source

    var body: some View {
        // Only http(s) links are opened.
        if let url = URL(string: source.url), ["http", "https"].contains(url.scheme?.lowercased()) {
            Link(destination: url) {
                Text(source.title).font(.callout).underline().multilineTextAlignment(.leading)
            }
            .padding(.vertical, 4)
        } else {
            Text(source.title).font(.callout).padding(.vertical, 4)
        }
    }
}
