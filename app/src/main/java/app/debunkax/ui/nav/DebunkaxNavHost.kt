package app.debunkax.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.debunkax.ui.detail.ClaimDetailScreen
import app.debunkax.ui.history.HistoryScreen
import app.debunkax.ui.listen.ListenScreen
import app.debunkax.ui.settings.PrivacyScreen
import app.debunkax.ui.settings.SettingsScreen
import app.debunkax.ui.settings.TrustedSourcesScreen
import app.debunkax.ui.speaker.SpeakerTestScreen

object Routes {
    const val LISTEN = "listen"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val TRUSTED_SOURCES = "trusted-sources"
    const val PRIVACY = "privacy"
    const val SPEAKER_TEST = "speaker-test"
    const val CLAIM_DETAIL_PATTERN = "claim/{claimId}"

    fun claimDetail(id: Long) = "claim/$id"
}

@Composable
fun DebunkaxNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LISTEN) {
        composable(Routes.LISTEN) {
            ListenScreen(
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenClaim = { id -> navController.navigate(Routes.claimDetail(id)) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenClaim = { id -> navController.navigate(Routes.claimDetail(id)) },
            )
        }
        composable(
            Routes.CLAIM_DETAIL_PATTERN,
            arguments = listOf(navArgument("claimId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val claimId = backStackEntry.arguments?.getLong("claimId") ?: 0L
            ClaimDetailScreen(claimId = claimId, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrustedSources = { navController.navigate(Routes.TRUSTED_SOURCES) },
                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                onOpenSpeakerTest = { navController.navigate(Routes.SPEAKER_TEST) },
            )
        }
        composable(Routes.TRUSTED_SOURCES) {
            TrustedSourcesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SPEAKER_TEST) {
            SpeakerTestScreen(onBack = { navController.popBackStack() })
        }
    }
}
