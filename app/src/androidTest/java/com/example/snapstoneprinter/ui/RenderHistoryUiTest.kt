package com.example.snapstoneprinter.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.image.PrintSlip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun renderFailureNoticeKeepsPreviewVisible() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ProxyPreview(
                        ProxyGeneratorUiState(slips = listOf(slip(Color.BLACK)), renderError = TONE_ERROR),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        compose.onNodeWithText(TONE_ERROR).assertIsDisplayed()
        compose.onNodeWithContentDescription("Generated proxy slip for Preview").assertIsDisplayed()
    }

    @Test
    fun fatalGenerationErrorStillUsesFatalPresentation() {
        compose.setContent {
            MaterialTheme {
                ProxyPreview(ProxyGeneratorUiState(error = "Fetch failed"), modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("Error").assertIsDisplayed()
        compose.onNodeWithText("Fetch failed").assertIsDisplayed()
    }

    @Test
    fun toneControlsShowNonfatalRenderNotice() {
        compose.setContent {
            MaterialTheme {
                InlineToneControls(
                    ProxyGeneratorUiState(renderError = TONE_ERROR),
                    onContrastChange = {}, onBrightnessChange = {}, onReset = {}
                )
            }
        }
        compose.onNodeWithText(TONE_ERROR).assertIsDisplayed()
    }

    @Test
    fun historySheetShowsLocalActionError() {
        compose.setContent {
            MaterialTheme {
                HistorySheet(listOf(entry(Color.BLACK)), onReprint = {}, onDismiss = {}, error = HISTORY_ERROR)
            }
        }
        compose.onNodeWithText(HISTORY_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Reprint").assertIsDisplayed()
    }

    @Test
    fun sameHistoryIdUpdatesThumbnail() {
        val current = mutableStateOf(entry(Color.RED))
        compose.setContent {
            MaterialTheme {
                HistorySheet(listOf(current.value), onReprint = {}, onDismiss = {})
            }
        }
        assertThumbnailCenter(Color.RED)
        compose.runOnIdle { current.value = entry(Color.BLUE) }
        assertThumbnailCenter(Color.BLUE)
    }

    private fun assertThumbnailCenter(expected: Int) {
        val pixels = compose.onNodeWithTag("history-thumbnail-7")
            .assertIsDisplayed().captureToImage().toPixelMap()
        assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    private fun entry(color: Int) = HistoryEntry(7L, "Preview", null, listOf(slip(color)))

    private fun slip(color: Int) = PrintSlip(
        Bitmap.createBitmap(384, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        "Preview", 0, 1, null
    )

    companion object {
        private const val TONE_ERROR = "Could not apply tone changes. Previous preview and settings kept."
        private const val HISTORY_ERROR = "This item is unavailable or busy. Wait for current work to finish and try again."
    }
}
