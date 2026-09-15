package com.example.snapstoneprinter.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.api.ScryfallQueryBuilder
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.ui.theme.SnapstonePrinterTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppModeRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    private val store = ViewModelStore()
    private val api = RecordingApi()
    private lateinit var viewModel: ProxyGeneratorViewModel

    @Before
    fun createViewModel() {
        compose.runOnUiThread {
            viewModel = ProxyGeneratorViewModel(
                ApplicationProvider.getApplicationContext<Application>(), CardRepository(api)
            )
            store.put("mode-routing", viewModel)
        }
    }

    @After
    fun clearViewModel() {
        compose.runOnUiThread { store.clear() }
    }

    private fun screen(): StateRestorationTester = StateRestorationTester(compose).apply {
        setContent { SnapstonePrinterTheme { ProxyGeneratorScreen(viewModel) } }
    }

    private fun selectMode(name: String) {
        compose.onNodeWithContentDescription("Switch game mode").performClick()
        compose.onNodeWithText(name).performClick()
    }

    @Test
    fun momirRestoresLabelAndCmcRouting() {
        val restoration = screen()
        selectMode("MomirVig")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("MomirVig").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pick a CMC").assertIsDisplayed().performClick()
        compose.onNodeWithText("Pick a converted mana cost").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, api.randomCalls.get())
            assertEquals(0, api.namedCalls.get())
        }
    }

    @Test
    fun snapstoneRestoresDirectRollRouting() {
        val restoration = screen()
        selectMode("MomirVig")
        selectMode("Snapstone Wielder")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Snapstone Wielder").assertIsDisplayed()
        compose.onNodeWithContentDescription("Random card").assertIsDisplayed().performClick()
        compose.waitUntil { api.randomCalls.get() == 1 }
        compose.onNodeWithText("Pick a converted mana cost").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, api.randomCalls.get())
            assertEquals(0, api.namedCalls.get())
            assertEquals(ScryfallQueryBuilder.build(), api.lastQuery)
        }
    }

    private class RecordingApi : ScryfallApiService {
        val randomCalls = AtomicInteger()
        val namedCalls = AtomicInteger()
        @Volatile var lastQuery: String? = null

        override suspend fun getRandomCard(query: String?): ScryfallCard {
            lastQuery = query
            randomCalls.incrementAndGet()
            throw IOException("Routing test: no network request")
        }

        override suspend fun getCardByName(fuzzy: String): ScryfallCard {
            namedCalls.incrementAndGet()
            throw IOException("Routing test: no network request")
        }
    }
}
