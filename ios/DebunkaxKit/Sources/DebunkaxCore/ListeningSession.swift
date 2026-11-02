import Foundation
import Observation

/// `speaker` is nil when unknown.
public struct ActiveAlert: Hashable, Sendable {
    public var claimId: Int64
    public var claimText: String
    public var result: CheckResult
    public var speaker: String?

    public init(claimId: Int64, claimText: String, result: CheckResult, speaker: String? = nil) {
        self.claimId = claimId
        self.claimText = claimText
        self.result = result
        self.speaker = speaker
    }
}

public enum ChecksPaused: Hashable, Sendable {
    case noProvider
    case failed(CheckFailure)
}

public struct SessionState: Hashable, Sendable {
    public var listening: Bool
    public var startedAt: Date?
    public var spent: Spend?
    public var liveText: String
    /// Who is speaking in the last few seconds, for the live transcript; nil when unknown or in doubt.
    public var liveSpeaker: String?
    public var alert: ActiveAlert?
    public var checksUsed: Int
    public var checkLimit: Int?
    public var checksPaused: ChecksPaused?
    public var speechProblem: SpeechFailure?

    public init(
        listening: Bool = false, startedAt: Date? = nil, spent: Spend? = nil, liveText: String = "",
        liveSpeaker: String? = nil, alert: ActiveAlert? = nil, checksUsed: Int = 0, checkLimit: Int? = nil,
        checksPaused: ChecksPaused? = nil, speechProblem: SpeechFailure? = nil
    ) {
        self.listening = listening
        self.startedAt = startedAt
        self.spent = spent
        self.liveText = liveText
        self.liveSpeaker = liveSpeaker
        self.alert = alert
        self.checksUsed = checksUsed
        self.checkLimit = checkLimit
        self.checksPaused = checksPaused
        self.speechProblem = speechProblem
    }
}

/// Waits for `seconds`; throws `CancellationError` when the calling task is cancelled. Injectable so tests need no real delay.
public typealias Sleeper = @Sendable (TimeInterval) async throws -> Void

public enum Sleepers {
    public static let real: Sleeper = { seconds in
        try await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
    }
}

/// A counting semaphore for async code (the Kotlin `Semaphore(2)`): waiters are served first come, first served.
actor ConcurrencyLimiter {
    private var available: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ permits: Int) {
        available = permits
    }

    func withPermit<T: Sendable>(_ operation: @Sendable () async throws -> T) async rethrows -> T {
        await acquire()
        defer { release() }
        return try await operation()
    }

    private func acquire() async {
        if available > 0 {
            available -= 1
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiters.append(continuation)
        }
    }

    private func release() {
        if waiters.isEmpty {
            available += 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}

/// One Listening Session: speech, transcript window, claim filter, Check, Alert and History.
/// The transcript lives only in memory and is dropped at `stop()`.
@MainActor @Observable
public final class ListeningSession {
    public private(set) var state: SessionState

    private let speech: any SpeechEngine
    private let detector: any ClaimDetector
    private let history: any HistoryRepository
    private let checkClient: (any CheckClient)?
    private let now: @Sendable () -> Date
    private let modelRetryDelay: TimeInterval
    private let sleep: Sleeper
    private let speakers: (any SpeakerRecognition)?

    private let transcript = RollingTranscript()
    private let repeats = RepeatedClaimMatcher()
    private let budget: CheckBudget
    private let concurrentChecks = ConcurrencyLimiter(2)

    @ObservationIgnored private var sessionId: Int64?
    @ObservationIgnored private var listenTask: Task<Void, Never>?

    /// Two recognition windows: enough to follow a change of Speaker within a few seconds.
    private static let liveSpeakerWindow: TimeInterval = 5

    public init(
        speech: any SpeechEngine,
        detector: any ClaimDetector,
        history: any HistoryRepository,
        checkClient: (any CheckClient)?,
        checkLimit: Int?,
        now: @escaping @Sendable () -> Date = { Date() },
        modelRetryDelay: TimeInterval = 5,
        sleep: @escaping Sleeper = Sleepers.real,
        speakers: (any SpeakerRecognition)? = nil
    ) {
        self.speech = speech
        self.detector = detector
        self.history = history
        self.checkClient = checkClient
        self.now = now
        self.modelRetryDelay = modelRetryDelay
        self.sleep = sleep
        self.speakers = speakers
        self.budget = CheckBudget(limit: checkLimit)
        self.state = SessionState(checkLimit: checkLimit, checksPaused: checkClient == nil ? .noProvider : nil)
    }

    public func start() {
        if listenTask != nil { return }
        state.listening = true
        state.startedAt = now()
        state.speechProblem = nil
        speakers?.start()
        listenTask = Task { await self.run() }
    }

    public func stop() async {
        guard let task = listenTask else { return }
        listenTask = nil
        task.cancel()
        speakers?.stop()
        transcript.clear()
        repeats.clear()
        resetLiveState()
        // Taken before suspending, so the cancelled listen task can't end the same History row twice.
        let id = sessionId
        sessionId = nil
        if let id { try? await history.endSession(sessionId: id, at: now()) }
    }

    public func dismissAlert() {
        state.alert = nil
    }

    private func resetLiveState() {
        state.listening = false
        state.startedAt = nil
        state.liveText = ""
        state.liveSpeaker = nil
        state.alert = nil
    }

    private func run() async {
        let id: Int64
        do {
            id = try await history.startSession(at: now())
        } catch {
            // No History row to write to: don't pretend to listen.
            speakers?.stop()
            state.listening = false
            state.startedAt = nil
            state.speechProblem = .engine("Impossible d'ouvrir l'historique")
            listenTask = nil
            return
        }
        if Task.isCancelled {
            // Stopped while the row was being created.
            try? await history.endSession(sessionId: id, at: now())
            return
        }
        sessionId = id
        await listen(sessionId: id)
    }

    private func listen(sessionId: Int64) async {
        while true {
            do {
                for try await segment in speech.transcribe() {
                    if Task.isCancelled { return }
                    handle(segment, sessionId: sessionId)
                }
                if Task.isCancelled { return }
                break  // The audio source ended.
            } catch let failure as SpeechFailure {
                if Task.isCancelled { return }
                state.speechProblem = failure
                if case .modelDownloading = failure {
                    do {
                        try await sleep(modelRetryDelay)
                    } catch {
                        return
                    }
                    continue
                }
                await endOnAudioEnd(sessionId: sessionId)
                return
            } catch {
                if Task.isCancelled { return }
                state.speechProblem = .engine(error.localizedDescription)
                await endOnAudioEnd(sessionId: sessionId)
                return
            }
        }
        await endOnAudioEnd(sessionId: sessionId)
    }

    private func handle(_ segment: TranscriptSegment, sessionId: Int64) {
        let at = now()
        let liveSpeaker = speakers?.speakerBetween(from: at.addingTimeInterval(-Self.liveSpeakerWindow), to: at)
        state.liveText = segment.text
        state.liveSpeaker = liveSpeaker
        state.speechProblem = nil
        if !segment.isFinal { return }
        let sentences = Sentences.split(segment.text)
        for (sentence, span) in zip(sentences, SentenceTiming.spans(sentences, endAt: segment.at)) {
            let context = transcript.textAt(segment.at)
            transcript.append(sentence, at: segment.at)
            if detector.isClaim(sentence) {
                let speaker = speakers?.speakerBetween(from: span.start, to: span.end)
                // Only a recognised claimant, so the addressee can't be the claimant themselves.
                var addressee: String?
                if let speaker, SecondPerson.isIn(sentence) {
                    addressee = speakers?.addresseeBefore(span.start, claimant: speaker)
                }
                let claim = HeardClaim(
                    text: sentence, heardAt: segment.at, surroundingTranscript: context,
                    speaker: speaker, addressedSpeaker: addressee)
                Task { await self.handleClaim(sessionId: sessionId, claim: claim) }
            }
        }
    }

    /// Same cleanup as `stop()` for when the audio ends on its own: the listen task finishes instead
    /// of being cancelled, so it must not cancel itself, it only clears state and closes the History
    /// row. Guarded against a concurrent `stop()`.
    private func endOnAudioEnd(sessionId: Int64) async {
        if Task.isCancelled || self.sessionId != sessionId { return }  // already stopped
        speakers?.stop()
        transcript.clear()
        repeats.clear()
        resetLiveState()
        self.sessionId = nil
        try? await history.endSession(sessionId: sessionId, at: now())
    }

    private func handleClaim(sessionId: Int64, claim: HeardClaim) async {
        if let known = repeats.find(claim.text, speaker: claim.speaker) {
            if let id = try? await history.addClaim(
                sessionId: sessionId, text: claim.text, heardAt: claim.heardAt, result: known, speaker: claim.speaker)
            {
                maybeAlert(claimId: id, claim: claim, result: known)
            }
            return
        }
        guard let client = checkClient else {
            _ = try? await history.addClaim(
                sessionId: sessionId, text: claim.text, heardAt: claim.heardAt, result: nil, speaker: claim.speaker)
            return
        }
        if !budget.tryConsume() {
            _ = try? await history.addClaim(
                sessionId: sessionId, text: claim.text, heardAt: claim.heardAt, result: nil, speaker: claim.speaker)
            state.checksPaused = .failed(.budgetReached)
            return
        }
        state.checksUsed = budget.used
        defer { state.spent = client.spent }
        do {
            let outcome = try await concurrentChecks.withPermit { try await client.check(claim) }
            switch outcome {
            case .notAClaim:
                break
            case .checked(let result):
                repeats.remember(claim.text, result: result, speaker: claim.speaker)
                let id = try? await history.addClaim(
                    sessionId: sessionId, text: claim.text, heardAt: claim.heardAt, result: result, speaker: claim.speaker)
                if case .failed = state.checksPaused { state.checksPaused = nil }
                if let id { maybeAlert(claimId: id, claim: claim, result: result) }
            }
        } catch is CancellationError {
            return
        } catch let failure as CheckFailure {
            await saveAsPending(sessionId: sessionId, claim: claim, failure: failure)
        } catch {
            // Kotlin let unexpected exceptions crash the scope; a Pending Claim and a pause are kinder.
            await saveAsPending(sessionId: sessionId, claim: claim, failure: .unexpected(error.localizedDescription))
        }
    }

    private func saveAsPending(sessionId: Int64, claim: HeardClaim, failure: CheckFailure) async {
        _ = try? await history.addClaim(
            sessionId: sessionId, text: claim.text, heardAt: claim.heardAt, result: nil, speaker: claim.speaker)
        state.checksPaused = .failed(failure)
    }

    private func maybeAlert(claimId: Int64, claim: HeardClaim, result: CheckResult) {
        // A Check that finishes after the session ended is still saved, but must not raise an Alert on the idle screen.
        if !state.listening { return }
        if !AlertPolicy.shouldAlert(verdict: result.verdict, heardAt: claim.heardAt, readyAt: now()) { return }
        // Stays until dismissed or replaced by a newer Alert, so there's time to read it.
        // The quote is the sentence as heard; the rewrite only feeds the Check and the detail screen.
        state.alert = ActiveAlert(claimId: claimId, claimText: claim.text, result: result, speaker: claim.speaker)
    }
}

/// `failure` is why re-checking stopped or last failed; nil when every attempt succeeded.
public struct PendingCheckReport: Hashable, Sendable {
    public var checked: Int
    public var stillPending: Int
    public var failure: CheckFailure?

    public init(checked: Int, stillPending: Int, failure: CheckFailure?) {
        self.checked = checked
        self.stillPending = stillPending
        self.failure = failure
    }
}

/// Checks Pending Claims once possible. Their surrounding transcript is gone, so they are checked on their own text.
public final class PendingClaimChecker: Sendable {
    private let history: any HistoryRepository

    public init(history: any HistoryRepository) {
        self.history = history
    }

    public func checkAll(_ client: any CheckClient) async throws -> PendingCheckReport {
        var checked = 0
        var failure: CheckFailure?
        loop: for pending in try await history.pendingClaims() {
            do {
                let claim = HeardClaim(
                    text: pending.text, heardAt: pending.heardAt, surroundingTranscript: "", speaker: pending.speaker)
                switch try await client.check(claim) {
                case .notAClaim: try await history.deleteClaim(claimId: pending.id)
                case .checked(let result): try await history.setResult(claimId: pending.id, result: result)
                }
                checked += 1
            } catch let e as CheckFailure {
                failure = e
                switch e {
                case .offline, .invalidKey, .noCredit, .searchDisabled: break loop
                default: break
                }
            }
        }
        return PendingCheckReport(checked: checked, stillPending: try await history.pendingClaims().count, failure: failure)
    }
}

/// Loads a Claim's Explanation, producing and saving it once so reopening is free.
public final class ExplanationLoader: Sendable {
    private let history: any HistoryRepository

    public init(history: any HistoryRepository) {
        self.history = history
    }

    public func load(
        claimId: Int64, claimText: String, result: CheckResult, existing: Explanation?, client: any CheckClient
    ) async throws -> Explanation {
        if let existing { return existing }
        let explanation = try await client.explain(claimText: claimText, result: result)
        try await history.setExplanation(claimId: claimId, explanation: explanation)
        return explanation
    }
}
