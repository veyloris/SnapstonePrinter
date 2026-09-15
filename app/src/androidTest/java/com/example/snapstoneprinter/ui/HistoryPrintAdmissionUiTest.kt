package com.example.snapstoneprinter.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryPrintAdmissionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realHistoryCallbackKeepsRejectedSheetOpenAndClosesAcceptedSheet() {
        val toneResult = CompletableDeferred<Unit>()
        val exportResult = CompletableDeferred<ExportedSlips>()
        var holdTone = false
        var exportCalls = 0
        lateinit var vm: ProxyGeneratorViewModel
        compose.activityRule.scenario.onActivity { activity ->
            val factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                    ProxyGeneratorViewModel(activity.application, CardRepository(object : ScryfallApiService {
                        override suspend fun getRandomCard(query: String?) = ScryfallCard(name = "History fixture")
                        override suspend fun getCardByName(fuzzy: String) = ScryfallCard(name = fuzzy)
                    }), object : ArtSource {
                        override suspend fun fetch(url: String): ArtResult = error("Unexpected art fetch")
                    }, object : SlipRenderer {
                        override suspend fun render(plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float): List<PrintSlip> {
                            if (holdTone) toneResult.await()
                            return plan.map { PrintSlip(Bitmap.createBitmap(384, 48, Bitmap.Config.ARGB_8888),
                                it.name, it.faceIndex, it.totalSlips, it.label) }
                        }
                    }, object : SlipExporter {
                        override suspend fun export(jobId: String, slips: List<PrintSlip>): ExportedSlips {
                            exportCalls++
                            return exportResult.await()
                        }
                        override suspend fun discardUnshared(batch: ExportedSlips) = Unit
                    })
                )!!
            }
            vm = ViewModelProvider(activity, factory)[ProxyGeneratorViewModel::class.java]
            vm.fetchCardByName("History fixture")
        }
        try {
            compose.setContent { MaterialTheme { ProxyGeneratorScreen(vm) } }
            compose.waitUntil { vm.uiState.value.canPrint }
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Session history (1)").performClick()
            compose.onNodeWithText("This session").assertIsDisplayed()
            compose.runOnIdle {
                holdTone = true
                vm.setBrightness(20f)
                assertTrue(vm.uiState.value.isRedithering)
            }
            compose.onNodeWithText("Reprint").performClick()
            compose.onNodeWithText("This item is unavailable or busy. Wait for current work to finish and try again.")
                .assertIsDisplayed()
            compose.onNodeWithText("This session").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, exportCalls) }

            toneResult.complete(Unit)
            compose.waitUntil { vm.uiState.value.canPrint }
            compose.onNodeWithText("Reprint").performClick()
            compose.onNodeWithText("This session").assertDoesNotExist()
            compose.waitUntil { exportCalls == 1 }
            compose.runOnIdle { assertTrue(vm.uiState.value.printJob is PrintJobState.Preparing) }
        } finally {
            toneResult.cancel()
            exportResult.cancel()
        }
    }
}
