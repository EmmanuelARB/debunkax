package app.debunkax

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.debunkax.testing.InMemoryHistoryRepository
import app.debunkax.testing.launchDebunkax
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers Réglages > Sources de confiance: the fixed, shown-to-everyone allowlist (see AGENTS.md / ADR 0001). */
@RunWith(AndroidJUnit4::class)
class TrustedSourcesFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun trustedSourcesScreen_listsAfpFactuelAndInsee() {
        scenario = launchDebunkax(TestOverrides(history = InMemoryHistoryRepository()))

        composeRule.onNodeWithContentDescription("Réglages").performClick()
        composeRule.onNodeWithText("Sources de confiance").performClick()

        composeRule.onNodeWithText("AFP Factuel").assertExists()
        composeRule.onNodeWithText("INSEE").assertExists()
    }
}
