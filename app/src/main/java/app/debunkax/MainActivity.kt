package app.debunkax

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.debunkax.domain.Provider
import app.debunkax.ui.nav.DebunkaxNavHost
import app.debunkax.ui.theme.DebunkaxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOpenRouterDeepLink(intent)
        setContent {
            DebunkaxTheme {
                DebunkaxNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenRouterDeepLink(intent)
    }

    private fun handleOpenRouterDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "debunkax" || data.host != "oauth" || data.path != "/openrouter") return
        val code = data.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            try {
                val key = container.openRouterAuth.exchange(code)
                container.keyVault.put(Provider.OPENROUTER, key)
                Toast.makeText(this@MainActivity, "Connexion OpenRouter réussie.", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Échec de la connexion OpenRouter.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
