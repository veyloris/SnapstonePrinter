package com.example.snapstoneprinter.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.print.ExportedSlips
import com.example.snapstoneprinter.data.print.PrintJobState
import com.example.snapstoneprinter.data.print.SlipExporter
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.image.ArtResult
import com.example.snapstoneprinter.image.ArtSource
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PrintExportOwnershipTest {
    @Test fun startReservesBeforeExport() = stateTest { f ->
        val entry = f.finish()
        assertTrue(f.vm.tryReprint(entry))
        assertTrue(f.job is PrintJobState.Preparing)
        assertFalse(f.vm.tryReprint(entry))
        f.vm.printCurrentCard()
        runCurrent()
        assertEquals(1, f.exporter.calls.size)
    }

    @Test fun handledInvalidBatchIsDiscarded() = stateTest { f ->
        for (uris in listOf(emptyList(), listOf(""), listOf("content://a", "content://b"))) {
            f.vm.tryReprint(f.finish())
            runCurrent()
            val call = f.exporter.calls.last()
            val invalid = ExportedSlips(call.id, uris)
            call.result.complete(invalid)
            runCurrent()
            assertTrue(f.job is PrintJobState.Failed)
            assertSame(invalid, f.exporter.discarded.last())
        }
        assertEquals(3, f.exporter.discarded.size)
    }

    @Test fun mismatchedExportIdentityCannotBeRelabeled() = stateTest { f ->
        f.vm.tryReprint(f.finish())
        runCurrent()
        f.exporter.calls.single().result.complete(ExportedSlips(UUID.randomUUID().toString(), listOf("content://foreign")))
        runCurrent()
        assertEquals("Could not prepare images for this print job.", (f.job as PrintJobState.Failed).message)
        assertTrue(f.exporter.discarded.isEmpty())
    }

    @Test fun canceledExportCompletesLate() = stateTest { f ->
        val entry = f.finish()
        f.vm.tryReprint(entry)
        runCurrent()
        val old = f.exporter.calls.single()
        f.vm.stopPrinting(old.id)
        assertTrue(f.vm.tryReprint(entry))
        runCurrent()
        val current = f.job
        val late = old.complete()
        runCurrent()
        assertSame(current, f.job)
        assertSame(late, f.exporter.discarded.single())
        f.exporter.calls.last().complete()
        runCurrent()
        assertTrue(f.job is PrintJobState.Ready)
    }

    @Test fun canceledForeignBatchCannotAffectNewJob() = stateTest { f ->
        val entry = f.finish()
        f.vm.tryReprint(entry)
        runCurrent()
        val old = f.exporter.calls.single()
        f.vm.stopPrinting(old.id)
        f.vm.tryReprint(entry)
        runCurrent()
        val current = f.job
        old.result.complete(ExportedSlips(UUID.randomUUID().toString(), listOf("content://foreign")))
        runCurrent()
        assertSame(current, f.job)
        assertTrue(f.exporter.discarded.isEmpty())
    }

    @Test fun stopDiscardsOnlyBeforeClaimAndClearRetainsReady() = stateTest { f ->
        val entry = f.finish()
        f.vm.tryReprint(entry)
        runCurrent()
        val first = f.exporter.calls.last().complete()
        runCurrent()
        f.vm.stopPrinting((f.job as PrintJobState.Ready).jobId)
        runCurrent()
        assertSame(first, f.exporter.discarded.single())
        f.vm.tryReprint(entry)
        runCurrent()
        f.exporter.calls.last().complete()
        runCurrent()
        val ready = f.job as PrintJobState.Ready
        assertNotNull(f.vm.claimPrintLaunch(ready.token))
        assertNull(f.vm.claimPrintLaunch(ready.token))
        f.vm.stopPrinting(ready.jobId)
        assertTrue(f.job is PrintJobState.Stopping)
        assertFalse(f.vm.tryReprint(entry))
        f.vm.onPrintReturned(ready.token)
        runCurrent()
        assertTrue(f.job is PrintJobState.Cancelled)
        assertEquals(1, f.exporter.discarded.size)
        f.vm.tryReprint(entry)
        runCurrent()
        f.exporter.calls.last().complete()
        runCurrent()
        f.store.clear()
        runCurrent()
        assertEquals("Clearing Ready must leave completed files to cache lifecycle", 1, f.exporter.discarded.size)
    }

    @Test fun latestHistoryAndFrozenExportSnapshot() = stateTest { f ->
        val stale = f.finish()
        f.vm.setBrightness(12f)
        assertFalse(f.vm.tryReprint(stale))
        f.vm.printCurrentCard()
        runCurrent()
        assertTrue(f.exporter.calls.isEmpty())
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        val latest = f.vm.uiState.value.history.single()
        assertNotSame(stale.slips.single().bitmap, latest.slips.single().bitmap)
        assertTrue(f.vm.tryReprint(stale))
        runCurrent()
        val captured = f.exporter.calls.single().slips
        assertSame(latest.slips.single().bitmap, captured.single().bitmap)
        f.vm.setBrightness(24f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        assertNotSame(f.vm.uiState.value.slips.single().bitmap, captured.single().bitmap)
        assertSame(latest.slips.single().bitmap, captured.single().bitmap)
    }

    @Test fun failedToneExportsRetainedCompletedSnapshot() = stateTest { f ->
        val entry = f.finish()
        f.renderer.fail = true
        f.vm.setBrightness(12f)
        advanceTimeBy(ProxyGeneratorViewModel.REDITHER_DEBOUNCE_MS)
        runCurrent()
        assertNotNull(f.vm.uiState.value.renderError)
        assertEquals(f.vm.uiState.value.appliedTone!!.brightness, f.vm.uiState.value.brightness)
        assertTrue(f.vm.tryReprint(entry))
        runCurrent()
        assertSame(entry.slips.single().bitmap, f.exporter.calls.single().slips.single().bitmap)
        f.vm.stopPrinting((f.job as PrintJobState.Preparing).jobId)
        f.vm.printCurrentCard()
        runCurrent()
        assertSame(entry.slips.single().bitmap, f.exporter.calls.last().slips.single().bitmap)
    }

    @Test fun clearingPreparingCancelsExportWithoutDetachedCleanup() = stateTest { f ->
        f.exporter.ignoreCancellation = false
        f.vm.tryReprint(f.finish())
        runCurrent()
        f.store.clear()
        runCurrent()
        assertTrue(f.exporter.cancelled)
        assertTrue(f.exporter.discarded.isEmpty())
    }

    @Test fun missingHistoryIdDoesNotExport() = stateTest { f ->
        val existing = f.finish()
        assertFalse(f.vm.tryReprint(existing.copy(id = existing.id + 10_000)))
        runCurrent()
        assertEquals(PrintJobState.Idle, f.job)
        assertTrue(f.exporter.calls.isEmpty())
    }

    private fun stateTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val f = Fixture(this)
        try { block(f) } finally {
            f.store.clear()
            f.exporter.calls.forEach { it.result.cancel() }
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(private val scope: TestScope) {
        val store = ViewModelStore()
        val exporter = ControlledExporter()
        val renderer = ImmediateRenderer()
        val vm: ProxyGeneratorViewModel
        val job get() = vm.uiState.value.printJob
        init {
            Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))
            val api = object : ScryfallApiService {
                override suspend fun getRandomCard(query: String?) = ScryfallCard(name = "Fixture")
                override suspend fun getCardByName(fuzzy: String) = ScryfallCard(name = fuzzy)
            }
            val art = object : ArtSource {
                override suspend fun fetch(url: String): ArtResult = error("Unexpected art fetch")
            }
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                    ProxyGeneratorViewModel(ApplicationProvider.getApplicationContext<Application>(),
                        CardRepository(api), art, renderer, exporter)
                )!!
            }
            vm = ViewModelProvider(store, factory)[ProxyGeneratorViewModel::class.java]
        }
        fun finish(): HistoryEntry {
            vm.fetchCardByName("Fixture")
            scope.runCurrent()
            return vm.uiState.value.history.first()
        }
    }

    private class ImmediateRenderer : SlipRenderer {
        var fail = false
        override suspend fun render(plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float): List<PrintSlip> {
            if (fail) throw IOException("Controlled tone failure")
            return plan.map { PrintSlip(Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888),
                it.name, it.faceIndex, it.totalSlips, it.label) }
        }
    }

    private class ExportCall(val id: String, val slips: List<PrintSlip>) {
        val result = CompletableDeferred<ExportedSlips>()
        fun complete(): ExportedSlips = ExportedSlips(id, slips.indices.map { "content://fixture/$id/slip_$it.png" })
            .also { result.complete(it) }
    }
    private class ControlledExporter : SlipExporter {
        var ignoreCancellation = true
        var cancelled = false
        val calls = mutableListOf<ExportCall>()
        val discarded = mutableListOf<ExportedSlips>()
        override suspend fun export(jobId: String, slips: List<PrintSlip>): ExportedSlips {
            val call = ExportCall(jobId, slips)
            calls += call
            try {
                return if (ignoreCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
        override suspend fun discardUnshared(batch: ExportedSlips) { discarded += batch }
    }
}
