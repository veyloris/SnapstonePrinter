package com.example.snapstoneprinter.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.repository.CardRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class ProxyGeneratorStateTest {
    private val api = ControlledCardApi()
    private val store = ViewModelStore()
    private lateinit var viewModel: ProxyGeneratorViewModel

    @Before
    fun createViewModel() = runBlocking {
        withContext(Dispatchers.Main) {
            val application = ApplicationProvider.getApplicationContext<Application>()
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return modelClass.cast(ProxyGeneratorViewModel(application, CardRepository(api)))!!
                }
            }
            viewModel = ViewModelProvider(store, factory)[ProxyGeneratorViewModel::class.java]
        }
    }

    @After
    fun releaseViewModel() = runBlocking {
        withContext(Dispatchers.Main) {
            store.clear()
            api.cancelPending()
        }
    }

    @Test
    fun delayedOldFetchCannotReplaceNewerCard() = runBlocking {
        val old = startFetch("Old")
        val newer = startFetch("New")
        completeGeneration(newer, "New")
        val completed = viewModel.uiState.value

        old.response.complete(card("Old"))
        withTimeout(5_000) { old.returned.await() }
        withContext(Dispatchers.Main) {
            val actual = viewModel.uiState.value
            assertEquals("New", actual.currentCard?.effectiveName)
            assertSame(completed.slips, actual.slips)
            assertEquals(completed.history, actual.history)
            assertFalse(actual.isLoading)
        }
    }

    @Test
    fun oldFetchFailureCannotClearNewRequestBusyState() = runBlocking {
        val old = startFetch("Old")
        startFetch("Still loading")

        old.response.completeExceptionally(IOException("Old request failed"))
        withTimeout(5_000) { old.returned.await() }
        withContext(Dispatchers.Main) {
            assertTrue("new request must remain loading", viewModel.uiState.value.isLoading)
            assertNull("old error must not replace current request state", viewModel.uiState.value.error)
        }
    }

    @Test
    fun toneDebounceImmediatelyDisablesPrinting() = runBlocking {
        completeGeneration(startFetch("Tone"), "Tone")

        withContext(Dispatchers.Main) {
            assertTrue(viewModel.uiState.value.canPrint)
            viewModel.setBrightness(23f)
            assertTrue("tone work includes the debounce period", viewModel.uiState.value.isRedithering)
            assertFalse("printing must wait for the selected tone", viewModel.uiState.value.canPrint)
        }
    }

    @Test
    fun completedToneUpdatesOnlyItsExistingHistorySnapshot() = runBlocking {
        completeGeneration(startFetch("Earlier"), "Earlier")
        val earlier = viewModel.uiState.value.history.single()
        completeGeneration(startFetch("Current"), "Current")
        val before = viewModel.uiState.value
        val currentId = before.history.first().id

        withContext(Dispatchers.Main) { viewModel.setBrightness(23f) }
        val after = withTimeout(5_000) {
            viewModel.uiState.first { !it.isRedithering && it.slips !== before.slips }
        }

        assertEquals(before.history.map { it.id }, after.history.map { it.id })
        assertEquals(currentId, after.history.first().id)
        assertSame("history must retain the completed current preview", after.slips, after.history.first().slips)
        assertSame("other history entries must not be replaced", earlier, after.history.last())
        assertSame("prior snapshots must remain unchanged", before.slips, before.history.first().slips)
    }

    private suspend fun startFetch(name: String): ControlledCardApi.Request {
        withContext(Dispatchers.Main) { viewModel.fetchCardByName(name) }
        return withTimeout(5_000) { api.requests.receive() }.also { assertEquals(name, it.name) }
    }

    private suspend fun completeGeneration(request: ControlledCardApi.Request, name: String) {
        request.response.complete(card(name))
        withTimeout(5_000) {
            viewModel.uiState.first {
                !it.isLoading && it.currentCard?.effectiveName == name && it.slips.isNotEmpty()
            }
        }
    }

    private fun card(name: String) = ScryfallCard(
        name = name,
        layout = "normal",
        type_line = "Instant",
        oracle_text = "Draw a card."
    )

    private class ControlledCardApi : ScryfallApiService {
        data class Request(
            val name: String,
            val response: CompletableDeferred<ScryfallCard> = CompletableDeferred(),
            val returned: CompletableDeferred<Unit> = CompletableDeferred()
        )

        val requests = Channel<Request>(Channel.UNLIMITED)
        private val pending = ConcurrentLinkedQueue<Request>()

        override suspend fun getRandomCard(query: String?): ScryfallCard = request(query.orEmpty())
        override suspend fun getCardByName(fuzzy: String): ScryfallCard = request(fuzzy)

        private suspend fun request(name: String): ScryfallCard {
            val request = Request(name)
            pending.add(request)
            requests.send(request)
            try {
                return withContext(NonCancellable) { request.response.await() }
            } finally {
                request.returned.complete(Unit)
            }
        }

        fun cancelPending() {
            pending.forEach { it.response.cancel() }
        }
    }
}
