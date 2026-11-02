import Foundation
import Synchronization
import XCTest
@testable import DebunkaxCore

// Test doubles and helpers shared by the session, speech and check tests.
//
// Virtual time: the Kotlin tests ran under `runTest`. Here the session takes an injected `now` clock
// (`TestClock`, moved by hand or by a fake Check that "takes" time) and an injected `Sleeper`, so no test
// sleeps for real. Where a test has to wait for a Task to make progress it polls with `waitUntil`.

/// A manually moved clock: stands in for the test scheduler's virtual time.
final class TestClock: Sendable {
    private let current: Mutex<Date>

    init(start: Date = testStart) {
        current = Mutex(start)
    }

    func now() -> Date { current.withLock { $0 } }

    func advance(_ seconds: TimeInterval) {
        current.withLock { $0 = $0.addingTimeInterval(seconds) }
    }

    /// For `ListeningSession(now:)`.
    var reader: @Sendable () -> Date { { [self] in now() } }
}

func makeResult(_ verdict: Verdict, context: String = "Contexte factuel.") -> CheckResult {
    CheckResult(verdict: verdict, context: context, sources: [])
}

/// Polls `condition` on the main actor until it holds, failing the test after `timeout` seconds.
@MainActor
func waitUntil(
    _ what: String = "condition", timeout: TimeInterval = 5, file: StaticString = #filePath, line: UInt = #line,
    _ condition: @MainActor () -> Bool
) async {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
        if Date() > deadline {
            XCTFail("Timed out waiting for \(what)", file: file, line: line)
            return
        }
        try? await Task.sleep(nanoseconds: 1_000_000)
    }
}

/// Gives the session's tasks time to do something unwanted before a negative assertion.
func settle() async {
    try? await Task.sleep(nanoseconds: 40_000_000)
}

struct ClosureDetector: ClaimDetector {
    let matches: @Sendable (String) -> Bool
    init(_ matches: @escaping @Sendable (String) -> Bool) { self.matches = matches }
    func isClaim(_ sentence: String) -> Bool { matches(sentence) }
}

/// Opens once; waiters before and after the opening all get through.
final class AsyncGate: Sendable {
    private struct State {
        var isOpen = false
        var waiters: [CheckedContinuation<Void, Never>] = []
    }

    private let state = Mutex(State())

    func wait() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let resumeNow = state.withLock { s -> Bool in
                if s.isOpen { return true }
                s.waiters.append(continuation)
                return false
            }
            if resumeNow { continuation.resume() }
        }
    }

    func open() {
        let waiters = state.withLock { s -> [CheckedContinuation<Void, Never>] in
            s.isOpen = true
            let w = s.waiters
            s.waiters = []
            return w
        }
        for w in waiters { w.resume() }
    }
}

/// A `SpeechEngine` fed manually, one stream per `transcribe()` call (so retries get a fresh stream).
final class FakeSpeechEngine: SpeechEngine {
    private let continuations = Mutex<[AsyncThrowingStream<TranscriptSegment, Error>.Continuation]>([])

    var attemptCount: Int { continuations.withLock { $0.count } }

    func transcribe() -> AsyncThrowingStream<TranscriptSegment, Error> {
        let (stream, continuation) = AsyncThrowingStream<TranscriptSegment, Error>.makeStream()
        continuations.withLock { $0.append(continuation) }
        return stream
    }

    func emitFinal(_ text: String, at: Date) {
        current().yield(TranscriptSegment(text: text, isFinal: true, at: at))
    }

    func emitPartial(_ text: String, at: Date) {
        current().yield(TranscriptSegment(text: text, isFinal: false, at: at))
    }

    /// The audio source ended normally (e.g. a consent token was revoked).
    func end() {
        current().finish()
    }

    func fail(_ failure: SpeechFailure) {
        current().finish(throwing: failure)
    }

    private func current() -> AsyncThrowingStream<TranscriptSegment, Error>.Continuation {
        guard let last = continuations.withLock({ $0.last }) else {
            fatalError("transcribe() was not called yet - wait for attemptCount first")
        }
        return last
    }
}

/// A scriptable `CheckClient`: enqueue one response per expected call, in order.
final class FakeCheckClient: CheckClient {
    typealias Step = @Sendable () async throws -> CheckOutcome

    private struct State {
        var scripted: [Step] = []
        var checkedClaims: [HeardClaim] = []
        var explainCalls: [(claimText: String, result: CheckResult)] = []
        var explanationToReturn = Explanation(text: "Explication.", sources: [])
        var spent: Spend?
    }

    private let state = Mutex(State())

    var checkedClaims: [HeardClaim] { state.withLock { $0.checkedClaims } }
    var explainCalls: [(claimText: String, result: CheckResult)] { state.withLock { $0.explainCalls } }

    var explanationToReturn: Explanation {
        get { state.withLock { $0.explanationToReturn } }
        set { state.withLock { $0.explanationToReturn = newValue } }
    }

    var spent: Spend? {
        get { state.withLock { $0.spent } }
        set { state.withLock { $0.spent = newValue } }
    }

    func enqueueChecked(_ result: CheckResult) { enqueue { .checked(result) } }
    func enqueueNotAClaim() { enqueue { .notAClaim } }
    func enqueueFailure(_ failure: CheckFailure) { enqueue { throw failure } }

    /// A Check that stays in flight until `gate` opens, and then takes `seconds` of the test clock.
    func enqueueDelayedChecked(gate: AsyncGate, clock: TestClock, seconds: TimeInterval, _ result: CheckResult) {
        enqueue {
            await gate.wait()
            clock.advance(seconds)
            return .checked(result)
        }
    }

    private func enqueue(_ step: @escaping Step) {
        state.withLock { $0.scripted.append(step) }
    }

    func check(_ claim: HeardClaim) async throws -> CheckOutcome {
        let next = state.withLock { s -> Step? in
            s.checkedClaims.append(claim)
            return s.scripted.isEmpty ? nil : s.scripted.removeFirst()
        }
        guard let next else { throw CheckFailure.unexpected("FakeCheckClient: no scripted response left for \(claim.text)") }
        return try await next()
    }

    func explain(claimText: String, result: CheckResult) async throws -> Explanation {
        state.withLock {
            $0.explainCalls.append((claimText: claimText, result: result))
            return $0.explanationToReturn
        }
    }
}

/// An in-memory `HistoryRepository`. `sessions` and `claim` (the stream queries) aren't needed by these tests.
final class FakeHistoryRepository: HistoryRepository {
    struct SessionRecord: Sendable {
        let id: Int64
        let startedAt: Date
        var endedAt: Date?
    }

    private struct State {
        var sessionRecords: [SessionRecord] = []
        var claims: [Int64: ClaimRecord] = [:]
        var setExplanationCalls: [(claimId: Int64, explanation: Explanation)] = []
        var nextSessionId: Int64 = 1
        var nextClaimId: Int64 = 1
    }

    private let state = Mutex(State())

    var sessionRecords: [SessionRecord] { state.withLock { $0.sessionRecords } }
    /// In insertion (id) order.
    var claims: [ClaimRecord] { state.withLock { $0.claims.values.sorted { $0.id < $1.id } } }
    func claimRecord(_ id: Int64) -> ClaimRecord? { state.withLock { $0.claims[id] } }
    var setExplanationCalls: [(claimId: Int64, explanation: Explanation)] { state.withLock { $0.setExplanationCalls } }

    func startSession(at: Date) async throws -> Int64 {
        state.withLock { s in
            let id = s.nextSessionId
            s.nextSessionId += 1
            s.sessionRecords.append(SessionRecord(id: id, startedAt: at, endedAt: nil))
            return id
        }
    }

    func closeUnfinishedSessions(before: Date) async throws {}

    func endSession(sessionId: Int64, at: Date) async throws {
        state.withLock { s in
            if let index = s.sessionRecords.firstIndex(where: { $0.id == sessionId }) {
                s.sessionRecords[index].endedAt = at
            }
        }
    }

    func addClaim(sessionId: Int64, text: String, heardAt: Date, result: CheckResult?, speaker: String?) async throws -> Int64 {
        state.withLock { s in
            let id = s.nextClaimId
            s.nextClaimId += 1
            s.claims[id] = ClaimRecord(
                id: id, sessionId: sessionId, text: text, heardAt: heardAt, result: result, explanation: nil, speaker: speaker)
            return id
        }
    }

    func setResult(claimId: Int64, result: CheckResult) async throws {
        state.withLock { $0.claims[claimId]?.result = result }
    }

    func setExplanation(claimId: Int64, explanation: Explanation) async throws {
        state.withLock { s in
            s.setExplanationCalls.append((claimId: claimId, explanation: explanation))
            s.claims[claimId]?.explanation = explanation
        }
    }

    func deleteClaim(claimId: Int64) async throws {
        state.withLock { _ = $0.claims.removeValue(forKey: claimId) }
    }

    func pendingClaims() async throws -> [ClaimRecord] {
        state.withLock { $0.claims.values.filter { $0.result == nil }.sorted { $0.id < $1.id } }
    }

    func sessions(filter: HistoryFilter) -> AsyncStream<[SessionHistory]> {
        fatalError("not needed by these tests")
    }

    func claim(id: Int64) -> AsyncStream<ClaimRecord?> {
        fatalError("not needed by these tests")
    }

    func clearAll() async throws {
        state.withLock { s in
            s.sessionRecords.removeAll()
            s.claims.removeAll()
        }
    }
}

/// Names `speaker` for every sentence once started.
final class FakeSpeakerRecognition: SpeakerRecognition {
    private struct State {
        var speaker: String?
        var addressee: String?
        var started = false
        var addresseeCalls: [(before: Date, claimant: String)] = []
    }

    private let state: Mutex<State>

    init(speaker: String?, addressee: String? = nil) {
        state = Mutex(State(speaker: speaker, addressee: addressee))
    }

    var speaker: String? {
        get { state.withLock { $0.speaker } }
        set { state.withLock { $0.speaker = newValue } }
    }

    var started: Bool { state.withLock { $0.started } }

    /// Every addressee lookup: the instant searched back from, and the claimant.
    var addresseeCalls: [(before: Date, claimant: String)] { state.withLock { $0.addresseeCalls } }

    func addresseeBefore(_ before: Date, claimant: String) -> String? {
        state.withLock {
            $0.addresseeCalls.append((before: before, claimant: claimant))
            return $0.addressee
        }
    }

    func start() { state.withLock { $0.started = true } }
    func stop() { state.withLock { $0.started = false } }

    func speakerBetween(from: Date, to: Date) -> String? {
        state.withLock { $0.started ? $0.speaker : nil }
    }
}

/// Answers for fixed time windows, latest first; nil outside every window.
final class ScriptedSpeakerRecognition: SpeakerRecognition {
    struct Window: Sendable {
        let from: Date
        let to: Date
        let speaker: String?
    }

    private let windows: [Window]

    init(_ windows: [Window]) { self.windows = windows }

    func start() {}
    func stop() {}

    func speakerBetween(from: Date, to: Date) -> String? {
        windows.last { $0.from < to && $0.to > from }?.speaker
    }
}
