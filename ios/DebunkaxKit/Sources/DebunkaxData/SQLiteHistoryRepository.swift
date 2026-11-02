import DebunkaxCore
import Foundation
import SQLite3

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

public struct SQLiteError: Error, LocalizedError, Sendable {
    public var code: Int32
    public var message: String
    public var errorDescription: String? { "SQLite (\(code)): \(message)" }
}

private enum Value {
    case int(Int64)
    case text(String)
    case null

    init(_ text: String?) { self = text.map(Value.text) ?? .null }
    init(_ int: Int64?) { self = int.map(Value.int) ?? .null }
}

private struct ClaimRow {
    var id: Int64
    var sessionId: Int64
    var text: String
    var heardAt: Int64
    var verdict: String?
    var context: String?
    var sources: String?
    var explanationText: String?
    var explanationSources: String?
    var checkedText: String?
    var speaker: String?
}

private func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
private func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }


/// Same shape as the Kotlin side: `[{"title":..,"url":..}]`, nil when there is none.
private func sourcesJson(_ sources: [Source]) -> String? {
    if sources.isEmpty { return nil }
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.withoutEscapingSlashes]
    guard let data = try? encoder.encode(sources) else { return nil }
    return String(data: data, encoding: .utf8)
}

private func sources(from json: String?) -> [Source] {
    guard let json, !json.isEmpty, let data = json.data(using: .utf8),
        let decoded = try? JSONDecoder().decode([Source].self, from: data)
    else { return [] }
    return decoded
}

private func domain(_ row: ClaimRow) throws -> ClaimRecord {
    var result: CheckResult?
    if let name = row.verdict {
        guard let verdict = Verdict(rawValue: name) else {
            throw SQLiteError(code: SQLITE_CORRUPT, message: "Verdict inconnu: \(name)")
        }
        result = CheckResult(
            verdict: verdict, context: row.context ?? "", sources: sources(from: row.sources),
            checkedText: row.checkedText)
    }
    let explanation = row.explanationText.map {
        Explanation(text: $0, sources: sources(from: row.explanationSources))
    }
    return ClaimRecord(
        id: row.id, sessionId: row.sessionId, text: row.text, heardAt: date(row.heardAt),
        result: result, explanation: explanation, speaker: row.speaker)
}

/// SQLite-backed `HistoryRepository`. The schema mirrors the Android Room one (`sessions`, `claims`
/// with cascade). Only Claim text, Verdict data and timestamps are stored: never add a transcript
/// or audio column.
public final class SQLiteHistoryRepository: HistoryRepository, @unchecked Sendable {
    static let schemaVersion: Int32 = 3
    private static let claimColumns =
        "id, sessionId, text, heardAt, verdict, context, sources, explanationText, explanationSources, checkedText, speaker"

    private let lock = NSLock()
    private var db: OpaquePointer?

    private let observersLock = NSLock()
    private var observers: [UUID: @Sendable () -> Void] = [:]

    private init(path: String) throws {
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let rc = sqlite3_open_v2(path, &handle, flags, nil)
        guard rc == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "ouverture impossible"
            if let handle { sqlite3_close_v2(handle) }
            throw SQLiteError(code: rc, message: message)
        }
        db = handle
        do {
            try exec("PRAGMA foreign_keys=ON")
            try migrate()
        } catch {
            sqlite3_close_v2(handle)
            db = nil
            throw error
        }
    }

    /// Opens (creating if needed) the database file at `fileURL`.
    public convenience init(fileURL: URL) throws {
        try self.init(path: fileURL.path)
    }

    /// An in-memory database, for tests.
    public static func inMemory() throws -> SQLiteHistoryRepository {
        try SQLiteHistoryRepository(path: ":memory:")
    }

    /// `Application Support/Debunkax/history.sqlite` (the directory is excluded from backups).
    public static func defaultLocation() throws -> URL {
        try DebunkaxStorage.directory().appendingPathComponent("history.sqlite")
    }

    deinit {
        if let db { sqlite3_close_v2(db) }
    }

    // MARK: Schema

    private func migrate() throws {
        var version = Int32(try query("PRAGMA user_version", []) { sqlite3_column_int($0, 0) }.first ?? 0)
        if version > Self.schemaVersion {
            throw SQLiteError(code: SQLITE_MISMATCH, message: "Base de données plus récente que l'app")
        }
        if version == 0 {
            try exec("BEGIN")
            do {
                try exec(
                    "CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER)"
                )
                try exec(
                    """
                    CREATE TABLE IF NOT EXISTS claims (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, \
                    sessionId INTEGER NOT NULL, text TEXT NOT NULL, heardAt INTEGER NOT NULL, verdict TEXT, \
                    context TEXT, sources TEXT, explanationText TEXT, explanationSources TEXT, checkedText TEXT, \
                    speaker TEXT, FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)
                    """)
                try exec("CREATE INDEX IF NOT EXISTS index_claims_sessionId ON claims(sessionId)")
                try exec("PRAGMA user_version=\(Self.schemaVersion)")
                try exec("COMMIT")
            } catch {
                try? exec("ROLLBACK")
                throw error
            }
            return
        }
        // Same steps as Room's MIGRATION_1_2 and MIGRATION_2_3.
        try exec("BEGIN")
        do {
            if version == 1 {
                try exec("ALTER TABLE claims ADD COLUMN checkedText TEXT")
                version = 2
            }
            if version == 2 {
                try exec("ALTER TABLE claims ADD COLUMN speaker TEXT")
                version = 3
            }
            try exec("PRAGMA user_version=\(version)")
            try exec("COMMIT")
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }

    // MARK: SQLite plumbing (callers hold `lock`, except during init)

    private func sqliteError(_ rc: Int32) -> SQLiteError {
        SQLiteError(code: rc, message: db.map { String(cString: sqlite3_errmsg($0)) } ?? "base fermée")
    }

    private func exec(_ sql: String) throws {
        let rc = sqlite3_exec(db, sql, nil, nil, nil)
        guard rc == SQLITE_OK else { throw sqliteError(rc) }
    }

    private func prepare(_ sql: String, _ params: [Value]) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        let rc = sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        guard rc == SQLITE_OK, let stmt else { throw sqliteError(rc) }
        for (i, param) in params.enumerated() {
            let index = Int32(i + 1)
            let brc: Int32
            switch param {
            case .int(let v): brc = sqlite3_bind_int64(stmt, index, v)
            case .text(let s): brc = sqlite3_bind_text(stmt, index, s, -1, sqliteTransient)
            case .null: brc = sqlite3_bind_null(stmt, index)
            }
            guard brc == SQLITE_OK else {
                let e = sqliteError(brc)
                sqlite3_finalize(stmt)
                throw e
            }
        }
        return stmt
    }

    private func run(_ sql: String, _ params: [Value]) throws {
        let stmt = try prepare(sql, params)
        defer { sqlite3_finalize(stmt) }
        let rc = sqlite3_step(stmt)
        guard rc == SQLITE_DONE || rc == SQLITE_ROW else { throw sqliteError(rc) }
    }

    private func query<T>(_ sql: String, _ params: [Value], _ row: (OpaquePointer) throws -> T) throws -> [T] {
        let stmt = try prepare(sql, params)
        defer { sqlite3_finalize(stmt) }
        var out: [T] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW {
                out.append(try row(stmt))
            } else if rc == SQLITE_DONE {
                return out
            } else {
                throw sqliteError(rc)
            }
        }
    }

    private static func text(_ stmt: OpaquePointer, _ col: Int32) -> String? {
        guard sqlite3_column_type(stmt, col) != SQLITE_NULL, let p = sqlite3_column_text(stmt, col) else { return nil }
        return String(cString: p)
    }

    private static func claimRow(_ s: OpaquePointer) -> ClaimRow {
        ClaimRow(
            id: sqlite3_column_int64(s, 0), sessionId: sqlite3_column_int64(s, 1),
            text: text(s, 2) ?? "", heardAt: sqlite3_column_int64(s, 3), verdict: text(s, 4),
            context: text(s, 5), sources: text(s, 6), explanationText: text(s, 7),
            explanationSources: text(s, 8), checkedText: text(s, 9), speaker: text(s, 10))
    }

    /// Runs `body` under the connection lock, then tells every observer to re-read.
    private func write<T>(_ body: () throws -> T) throws -> T {
        lock.lock()
        let value: T
        do {
            value = try body()
        } catch {
            lock.unlock()
            throw error
        }
        lock.unlock()
        notifyObservers()
        return value
    }

    private func read<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    // MARK: HistoryRepository

    public func startSession(at: Date) async throws -> Int64 {
        try write {
            try run("INSERT INTO sessions (startedAt, endedAt) VALUES (?, NULL)", [.int(millis(at))])
            return sqlite3_last_insert_rowid(db)
        }
    }

    public func endSession(sessionId: Int64, at: Date) async throws {
        try write {
            try run("UPDATE sessions SET endedAt = ? WHERE id = ?", [.int(millis(at)), .int(sessionId)])
        }
    }

    public func closeUnfinishedSessions(before: Date) async throws {
        try write {
            try run(
                "UPDATE sessions SET endedAt = COALESCE((SELECT MAX(heardAt) FROM claims WHERE claims.sessionId = sessions.id), startedAt) WHERE endedAt IS NULL AND startedAt < ?",
                [.int(millis(before))])
        }
    }

    public func addClaim(
        sessionId: Int64, text: String, heardAt: Date, result: CheckResult?, speaker: String?
    ) async throws -> Int64 {
        try write {
            try run(
                "INSERT INTO claims (sessionId, text, heardAt, verdict, context, sources, explanationText, explanationSources, checkedText, speaker) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)",
                [
                    .int(sessionId), .text(text), .int(millis(heardAt)), Value(result?.verdict.rawValue),
                    Value(result?.context), Value(result.flatMap { sourcesJson($0.sources) }),
                    Value(result?.checkedText), Value(speaker),
                ])
            return sqlite3_last_insert_rowid(db)
        }
    }

    public func setResult(claimId: Int64, result: CheckResult) async throws {
        try write {
            try run(
                "UPDATE claims SET verdict = ?, context = ?, sources = ?, checkedText = ? WHERE id = ?",
                [
                    .text(result.verdict.rawValue), .text(result.context), Value(sourcesJson(result.sources)),
                    Value(result.checkedText), .int(claimId),
                ])
        }
    }

    public func setExplanation(claimId: Int64, explanation: Explanation) async throws {
        try write {
            try run(
                "UPDATE claims SET explanationText = ?, explanationSources = ? WHERE id = ?",
                [.text(explanation.text), Value(sourcesJson(explanation.sources)), .int(claimId)])
        }
    }

    public func deleteClaim(claimId: Int64) async throws {
        try write { try run("DELETE FROM claims WHERE id = ?", [.int(claimId)]) }
    }

    /// Deletes one session; its claims cascade-delete via the foreign key.
    func deleteSession(sessionId: Int64) throws {
        try write { try run("DELETE FROM sessions WHERE id = ?", [.int(sessionId)]) }
    }

    public func pendingClaims() async throws -> [ClaimRecord] {
        try read {
            try query(
                "SELECT \(Self.claimColumns) FROM claims WHERE verdict IS NULL ORDER BY id", [],
                Self.claimRow
            ).map(domain)
        }
    }

    public func clearAll() async throws {
        // Deleting sessions cascades the claims that reference them; claims never outlive their session.
        try write {
            try run("DELETE FROM sessions", [])
            try run("DELETE FROM claims", [])
        }
    }

    // MARK: Observation

    public func sessions(filter: HistoryFilter) -> AsyncStream<[SessionHistory]> {
        observe { [weak self] in try self?.loadSessions(filter: filter) }
    }

    public func claim(id: Int64) -> AsyncStream<ClaimRecord?> {
        observe { [weak self] () throws -> ClaimRecord?? in
            guard let self else { return nil }
            return .some(try self.loadClaim(id: id))
        }
    }

    /// Emits the current value now and again after every write; the observer is dropped when the stream ends.
    private func observe<T: Sendable>(_ load: @escaping @Sendable () throws -> T?) -> AsyncStream<T> {
        AsyncStream(T.self, bufferingPolicy: .bufferingNewest(1)) { continuation in
            let emit: @Sendable () -> Void = {
                if let value = try? load() { continuation.yield(value) }
            }
            let id = UUID()
            observersLock.lock()
            observers[id] = emit
            observersLock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.observersLock.lock()
                self.observers[id] = nil
                self.observersLock.unlock()
            }
            emit()
        }
    }

    private func notifyObservers() {
        observersLock.lock()
        let current = Array(observers.values)
        observersLock.unlock()
        for emit in current { emit() }
    }

    private func loadClaim(id: Int64) throws -> ClaimRecord? {
        try read {
            try query("SELECT \(Self.claimColumns) FROM claims WHERE id = ?", [.int(id)], Self.claimRow)
                .first.map(domain)
        }
    }

    private func loadSessions(filter: HistoryFilter) throws -> [SessionHistory] {
        let (sessionRows, claimRows) = try read {
            let s = try query(
                "SELECT id, startedAt, endedAt FROM sessions ORDER BY startedAt DESC, id DESC", []
            ) { (stmt: OpaquePointer) -> (Int64, Int64, Int64?) in
                let ended: Int64? =
                    sqlite3_column_type(stmt, 2) == SQLITE_NULL ? nil : sqlite3_column_int64(stmt, 2)
                return (sqlite3_column_int64(stmt, 0), sqlite3_column_int64(stmt, 1), ended)
            }
            let c = try query(
                "SELECT \(Self.claimColumns) FROM claims ORDER BY heardAt DESC, id ASC", [], Self.claimRow)
            return (s, c)
        }
        let normalizedQuery = RepeatedClaimMatcher.normalize(filter.query)
        let isFiltering = !normalizedQuery.isEmpty || !filter.verdicts.isEmpty
        let bySession = Dictionary(grouping: claimRows, by: \.sessionId)

        var out: [SessionHistory] = []
        for (id, startedAt, endedAt) in sessionRows {
            let claims = try (bySession[id] ?? []).filter { row in
                let matchesText =
                    normalizedQuery.isEmpty || RepeatedClaimMatcher.normalize(row.text).contains(normalizedQuery)
                var matchesVerdict = filter.verdicts.isEmpty
                if !matchesVerdict, let name = row.verdict, let verdict = Verdict(rawValue: name) {
                    matchesVerdict = filter.verdicts.contains(verdict)
                }
                return matchesText && matchesVerdict
            }.map(domain)
            if isFiltering && claims.isEmpty { continue }
            out.append(
                SessionHistory(
                    sessionId: id, startedAt: date(startedAt), endedAt: endedAt.map(date), claims: claims))
        }
        return out
    }
}
