import DebunkaxCore
import Observation

enum ExplanationUiState: Equatable {
    case idle
    case loading
    case loaded(Explanation)
    case error(String)
}

@MainActor @Observable
final class ClaimDetailViewModel {
    private let container: AppContainer
    let claimId: Int64

    private(set) var claim: ClaimRecord?
    private(set) var explanationState: ExplanationUiState = .idle

    @ObservationIgnored private var requested = false
    @ObservationIgnored private var observation: Task<Void, Never>?

    init(container: AppContainer, claimId: Int64) {
        self.container = container
        self.claimId = claimId
    }

    func startObserving() {
        guard observation == nil else { return }
        let stream = container.history.claim(claimId)
        observation = Task { [weak self] in
            for await value in stream {
                if Task.isCancelled { return }
                guard let self else { return }
                self.claim = value
                if value != nil { self.loadExplanationIfNeeded() }
            }
        }
    }

    func stopObserving() {
        observation?.cancel()
        observation = nil
    }

    func loadExplanationIfNeeded() {
        guard let current = claim, let result = current.result else { return }
        if let existing = current.explanation {
            explanationState = .loaded(existing)
            return
        }
        if requested { return }
        requested = true
        loadExplanation(text: current.checkedText, result: result)
    }

    func retry() {
        guard let current = claim, let result = current.result else { return }
        loadExplanation(text: current.checkedText, result: result)
    }

    private func loadExplanation(text: String, result: CheckResult) {
        explanationState = .loading
        let id = claimId
        Task {
            guard let client = await container.checkClient() else {
                explanationState = .error("Aucun fournisseur configuré.")
                return
            }
            do {
                let explanation = try await container.explanations.load(
                    claimId: id, claimText: text, result: result, existing: claim?.explanation, client: client)
                explanationState = .loaded(explanation)
            } catch is CancellationError {
                return
            } catch {
                explanationState = .error("Impossible de charger l'explication.")
            }
        }
    }
}
