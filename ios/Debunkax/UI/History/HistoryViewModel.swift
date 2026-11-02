import DebunkaxCore
import Observation

@MainActor @Observable
final class HistoryViewModel {
    private let container: AppContainer
    private(set) var filter = HistoryFilter()
    private(set) var sessions: [SessionHistory] = []

    var pendingCheckMessage: String?
    var showClearConfirm = false

    @ObservationIgnored private var observation: Task<Void, Never>?

    init(container: AppContainer) {
        self.container = container
    }

    /// Follows the History for the current filter until `stopObserving()` (or a new filter) replaces it.
    func startObserving() {
        observation?.cancel()
        let history = container.history
        let filter = self.filter
        observation = Task { [weak self] in
            for await value in history.sessions(filter: filter) {
                if Task.isCancelled { return }
                self?.sessions = value
            }
        }
    }

    func stopObserving() {
        observation?.cancel()
        observation = nil
    }

    func setQuery(_ query: String) {
        filter.query = query
        startObserving()
    }

    func toggleVerdict(_ verdict: Verdict) {
        if filter.verdicts.contains(verdict) {
            filter.verdicts.remove(verdict)
        } else {
            filter.verdicts.insert(verdict)
        }
        startObserving()
    }

    func checkPending() {
        Task {
            guard let client = await container.checkClient() else {
                pendingCheckMessage = "Aucun fournisseur configuré."
                return
            }
            let report: PendingCheckReport
            do {
                report = try await container.pendingChecker.checkAll(client)
            } catch {
                pendingCheckMessage = "Vérification interrompue : \(error.localizedDescription)"
                return
            }
            let remaining = report.stillPending > 0 ? " \(report.stillPending) encore en attente." : ""
            if let failure = report.failure {
                pendingCheckMessage =
                    "Vérification interrompue : \(failure.message). \(report.checked) vérifiée(s).\(remaining)"
            } else if report.checked == 0 && report.stillPending == 0 {
                pendingCheckMessage = "Aucune affirmation en attente."
            } else {
                pendingCheckMessage = "\(report.checked) affirmation(s) vérifiée(s).\(remaining)"
            }
        }
    }

    func deleteClaim(_ id: Int64) {
        Task { try? await container.history.deleteClaim(claimId: id) }
    }

    func requestClear() { showClearConfirm = true }

    func cancelClear() { showClearConfirm = false }

    func confirmClear() {
        showClearConfirm = false
        Task { try? await container.history.clearAll() }
    }
}
