package com.example.snapstoneprinter.ui

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.CardFace
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.print.ExportedSlips
import com.example.snapstoneprinter.data.print.PrintJobState
import com.example.snapstoneprinter.data.print.PrinterTargetStore
import com.example.snapstoneprinter.data.print.SlipExporter
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.image.ArtResult
import com.example.snapstoneprinter.image.ArtSource
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrintDispatchHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: ProxyGeneratorViewModel
    private val registry = RecordingRegistry()

    @Before fun setup() {
        runBlocking { PrinterTargetStore.forget(compose.activity.application) }
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity, factory())[ProxyGeneratorViewModel::class.java]
            install(activity, registry)
            vm.fetchCardByName("Two faces")
        }
        compose.waitUntil { vm.uiState.value.canPrint }
        compose.runOnIdle { assertEquals(2, vm.uiState.value.slips.size) }
    }

    @Test fun returnWaitsForChoiceAndStopSendsNothing() {
        start()
        val first = registry.launches.single()
        compose.runOnIdle { registry.dispatchResult(first.code, Activity.RESULT_OK, null) }
        compose.onNodeWithText("Continue with slip 2 of 2?").assertExists()
        assertEquals(1, registry.launches.size)
        compose.onNodeWithText("Stop").performClick()
        compose.runOnIdle { assertTrue(vm.uiState.value.printJob is PrintJobState.Cancelled) }
        assertEquals(1, registry.launches.size)
    }

    @Test fun canceledReturnAlsoRequiresExplicitNextAndDuplicateOldResultIsHarmless() {
        start()
        val first = registry.launches.single()
        compose.runOnIdle { registry.dispatchResult(first.code, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithText("Send next slip").performClick()
        compose.waitUntil { registry.launches.size == 2 }
        val second = registry.launches.last()
        assertNotEquals(first.code, second.code)
        compose.runOnIdle {
            registry.dispatchResult(first.code, Activity.RESULT_OK, null)
            assertEquals(1, (vm.uiState.value.printJob as PrintJobState.Launched).index)
            registry.dispatchResult(second.code, Activity.RESULT_CANCELED, null)
            assertTrue(vm.uiState.value.printJob is PrintJobState.Completed)
        }
        assertEquals(2, registry.launches.size)
    }

    @Test fun activityRecreationRestoresRegistryWithoutRelaunch() {
        start()
        val before = vm
        val first = registry.launches.single()
        val saved = Bundle()
        compose.runOnIdle { registry.onSaveInstanceState(saved) }
        compose.activityRule.scenario.recreate()
        val restored = RecordingRegistry()
        compose.activityRule.scenario.onActivity { activity ->
            restored.onRestoreInstanceState(saved)
            // Real registry queues this while its stable key has no callback registered.
            assertTrue(restored.dispatchResult(first.code, Activity.RESULT_OK, null))
            vm = ViewModelProvider(activity, factory())[ProxyGeneratorViewModel::class.java]
            assertSame(before, vm)
            install(activity, restored)
        }
        compose.onNodeWithText("Send next slip").assertExists()
        assertTrue(restored.launches.isEmpty())
        compose.onNodeWithText("Send next slip").performClick()
        compose.waitUntil { restored.launches.size == 1 }
        assertEquals(1, (vm.uiState.value.printJob as PrintJobState.Launched).index)
    }

    @Test fun rememberedTargetFailureFallsBackOnceAndChooserFailureIsVisible() {
        runBlocking {
            PrinterTargetStore.remember(compose.activity.application,
                ComponentName("missing.print.app", "missing.print.app.SendActivity"))
        }
        compose.waitUntil { vm.uiState.value.printerTarget != null }
        registry.failure = { throw ActivityNotFoundException("fixture rejects all launches") }
        compose.runOnIdle { vm.printCurrentCard() }
        compose.waitUntil { vm.uiState.value.printJob is PrintJobState.Failed }
        assertEquals(2, registry.launches.size)
        assertEquals(Intent.ACTION_SEND, registry.launches.first().intent.action)
        assertEquals(Intent.ACTION_CHOOSER, registry.launches.last().intent.action)
        compose.runOnIdle { assertNull(vm.uiState.value.printerTarget) }
        compose.onNodeWithText("No app could open this slip.").assertExists()
    }

    @Test fun newProcessDoesNotReplayRestoredJobOrAcceptItsCallback() {
        start()
        val first = registry.launches.single()
        val oldId = (vm.uiState.value.printJob as PrintJobState.Launched).jobId
        val saved = Bundle()
        val restored = RecordingRegistry()
        compose.activityRule.scenario.onActivity { activity ->
            registry.onSaveInstanceState(saved)
            activity.viewModelStore.clear()
            vm = ViewModelProvider(activity, factory())[ProxyGeneratorViewModel::class.java]
            restored.onRestoreInstanceState(saved)
            install(activity, restored)
        }
        compose.runOnIdle {
            assertEquals(PrintJobState.Idle, vm.uiState.value.printJob)
            assertTrue(restored.launches.isEmpty())
            vm.fetchCardByName("Fresh session")
        }
        compose.waitUntil { vm.uiState.value.canPrint }
        compose.runOnIdle { vm.printCurrentCard() }
        compose.waitUntil { restored.launches.size == 1 }
        compose.runOnIdle {
            val current = vm.uiState.value.printJob as PrintJobState.Launched
            assertNotEquals(oldId, current.jobId)
            restored.dispatchResult(first.code, Activity.RESULT_OK, null)
            assertSame(current, vm.uiState.value.printJob)
        }
    }

    private fun start() {
        compose.runOnIdle { vm.printCurrentCard() }
        compose.waitUntil { registry.launches.isNotEmpty() }
    }

    private fun install(activity: ComponentActivity, targetRegistry: ActivityResultRegistry) {
        activity.setContent {
            val state by vm.uiState.collectAsState()
            MaterialTheme {
                PrintDispatchHost(vm, targetRegistry)
                PrintBar(state, vm::printCurrentCard, vm::sendNextSlip, vm::stopPrinting)
            }
        }
    }

    private fun factory() = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
            ProxyGeneratorViewModel(compose.activity.application as Application,
                CardRepository(object : ScryfallApiService {
                    override suspend fun getRandomCard(query: String?) = card()
                    override suspend fun getCardByName(fuzzy: String) = card()
                }), object : ArtSource {
                    override suspend fun fetch(url: String): ArtResult = ArtResult.Success(Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888))
                }, object : SlipRenderer {
                    override suspend fun render(plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float) =
                        plan.map { PrintSlip(Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888),
                            it.name, it.faceIndex, it.totalSlips, it.label) }
                }, object : SlipExporter {
                    override suspend fun export(jobId: String, slips: List<PrintSlip>) =
                        ExportedSlips(jobId, slips.indices.map { "content://fixture/$jobId/slip_$it.png" })
                    override suspend fun discardUnshared(batch: ExportedSlips) = Unit
                })
        )!!
    }

    private fun card() = ScryfallCard(name = "Two faces", layout = "transform",
        card_faces = listOf(CardFace(name = "Front", image_uris = ImageUris(artCrop = "fixture://front")),
            CardFace(name = "Back", image_uris = ImageUris(artCrop = "fixture://back"))))

    private data class Launch(val code: Int, val intent: Intent, val contract: ActivityResultContract<*, *>)
    private class RecordingRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Launch>()
        var failure: (() -> Unit)? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, input as Intent, contract)
            failure?.invoke()
        }
    }
}
