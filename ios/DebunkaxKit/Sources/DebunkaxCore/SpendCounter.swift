import Synchronization

/// Adds up the cost of a client's requests; Checks run concurrently, hence the locking.
public final class SpendCounter: Sendable {
    private let estimated: Bool
    private let usd = Mutex<Double>(0)

    public init(estimated: Bool) {
        self.estimated = estimated
    }

    public func add(_ amount: Double) {
        usd.withLock { $0 += amount }
    }

    public var spend: Spend { Spend(usd: usd.withLock { $0 }, estimated: estimated) }
}
