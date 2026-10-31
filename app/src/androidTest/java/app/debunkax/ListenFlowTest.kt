package app.debunkax

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.debunkax.domain.CheckResult
import app.debunkax.domain.Explanation
import app.debunkax.domain.Source
import app.debunkax.domain.Verdict
import app.debunkax.testing.FakeCheckClient
import app.debunkax.testing.FakeSpeechEngine
import app.debunkax.testing.InMemoryHistoryRepository
import app.debunkax.testing.launchDebunkax
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the main Listening flows end to end against fakes: no network, no microphone, no real DB.
 * See AGENTS.md / CONTEXT.md for the vocabulary (Listening Session, Claim, Verdict, Alert, ...).
 */
@RunWith(AndroidJUnit4::class)
class ListenFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** A Claim guaranteed to pass [app.debunkax.domain.TfidfClaimDetector]: long, with a figure. */
    private val claimText = "Le chômage a baissé de vingt pourcent en France depuis deux mille vingt"

    private fun start(fakeSpeech: FakeSpeechEngine) {
        composeRule.onNodeWithText("Démarrer l'écoute").performClick()
        composeRule.onNodeWithText("Microphone").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { fakeSpeech.attemptCount > 0 }
    }

    @Test
    fun noProviderConfigured_showsBannerAndClaimBecomesPending() {
        val fakeSpeech = FakeSpeechEngine()
        val history = InMemoryHistoryRepository()
        scenario = launchDebunkax(TestOverrides(history = history, speechEngine = fakeSpeech, checkClient = CheckClientOverride(null)))

        // The "no provider" banner only appears once a Listening Session exists, i.e. after starting.
        start(fakeSpeech)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Aucun fournisseur configuré.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Aucun fournisseur configuré.").assertExists()

        runBlocking { fakeSpeech.emitFinal(claimText) }

        composeRule.onNodeWithContentDescription("Historique").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pas encore vérifiée").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(claimText, substring = true).assertExists()
    }

    @Test
    fun falseVerdict_showsAlertAndDetailShowsExplanation() {
        val fakeSpeech = FakeSpeechEngine()
        val fakeCheck = FakeCheckClient().apply {
            enqueueChecked(CheckResult(Verdict.FALSE, "Contexte : c'est faux.", listOf(Source("AFP Factuel", "https://factuel.afp.com"))))
            explanationToReturn = Explanation(
                "Explication détaillée de test pour la vérification.",
                listOf(Source("AFP Factuel", "https://factuel.afp.com")),
            )
        }
        val history = InMemoryHistoryRepository()
        scenario = launchDebunkax(
            TestOverrides(history = history, speechEngine = fakeSpeech, checkClient = CheckClientOverride(fakeCheck)),
        )

        start(fakeSpeech)
        runBlocking { fakeSpeech.emitFinal(claimText) }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("En savoir plus").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Faux").assertExists()
        // The claim text also shows in the live-transcript line below the button; check both are there.
        assertEquals(2, composeRule.onAllNodesWithText(claimText, substring = true).fetchSemanticsNodes().size)

        composeRule.onNodeWithText("En savoir plus").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Explication détaillée de test pour la vérification.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun alert_staysOnScreenUntilDismissedOrReplaced() {
        val fakeSpeech = FakeSpeechEngine()
        val fakeCheck = FakeCheckClient().apply {
            enqueueChecked(CheckResult(Verdict.FALSE, "Contexte : c'est faux.", emptyList()))
        }
        val history = InMemoryHistoryRepository()
        scenario = launchDebunkax(
            TestOverrides(history = history, speechEngine = fakeSpeech, checkClient = CheckClientOverride(fakeCheck)),
        )

        start(fakeSpeech)
        runBlocking { fakeSpeech.emitFinal(claimText) }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("En savoir plus").fetchSemanticsNodes().isNotEmpty()
        }

        // Alerts used to vanish after 8 s; check one is still readable well past that.
        Thread.sleep(10_000)
        composeRule.onNodeWithText("En savoir plus").assertExists()
    }

    @Test
    fun accurateVerdict_noAlertButShowsInHistoryAsExact() {
        val fakeSpeech = FakeSpeechEngine()
        val fakeCheck = FakeCheckClient().apply {
            enqueueChecked(CheckResult(Verdict.ACCURATE, "Contexte : c'est exact.", emptyList()))
        }
        val history = InMemoryHistoryRepository()
        scenario = launchDebunkax(
            TestOverrides(history = history, speechEngine = fakeSpeech, checkClient = CheckClientOverride(fakeCheck)),
        )

        start(fakeSpeech)
        runBlocking { fakeSpeech.emitFinal(claimText) }

        composeRule.onNodeWithContentDescription("Historique").performClick()
        // The verdict filter chips always show a chip labelled "Exact"; wait for the claim row
        // itself (unique text) before checking the chip count.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(claimText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // One "Exact" from the filter chip, one from the claim's own VerdictChip.
        assertEquals(2, composeRule.onAllNodesWithText("Exact").fetchSemanticsNodes().size)

        composeRule.onNodeWithContentDescription("Retour").performClick()
        composeRule.onNodeWithText("En savoir plus").assertDoesNotExist()
    }

    @Test
    fun alertRaisedWhileOnHistory_showsOnReturn() {
        val fakeSpeech = FakeSpeechEngine()
        val fakeCheck = FakeCheckClient().apply {
            enqueueChecked(CheckResult(Verdict.FALSE, "Premier contexte.", emptyList()))
            enqueueChecked(CheckResult(Verdict.MISLEADING, "Second contexte.", emptyList()))
        }
        scenario = launchDebunkax(
            TestOverrides(history = InMemoryHistoryRepository(), speechEngine = fakeSpeech, checkClient = CheckClientOverride(fakeCheck)),
        )

        start(fakeSpeech)
        runBlocking { fakeSpeech.emitFinal(claimText) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Premier contexte.").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Historique").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("Retour").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { fakeSpeech.emitFinal("Les impôts ont augmenté de trente pourcent en France depuis deux mille dix") }
        composeRule.waitUntil(timeoutMillis = 5_000) { fakeCheck.checkedClaims.size == 2 }
        composeRule.onNodeWithContentDescription("Retour").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Second contexte.").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
