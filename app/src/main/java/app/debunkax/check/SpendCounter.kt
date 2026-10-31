package app.debunkax.check

import app.debunkax.domain.Spend

/** Adds up the cost of a client's requests; Checks run concurrently, hence the locking. */
internal class SpendCounter(private val estimated: Boolean) {
    private var usd = 0.0

    @Synchronized
    fun add(amount: Double) {
        usd += amount
    }

    val spend: Spend
        @Synchronized get() = Spend(usd, estimated)
}
