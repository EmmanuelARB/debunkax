import Foundation

/// TF-IDF (word 1-2grams + char 3-5grams) + logistic regression Claim filter, trained on labelled
/// French TV-debate subtitles (see `spikes/claims/RESULTS.md`). Pure Swift, no platform dependency.
///
/// Reproduces scikit-learn's `TfidfVectorizer` + `LogisticRegression` scoring exactly: normalize the sentence
/// the same way as `RepeatedClaimMatcher.normalize`, compute a smooth, sublinear (`1 + ln(tf)`) TF-IDF vector
/// per block (word n-grams, char n-grams), L2-normalize each block separately, concatenate word-then-char,
/// dot with the logistic regression's coefficients plus intercept, then the logistic function, compared
/// against the trained threshold.
public final class TfidfClaimDetector: ClaimDetector {
    private let wordBlock: NgramBlock
    private let charBlock: NgramBlock
    private let coef: [Double]
    private let intercept: Double
    private let threshold: Double

    /// Builds the detector from the contents of `claim_model.json`.
    public init(modelData: Data) throws {
        let export = try JSONDecoder().decode(TfidfExportJson.self, from: modelData)
        wordBlock = NgramBlock(block: export.word, isChar: false)
        charBlock = NgramBlock(block: export.char, isChar: true)
        coef = export.coef
        intercept = export.intercept
        threshold = export.threshold
    }

    public convenience init(contentsOf url: URL) throws {
        try self.init(modelData: Data(contentsOf: url))
    }

    public static func fromJson(_ source: String) throws -> TfidfClaimDetector {
        try TfidfClaimDetector(modelData: Data(source.utf8))
    }

    public func isClaim(_ sentence: String) -> Bool { score(sentence) >= threshold }

    /// Claim probability in [0, 1]; exposed for tests.
    public func score(_ sentence: String) -> Double {
        let text = RepeatedClaimMatcher.normalize(sentence)
        var dot = intercept
        for (index, value) in wordBlock.tfidf(text) { dot += value * coef[index] }
        let charOffset = wordBlock.vocabSize
        for (index, value) in charBlock.tfidf(text) { dot += value * coef[charOffset + index] }
        return 1.0 / (1.0 + exp(-dot))
    }

    /// One of the two sub-vectorizers (word or char), with its own vocabulary and IDF weights.
    private struct NgramBlock: Sendable {
        let vocab: [String: Int]
        let idf: [Double]
        let minN: Int
        let maxN: Int
        let isChar: Bool

        init(block: NgramBlockJson, isChar: Bool) {
            vocab = block.vocab
            idf = block.idf
            minN = block.ngramRange[0]
            maxN = block.ngramRange[1]
            self.isChar = isChar
        }

        var vocabSize: Int { idf.count }

        /// Sparse, L2-normalized TF-IDF vector as (vocab index, weight) pairs for in-vocabulary n-grams, by index.
        /// Sorted so the floating-point sums run in the same order every time: a Swift Dictionary's iteration order
        /// can differ between two instances holding the same keys, which changed the score's last bits.
        func tfidf(_ text: String) -> [(index: Int, weight: Double)] {
            var counts: [Int: Int] = [:]
            for gram in ngrams(text) {
                guard let index = vocab[gram] else { continue }
                counts[index, default: 0] += 1
            }
            if counts.isEmpty { return [] }
            var weights: [(index: Int, weight: Double)] = []
            weights.reserveCapacity(counts.count)
            var normSquared = 0.0
            for (index, count) in counts.sorted(by: { $0.key < $1.key }) {
                let weight = (1.0 + log(Double(count))) * idf[index]
                weights.append((index, weight))
                normSquared += weight * weight
            }
            if normSquared == 0.0 { return [] }
            let norm = normSquared.squareRoot()
            return weights.map { ($0.index, $0.weight / norm) }
        }

        private func ngrams(_ text: String) -> [String] { isChar ? charNgrams(text) : wordNgrams(text) }

        /// Port of sklearn's `CountVectorizer._word_ngrams` for `analyzer="word"`.
        private func wordNgrams(_ text: String) -> [String] {
            let tokens = text.split(separator: " ").map(String.init)
            if maxN == 1 { return tokens }
            let n = tokens.count
            var result: [String] = minN == 1 ? tokens : []
            let start = minN == 1 ? 2 : minN
            for k in stride(from: start, through: min(maxN, n), by: 1) {
                for i in 0...(n - k) {
                    result.append(tokens[i..<(i + k)].joined(separator: " "))
                }
            }
            return result
        }

        /// Port of sklearn's `CountVectorizer._char_ngrams` for `analyzer="char"` (not `char_wb`: no word-boundary padding).
        private func charNgrams(_ text: String) -> [String] {
            let scalars = Array(text.unicodeScalars)
            let len = scalars.count
            var result: [String] = []
            let start: Int
            if minN == 1 {
                for scalar in scalars { result.append(String(scalar)) }
                start = 2
            } else {
                start = minN
            }
            for n in stride(from: start, through: min(maxN, len), by: 1) {
                for i in 0...(len - n) {
                    var view = String.UnicodeScalarView()
                    view.append(contentsOf: scalars[i..<(i + n)])
                    result.append(String(view))
                }
            }
            return result
        }
    }
}

private struct NgramBlockJson: Decodable, Sendable {
    let ngramRange: [Int]
    let vocab: [String: Int]
    let idf: [Double]

    enum CodingKeys: String, CodingKey {
        case ngramRange = "ngram_range"
        case vocab, idf
    }
}

private struct TfidfExportJson: Decodable, Sendable {
    let word: NgramBlockJson
    let char: NgramBlockJson
    let coef: [Double]
    let intercept: Double
    let threshold: Double
}
