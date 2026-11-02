public protocol ClaimDetector: Sendable {
    func isClaim(_ sentence: String) -> Bool
}
