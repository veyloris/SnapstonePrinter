package com.example.snapstoneprinter.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.CardFace
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.print.PrintJobState
import com.example.snapstoneprinter.data.print.PrinterTargetStore
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.image.ArtResult
import com.example.snapstoneprinter.image.ArtSource
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipRenderer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PrintExternalReceiverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun externalReceiverReadsCorrectPngAndWaitsForExplicitNext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val component = ComponentName(instrumentation.context.packageName,
            "com.example.snapstoneprinter.ui.PrintReceiverActivity")
        val application = compose.activity.application
        runBlocking { PrinterTargetStore.remember(application, component) }
        lateinit var vm: ProxyGeneratorViewModel
        val bitmaps = listOf(Color.BLACK, Color.WHITE).map { color ->
            Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        try {
            compose.activityRule.scenario.onActivity { activity ->
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                        ProxyGeneratorViewModel(application, CardRepository(object : ScryfallApiService {
                            override suspend fun getRandomCard(query: String?) = card()
                            override suspend fun getCardByName(fuzzy: String) = card()
                        }), object : ArtSource {
                            override suspend fun fetch(url: String): ArtResult = ArtResult.Success(bitmaps.first())
                        }, object : SlipRenderer {
                            override suspend fun render(plan: List<SlipContent>, art: List<Bitmap?>, contrast: Float, brightness: Float) =
                                plan.mapIndexed { index, slip -> PrintSlip(bitmaps[index], slip.name,
                                    slip.faceIndex, slip.totalSlips, slip.label) }
                        })
                    )!!
                }
                vm = ViewModelProvider(activity, factory)[ProxyGeneratorViewModel::class.java]
                vm.fetchCardByName("Fixture")
            }
            compose.setContent { Host(vm) }
            compose.waitUntil { vm.uiState.value.canPrint && vm.uiState.value.printerTarget?.component == component }
            compose.runOnIdle { assertEquals(2, vm.uiState.value.slips.size) }
            compose.runOnIdle { vm.printCurrentCard() }
            for (index in bitmaps.indices) {
                // Advance Compose's test clock while asynchronous export makes the host Ready.
                compose.waitUntil(15_000) {
                    val job = vm.uiState.value.printJob
                    job is PrintJobState.Launched && job.index == index
                }
                val receipt = JSONObject(awaitNode { it.contentDescription == "print-receiver-receipt" }.text.toString())
                assertTrue(receipt.toString(), receipt.isNull("error"))
                assertEquals("android.intent.action.SEND", receipt.getString("action"))
                assertEquals("image/png", receipt.getString("mime"))
                assertTrue(receipt.getBoolean("urisMatch"))
                assertEquals(1, receipt.getInt("clipCount"))
                assertTrue(receipt.getBoolean("readGrant"))
                assertFalse(receipt.getBoolean("writeGrant"))
                assertNotEquals(Process.myUid(), receipt.getInt("uid"))
                assertEquals(3, receipt.getInt("width"))
                assertEquals(2, receipt.getInt("height"))
                assertEquals(index, receipt.getInt("slipIndex"))
                assertEquals(hash(bitmaps[index]), receipt.getString("pixelSha256"))
                if (index == 0) {
                    val retained = vm
                    val token = (vm.uiState.value.printJob as PrintJobState.Launched).token
                    compose.activityRule.scenario.recreate()
                    compose.activityRule.scenario.onActivity { activity ->
                        vm = ViewModelProvider(activity)[ProxyGeneratorViewModel::class.java]
                        assertSame(retained, vm)
                        activity.setContent { Host(vm) }
                    }
                    val afterRecreation = JSONObject(awaitNode {
                        it.contentDescription == "print-receiver-receipt"
                    }.text.toString())
                    assertEquals(receipt.getString("activityInstance"), afterRecreation.getString("activityInstance"))
                    assertEquals(token, (vm.uiState.value.printJob as PrintJobState.Launched).token)
                } else {
                    instrumentation.runOnMainSync {
                        val job = vm.uiState.value.printJob as PrintJobState.Launched
                        vm.stopPrinting(job.jobId)
                        assertTrue(vm.uiState.value.printJob is PrintJobState.Stopping)
                        assertFalse(vm.tryReprint(vm.uiState.value.history.single()))
                    }
                    assertTrue(awaitNode { it.text == "Read URI again" }
                        .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    val reread = JSONObject(awaitNode {
                        it.contentDescription == "print-receiver-receipt" &&
                            JSONObject(it.text.toString()).getInt("readCount") == 2
                    }.text.toString())
                    assertTrue(reread.toString(), reread.isNull("error"))
                    assertEquals(receipt.getString("activityInstance"), reread.getString("activityInstance"))
                    assertEquals(receipt.getString("streamUri"), reread.getString("streamUri"))
                    assertEquals(hash(bitmaps[index]), reread.getString("pixelSha256"))
                    assertEquals(3, reread.getInt("width"))
                    assertEquals(2, reread.getInt("height"))
                }
                assertTrue(awaitNode { it.text == if (index == 0) "Return Cancel" else "Return OK" }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                if (index == 0) {
                    compose.onNodeWithText("Continue with slip 2 of 2?").assertExists()
                    compose.runOnIdle { assertTrue(vm.uiState.value.printJob is PrintJobState.AwaitingNext) }
                    assertNull(node(instrumentation.uiAutomation.rootInActiveWindow) {
                        it.contentDescription == "print-receiver-receipt"
                    })
                    compose.onNodeWithText("Send next slip").performClick()
                }
            }
            compose.waitUntil { vm.uiState.value.printJob is PrintJobState.Cancelled }
        } finally {
            node(instrumentation.uiAutomation.rootInActiveWindow) { it.text == "Return Cancel" }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            runBlocking { PrinterTargetStore.forget(application) }
        }
    }

    @Composable private fun Host(vm: ProxyGeneratorViewModel) {
        val state by vm.uiState.collectAsState()
        MaterialTheme {
            PrintDispatchHost(vm)
            PrintBar(state, vm::printCurrentCard, vm::sendNextSlip, vm::stopPrinting)
        }
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            node(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected receiver accessibility node within 15 seconds")
    }
    private fun node(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) node(root.getChild(index), predicate)?.let { return it }
        return null
    }
    private fun hash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            digest.update(byteArrayOf((pixel ushr 24).toByte(), (pixel ushr 16).toByte(), (pixel ushr 8).toByte(), pixel.toByte()))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun card() = ScryfallCard(name = "Fixture", layout = "transform",
        card_faces = listOf(CardFace(name = "Front", image_uris = ImageUris(artCrop = "fixture://front")),
            CardFace(name = "Back", image_uris = ImageUris(artCrop = "fixture://back"))))
}
