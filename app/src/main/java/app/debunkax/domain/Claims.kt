package app.debunkax.domain

import java.time.Instant

enum class Verdict(val raisesAlert: Boolean) {
    FALSE(true),
    MISLEADING(true),
    LACKS_CONTEXT(true),
    UNVERIFIABLE(false),
    ACCURATE(false),
}

data class Source(val title: String, val url: String)

/** A Claim as heard live. Holds the surrounding transcript, so it must never be persisted. */
data class HeardClaim(
    val text: String,
    val heardAt: Instant,
    val surroundingTranscript: String,
    /** Who said it, recognised by voice; null when unknown (ADR 0003). */
    val speaker: String? = null,
    /** Who a « tu » / « vous » refers to: the most recent other Speaker; null when unknown. */
    val addressedSpeaker: String? = null,
)

data class CheckResult(
    val verdict: Verdict,
    val context: String,
    val sources: List<Source>,
    /** The Claim as it was checked, rewritten from the heard sentence into a clear one; null when checked as heard. */
    val checkedText: String? = null,
)

sealed interface CheckOutcome {
    data class Checked(val result: CheckResult) : CheckOutcome
    /** The provider judged the sentence not to be a checkable factual assertion after all. */
    data object NotAClaim : CheckOutcome
}

data class Explanation(val text: String, val sources: List<Source>)

sealed class CheckFailure(message: String) : Exception(message) {
    class Offline : CheckFailure("Pas de connexion")
    class InvalidKey : CheckFailure("Clé API invalide")
    class NoCredit : CheckFailure("Crédit épuisé chez le fournisseur")
    class SearchDisabled : CheckFailure("La recherche web est désactivée sur ce compte")
    class RateLimited : CheckFailure("Trop de requêtes, réessai plus tard")
    class BudgetReached : CheckFailure("Limite de vérifications de la session atteinte")
    class Unexpected(detail: String) : CheckFailure(detail)
}

interface CheckClient {
    suspend fun check(claim: HeardClaim): CheckOutcome
    suspend fun explain(claimText: String, result: CheckResult): Explanation

    /** What this client's requests have cost so far, or null when it can't tell. */
    val spent: Spend? get() = null
}

/** [estimated] when worked out from token counts and list prices rather than reported by the provider. */
data class Spend(val usd: Double, val estimated: Boolean)
