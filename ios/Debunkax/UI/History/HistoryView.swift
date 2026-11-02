import DebunkaxCore
import SwiftUI

struct HistoryView: View {
    @Bindable var model: HistoryViewModel
    let onOpenClaim: (Int64) -> Void

    @State private var query = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Rechercher", text: $query)
                    .textInputAutocapitalization(.never)
                    .submitLabel(.search)
            }
            .padding(10)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Verdict.allCases, id: \.self) { verdict in
                        FilterChip(label: verdictLabel(verdict), selected: model.filter.verdicts.contains(verdict)) {
                            model.toggleVerdict(verdict)
                        }
                    }
                }
                .padding(.horizontal, 16)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button("Vérifier les affirmations en attente", action: model.checkPending)
                    Button("Effacer l'historique", action: model.requestClear)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }

            if model.sessions.isEmpty {
                Text("Aucune affirmation vérifiée pour l'instant.")
                    .font(.callout)
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(model.sessions, id: \.sessionId) { session in
                        Section {
                            ForEach(session.claims, id: \.id) { claim in
                                Button { onOpenClaim(claim.id) } label: { ClaimRow(claim: claim) }
                                    .buttonStyle(.plain)
                                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                        Button("Supprimer", role: .destructive) { model.deleteClaim(claim.id) }
                                    }
                            }
                        } header: {
                            Text(formatSessionHeader(startedAt: session.startedAt, endedAt: session.endedAt))
                                .font(.subheadline.weight(.medium))
                                .textCase(nil)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Historique")
        .navigationBarTitleDisplayMode(.inline)
        .snackbar($model.pendingCheckMessage)
        .onAppear { model.startObserving() }
        .onDisappear { model.stopObserving() }
        .onChange(of: query) { _, newValue in model.setQuery(newValue) }
        .alert("Effacer l'historique ?", isPresented: $model.showClearConfirm) {
            Button("Annuler", role: .cancel) { model.cancelClear() }
            Button("Effacer", role: .destructive) { model.confirmClear() }
        } message: {
            Text("Toutes les affirmations enregistrées seront supprimées définitivement.")
        }
    }
}

private struct FilterChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if selected { Image(systemName: "checkmark").font(.caption) }
                Text(label).font(.subheadline)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(selected ? Palette.primary.opacity(0.2) : Color.clear, in: Capsule())
            .overlay(Capsule().stroke(Color(.separator)))
        }
        .buttonStyle(.plain)
    }
}

private struct ClaimRow: View {
    let claim: ClaimRecord

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Text(formatTime(claim.heardAt)).font(.footnote.weight(.medium)).frame(width: 48, alignment: .leading)
            if let result = claim.result {
                VerdictChip(verdict: result.verdict)
            } else {
                Text("Pas encore vérifiée")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Palette.surfaceVariant, in: RoundedRectangle(cornerRadius: 8))
            }
            VStack(alignment: .leading, spacing: 0) {
                if let speaker = claim.speaker { Text(speaker).font(.footnote.weight(.medium)) }
                Text(claim.text).font(.subheadline).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}
