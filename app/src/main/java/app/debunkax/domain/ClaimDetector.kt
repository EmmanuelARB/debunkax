package app.debunkax.domain

fun interface ClaimDetector {
    fun isClaim(sentence: String): Boolean
}
