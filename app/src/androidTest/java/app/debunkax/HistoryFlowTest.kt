package app.debunkax

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.debunkax.domain.CheckResult
import app.debunkax.domain.Verdict
import app.debunkax.testing.InMemoryHistoryRepository
import app.debunkax.testing.launchDebunkax
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers Historique: search, Verdict chip filtering, and clearing with confirmation. */
@RunWith(AndroidJUnit4::class)
class HistoryFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val falseClaim = "Le chômage a doublé depuis mille neuf cent quatre-vingt en France"
    private val accurateClaim = "Le PIB de la France a progressé de trois pourcent cette année"
    private val misleadingClaim = "Les impôts ont augmenté de cinquante pourcent depuis dix ans"

    /** Seeds one session with three already-checked Claims, then opens Historique. */
    private fun launchWithSeededHistory(): InMemoryHistoryRepository {
        val history = InMemoryHistoryRepository()
        runBlocking {
            val sessionId = history.startSession(Instant.parse("2000-01-01T10:00:00Z"))
            history.addClaim(sessionId, falseClaim, Instant.parse("2000-01-01T10:00:01Z"), CheckResult(Verdict.FALSE, "Contexte faux.", emptyList()))
            history.addClaim(sessionId, accurateClaim, Instant.parse("2000-01-01T10:00:02Z"), CheckResult(Verdict.ACCURATE, "Contexte exact.", emptyList()))
            history.addClaim(sessionId, misleadingClaim, Instant.parse("2000-01-01T10:00:03Z"), CheckResult(Verdict.MISLEADING, "Contexte trompeur.", emptyList()))
            history.endSession(sessionId, Instant.parse("2000-01-01T10:05:00Z"))
        }
        scenario = launchDebunkax(TestOverrides(history = history))
        composeRule.onNodeWithContentDescription("Historique").performClick()
        composeRule.onNodeWithText(falseClaim, substring = true).assertExists()
        return history
    }

    @Test
    fun search_filtersToMatchingClaim() {
        launchWithSeededHistory()

        composeRule.onNodeWithText("Rechercher").performTextInput("impôts")

        composeRule.onNodeWithText(misleadingClaim, substring = true).assertExists()
        composeRule.onNodeWithText(falseClaim, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(accurateClaim, substring = true).assertDoesNotExist()
    }

    @Test
    fun verdictChip_filtersToMatchingVerdict() {
        launchWithSeededHistory()

        // "Faux" also labels the FALSE claim's own VerdictChip; target the selectable filter chip.
        composeRule.onNode(hasText("Faux") and isSelectable()).performClick()

        composeRule.onNodeWithText(falseClaim, substring = true).assertExists()
        composeRule.onNodeWithText(accurateClaim, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(misleadingClaim, substring = true).assertDoesNotExist()
    }

    @Test
    fun clearHistory_asksForConfirmationThenEmptiesList() {
        launchWithSeededHistory()

        composeRule.onNodeWithText("Effacer l'historique").performClick()
        composeRule.onNodeWithText("Effacer l'historique ?").assertExists()

        // The dialog's own confirm button, distinct from the "Effacer l'historique" TextButton behind it.
        composeRule.onNodeWithText("Effacer").performClick()

        composeRule.onNodeWithText("Aucune affirmation vérifiée pour l'instant.").assertExists()
        composeRule.onNodeWithText(falseClaim, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(accurateClaim, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(misleadingClaim, substring = true).assertDoesNotExist()
    }
}
