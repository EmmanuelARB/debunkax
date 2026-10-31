package app.debunkax

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test verifying the JUnit + kotlinx-coroutines-test wiring resolves and runs.
 */
class ExampleUnitTest {

    @Test
    fun `addition works`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `suspend test infra works`() = runTest {
        val value = suspendDouble(21)
        assertEquals(42, value)
    }

    private suspend fun suspendDouble(x: Int): Int {
        return x * 2
    }
}
