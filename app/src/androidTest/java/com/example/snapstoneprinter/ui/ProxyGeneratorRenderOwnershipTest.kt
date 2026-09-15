package com.example.snapstoneprinter.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.image.ArtResult
import com.example.snapstoneprinter.image.ArtSource
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProxyGeneratorRenderOwnershipTest {
    @Test
    fun toneChangedDuringFetchUsesLatestWithoutRefetch() = stateTest { fixture ->
        fixture.art.hold = true
        fixture.vm.fetchCardByName("First")
        runCurrent()
        fixture.vm.setContrast(2f)
        fixture.vm.setBrightness(25f)
        runCurrent()
        assertEquals(1, fixture.api.names.size)
        assertEquals(1, fixture.art.calls.size)
        assertTrue(fixture.renderer.calls.isEmpty())

        fixture.art.calls.single().complete(ArtResult.Success(bitmap(Color.GRAY)))
        runCurrent()
        val render = fixture.renderer.calls.single()
        assertEquals(ToneSettings(2f, 25f), render.tone)
        render.complete(Color.BLACK)
        runCurrent()
        assertEquals(ToneSettings(2f, 25f), fixture.state.appliedTone)
        assertEquals(1, fixture.state.history.size)
    }

    @Test
    fun toneChangedDuringInitialRenderRejectsOldCompletion() = stateTest { fixture ->
        val old = fixture.start("Initial")
        fixture.vm.setBrightness(30f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        val newer = fixture.renderer.calls.last()
        assertNotSame(old, newer)

        old.complete(Color.BLACK)
        runCurrent()
        assertTrue(fixture.state.isLoading)
        assertNull(fixture.state.currentPullId)
        assertTrue(fixture.state.slips.isEmpty())
        assertTrue(fixture.state.history.isEmpty())

        newer.complete(Color.WHITE)
        runCurrent()
        assertFalse(fixture.state.isLoading)
        assertEquals(1, fixture.state.history.size)
        assertEquals(30f, fixture.state.appliedTone!!.brightness)
        assertEquals(Color.WHITE, fixture.state.slips.single().bitmap.getPixel(0, 0))
    }

    @Test
    fun newGenerationRejectsLateFetchAndRender() = stateTest { fixture ->
        val oldRender = fixture.start("Old")
        val current = fixture.finish("New", Color.WHITE)
        oldRender.complete(Color.BLACK)
        runCurrent()
        assertEquals("New", fixture.state.currentCard?.effectiveName)
        assertSame(current.slips, fixture.state.slips)
        assertEquals(current.history, fixture.state.history)
        assertNull(fixture.state.error)
        assertNull(fixture.state.renderError)
        assertFalse(fixture.state.isLoading)
        assertFalse(fixture.state.isRedithering)
    }

    @Test
    fun debounceImmediatelyBlocksCurrentPrinting() = stateTest { fixture ->
        fixture.finish("Earlier")
        val oldEntry = fixture.state.history.single()
        fixture.finish("Current")
        val currentEntry = fixture.state.history.first()
        fixture.vm.setBrightness(19f)

        assertTrue(fixture.state.isRedithering)
        assertFalse(fixture.state.canPrint)
        assertFalse(fixture.vm.tryReprint(currentEntry))
        fixture.vm.printCurrentCard()
        assertFalse(fixture.state.printJob.isBusy)
        assertTrue("different completed entries stay eligible", fixture.vm.tryReprint(oldEntry))
    }

    @Test
    fun reditherUpdatesMatchingHistoryWithoutReordering() = stateTest { fixture ->
        fixture.finish("Earlier")
        val earlier = fixture.state.history.single()
        fixture.finish("Current", Color.BLACK)
        val before = fixture.state
        val oldEntry = before.history.first()

        fixture.vm.setBrightness(50f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        fixture.renderer.calls.last().complete(Color.WHITE)
        runCurrent()

        assertEquals(before.history.map { it.id }, fixture.state.history.map { it.id })
        assertSame(earlier, fixture.state.history.last())
        assertSame(fixture.state.slips, fixture.state.history.first().slips)
        assertSame(fixture.state.history.first(), fixture.vm.resolveHistoryEntry(oldEntry.id))
        assertSame(before.slips, oldEntry.slips)
        assertEquals(Color.BLACK, oldEntry.slips.single().bitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, fixture.state.slips.single().bitmap.getPixel(0, 0))
    }

    @Test
    fun failedTonePreservesPreviewHistoryAndAppliedControls() = stateTest { fixture ->
        val before = fixture.finish("Retained")
        fixture.vm.setContrast(2f)
        fixture.vm.setBrightness(55f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        fixture.renderer.calls.last().result.completeExceptionally(IOException("render failed"))
        runCurrent()

        assertSame(before.slips, fixture.state.slips)
        assertEquals(before.history, fixture.state.history)
        assertEquals(before.appliedTone, fixture.state.appliedTone)
        assertEquals(before.contrast, fixture.state.contrast)
        assertEquals(before.brightness, fixture.state.brightness)
        assertNull(fixture.state.error)
        assertEquals("Could not apply tone changes. Previous preview and settings kept.", fixture.state.renderError)
        assertTrue(fixture.state.canPrint)

        fixture.vm.setBrightness(15f)
        assertNull(fixture.state.renderError)
        assertTrue(fixture.state.isRedithering)
    }

    @Test
    fun oldFailureCannotClearNewBusyFlags() = stateTest { fixture ->
        fixture.finish("Current")
        fixture.vm.setBrightness(10f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        val old = fixture.renderer.calls.last()
        fixture.vm.setBrightness(20f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        val newer = fixture.renderer.calls.last()
        old.result.completeExceptionally(IOException("stale error"))
        runCurrent()
        assertTrue(fixture.state.isRedithering)
        assertNull(fixture.state.renderError)
        assertNull(fixture.state.error)
        newer.complete(Color.WHITE)
        runCurrent()
        assertFalse(fixture.state.isRedithering)
        assertEquals(20f, fixture.state.appliedTone!!.brightness)
    }

    @Test
    fun failedGenerationCannotReuseOldArt() = stateTest { fixture ->
        fixture.finish("Old")
        val history = fixture.state.history
        fixture.art.failure = true
        val failing = fixture.start("No art")
        assertEquals(listOf<Bitmap?>(null), failing.art)
        failing.result.completeExceptionally(IOException("initial render failed"))
        runCurrent()
        assertNull(fixture.state.currentPullId)
        assertNull(fixture.state.appliedTone)
        assertTrue(fixture.state.slips.isEmpty())
        assertEquals(history, fixture.state.history)
        assertFalse(fixture.state.isLoading)
        assertNotNull(fixture.state.error)

        val count = fixture.renderer.calls.size
        fixture.vm.setBrightness(20f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        assertEquals(count, fixture.renderer.calls.size)
    }

    @Test
    fun missingHistoryDoesNotDispatch() = stateTest { fixture ->
        fixture.finish("Evicted")
        val old = fixture.state.history.single()
        repeat(ProxyGeneratorViewModel.MAX_HISTORY) { fixture.finish("Card $it") }
        assertEquals(ProxyGeneratorViewModel.MAX_HISTORY, fixture.state.history.size)
        assertTrue(fixture.state.history.zipWithNext().all { (newer, older) -> newer.id > older.id })
        assertNull(fixture.vm.resolveHistoryEntry(old.id))
        assertFalse(fixture.vm.tryReprint(old))
        fixture.vm.reprint(old)
        assertFalse(fixture.state.printJob.isBusy)
    }

    @Test
    fun malformedInitialRenderNeverPublishesACompletedPull() = stateTest { fixture ->
        for (count in listOf(0, 2)) {
            val render = fixture.start("Malformed")
            render.result.complete(List(count) { PrintSlip(bitmap(Color.BLACK), "Malformed", 0, 1, null) })
            runCurrent()
            assertNull(fixture.state.currentPullId)
            assertNull(fixture.state.appliedTone)
            assertTrue(fixture.state.history.isEmpty())
            assertFalse(fixture.state.isLoading)
            assertNotNull(fixture.state.error)
        }
    }

    private fun stateTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val scope: TestScope) {
        val api = ImmediateCardApi()
        val art = ControlledArtSource()
        val renderer = ControlledRenderer()
        private val store = ViewModelStore()
        val vm: ProxyGeneratorViewModel
        val state: ProxyGeneratorUiState get() = vm.uiState.value

        init {
            Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))
            val application = ApplicationProvider.getApplicationContext<Application>()
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                    ProxyGeneratorViewModel(application, CardRepository(api), art, renderer)
                )!!
            }
            vm = ViewModelProvider(store, factory)[ProxyGeneratorViewModel::class.java]
        }

        fun start(name: String): RenderCall {
            vm.fetchCardByName(name)
            scope.runCurrent()
            return renderer.calls.last()
        }

        fun finish(name: String, color: Int = Color.BLACK): ProxyGeneratorUiState {
            start(name).complete(color)
            scope.runCurrent()
            return state
        }

        fun close() {
            try {
                store.clear()
                art.calls.forEach { it.cancel() }
                renderer.calls.forEach { it.result.cancel() }
                scope.runCurrent()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private class ImmediateCardApi : ScryfallApiService {
        val names = mutableListOf<String>()
        override suspend fun getRandomCard(query: String?): ScryfallCard = getCardByName(query.orEmpty())
        override suspend fun getCardByName(fuzzy: String): ScryfallCard {
            names += fuzzy
            return ScryfallCard(name = fuzzy, layout = "normal", image_uris = ImageUris(artCrop = "fixture://$fuzzy"))
        }
    }

    private class ControlledArtSource : ArtSource {
        var hold = false
        var failure = false
        val calls = mutableListOf<CompletableDeferred<ArtResult>>()
        override suspend fun fetch(url: String): ArtResult {
            val result = CompletableDeferred<ArtResult>()
            calls += result
            if (!hold) result.complete(if (failure) ArtResult.Failure("Unavailable") else ArtResult.Success(bitmap(Color.GRAY)))
            return withContext(NonCancellable) { result.await() }
        }
    }

    private data class RenderCall(
        val plan: List<SlipContent>,
        val art: List<Bitmap?>,
        val tone: ToneSettings,
        val result: CompletableDeferred<List<PrintSlip>> = CompletableDeferred()
    ) {
        fun complete(color: Int) {
            result.complete(plan.map {
                PrintSlip(bitmap(color), it.name, it.faceIndex, it.totalSlips, it.label)
            })
        }
    }

    private class ControlledRenderer : SlipRenderer {
        val calls = mutableListOf<RenderCall>()
        override suspend fun render(
            plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float
        ): List<PrintSlip> {
            val call = RenderCall(plan.toList(), art.toList(), ToneSettings(contrast, brightness))
            calls += call
            return withContext(NonCancellable) { call.result.await() }
        }
    }

    companion object {
        private fun bitmap(color: Int): Bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }
}
