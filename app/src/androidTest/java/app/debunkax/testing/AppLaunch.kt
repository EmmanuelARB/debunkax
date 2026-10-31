package app.debunkax.testing

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.debunkax.MainActivity
import app.debunkax.TestOverrides
import app.debunkax.container

/**
 * Sets [app.debunkax.AppContainer.testOverrides] and launches [MainActivity] against them.
 *
 * The container is a singleton created once when the instrumentation process starts, so overrides
 * must be installed before the activity (and its Composables) read them for the first time; this
 * must be called before any Compose interaction in the test.
 */
fun launchDebunkax(overrides: TestOverrides): ActivityScenario<MainActivity> {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    context.container.testOverrides = overrides
    return ActivityScenario.launch(MainActivity::class.java)
}
