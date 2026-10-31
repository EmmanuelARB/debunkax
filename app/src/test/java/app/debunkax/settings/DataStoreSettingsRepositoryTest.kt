package app.debunkax.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.debunkax.domain.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises [DataStoreSettingsRepository] against a real (Robolectric) Preferences DataStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreSettingsRepositoryTest {

    private lateinit var repository: DataStoreSettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = DataStoreSettingsRepository(context)
    }

    @Test
    fun `clearProvider forgets provider and model but keeps the Check limit`() = runTest {
        repository.setProvider(Provider.OPENROUTER, "deepseek/deepseek-v4.1-flash")
        repository.setCheckLimit(50)

        repository.clearProvider()

        val settings = repository.settings.first()
        assertNull(settings.provider)
        assertNull(settings.modelId)
        assertEquals(50, settings.checkLimit)
    }
}
