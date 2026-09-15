package com.example.snapstoneprinter.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.image.ImageProcessor
import com.example.snapstoneprinter.image.PrintSlip
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Layout regressions for [ProxyPreview].
 *
 * Two historical bugs are pinned here:
 *
 * 1. THE TINY PREVIEW. The slip used to be drawn with `wrapContentHeight()` + `ContentScale.Fit`,
 *    which capped the height at the bitmap's intrinsic 384 px and therefore drew a 384x1100 slip
 *    at roughly 128 dp wide - about 0.8 inch on a 480 dpi phone. The preview must now fill the
 *    width it is given while preserving the bitmap's aspect ratio.
 *
 * 2. THE INFINITE-HEIGHT CRASH. Rendering the preview with `scrollContent = true` underneath an
 *    already-scrollable ancestor threw
 *    `IllegalStateException: Vertically scrollable component was measured with an infinity maximum
 *    height constraints` from the measure pass, killing the activity. `scrollContent = false` must
 *    stay crash-free under an unbounded-height parent.
 *
 * 3. SMOOTHED DITHER. The slip is 1-bit Floyd-Steinberg output upscaled roughly 3x. Bilinear
 *    filtering would blend the black/white scatter into grey, which lies about what the thermal
 *    printer emits. [ProxyPreview] must sample nearest-neighbour.
 */
@RunWith(AndroidJUnit4::class)
class ProxyPreviewLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun slip(
        faceName: String,
        faceIndex: Int = 0,
        totalSlips: Int = 1,
        height: Int = SLIP_HEIGHT_PX
    ) = PrintSlip(
        bitmap = Bitmap.createBitmap(
            ImageProcessor.OUTPUT_WIDTH,
            height,
            Bitmap.Config.ARGB_8888
        ),
        faceName = faceName,
        faceIndex = faceIndex,
        totalSlips = totalSlips,
        label = null
    )

    @Test
    fun slipFillsAvailableWidthAndKeepsAspectRatio() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(CONTAINER_WIDTH)
                        .height(CONTAINER_HEIGHT)
                        .testTag("preview-container")
                ) {
                    ProxyPreview(
                        uiState = ProxyGeneratorUiState(slips = listOf(slip("Test Card"))),
                        scrollContent = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val bounds = composeTestRule
            .onNodeWithContentDescription("Generated proxy slip for Test Card")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        val widthDp = bounds.width.value
        val heightDp = bounds.height.value
        val containerWidthDp = composeTestRule
            .onNodeWithTag("preview-container")
            .getUnclippedBoundsInRoot()
            .width.value

        assertTrue("container and slip dimensions must be positive", containerWidthDp > 0f && widthDp > 0f && heightDp > 0f)
        // Compare against allocated width; the device may constrain the requested container size.
        assertTrue(
            "Slip width was $widthDp dp inside a $containerWidthDp dp container - the " +
                "preview is not filling the available width",
            widthDp >= containerWidthDp * 0.8f && widthDp <= containerWidthDp
        )

        // Aspect ratio must come from the bitmap, not from the viewport.
        val expectedRatio = SLIP_HEIGHT_PX.toFloat() / ImageProcessor.OUTPUT_WIDTH.toFloat()
        val actualRatio = heightDp / widthDp
        assertTrue(
            "Expected height/width ~$expectedRatio but was $actualRatio " +
                "(${widthDp}x$heightDp dp)",
            abs(actualRatio - expectedRatio) < 0.05f
        )
    }

    @Test
    fun multiSlipPagerShowsIndicatorForEachSlip() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(CONTAINER_WIDTH)
                        .height(CONTAINER_HEIGHT)
                ) {
                    ProxyPreview(
                        uiState = ProxyGeneratorUiState(
                            slips = listOf(
                                slip("Front Face", faceIndex = 0, totalSlips = 2),
                                slip("Back Face", faceIndex = 1, totalSlips = 2)
                            )
                        ),
                        scrollContent = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText("Slip 1 of 2 \u2014 Front Face")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Swipe \u2014 this card needs 2 printed slips")
            .assertIsDisplayed()
    }

    /**
     * The nested-scroll crash guard. `scrollContent = false` must measure cleanly when the parent
     * hands down an infinite maximum height.
     */
    @Test
    fun unboundedHeightParentDoesNotCrash() {
        composeTestRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .width(CONTAINER_WIDTH)
                        .height(CONTAINER_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    ProxyPreview(
                        uiState = ProxyGeneratorUiState(
                            slips = listOf(
                                slip("Front Face", faceIndex = 0, totalSlips = 2),
                                slip("Back Face", faceIndex = 1, totalSlips = 2)
                            )
                        ),
                        scrollContent = false,
                        modifier = Modifier.width(CONTAINER_WIDTH)
                    )
                }
            }
        }

        // Reaching an assertion at all means the measure pass survived.
        composeTestRule
            .onNodeWithContentDescription("Generated proxy slip for Front Face")
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription("Generated proxy slip for Back Face")
            .assertExists()
    }

    /**
     * NEAREST-NEIGHBOUR PROOF.
     *
     * Feeds the preview a pure black/white vertical-stripe bitmap - the worst case for a smoothing
     * filter - upscaled by roughly two orders of magnitude, then reads the rendered pixels back.
     *
     * With `FilterQuality.None` every output pixel is a verbatim copy of a source pixel, so the
     * render contains ONLY pure black and pure white. With the default bilinear filtering the
     * stripes blur into a continuous ramp and the overwhelming majority of pixels land in the grey
     * midtones. Asserting "almost no midtones" therefore fails loudly if anyone swaps the filter.
     *
     * Measured on a Pixel 10 Pro XL emulator, API 36, over 848,241 sampled pixels:
     *  - `FilterQuality.None` (current): 0 midtone pixels, 0.00%
     *  - `FilterQuality.Low`  (bilinear): 605,097 midtone pixels, 71.34%
     *
     * The gap is three orders of magnitude, so the 0.5% threshold below has plenty of teeth while
     * tolerating GPU-to-GPU rasterisation differences.
     */
    @Test
    fun ditherIsRenderedNearestNeighbourNotSmoothedToGrey() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(CONTAINER_WIDTH)
                        .height(CONTAINER_HEIGHT)
                ) {
                    ProxyPreview(
                        uiState = ProxyGeneratorUiState(slips = listOf(stripeSlip())),
                        scrollContent = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val pixels = composeTestRule
            .onNodeWithContentDescription("Generated proxy slip for Stripes")
            .captureToImage()
            .toPixelMap()

        // Stay clear of the rounded-corner clip, which is legitimately anti-aliased.
        val xRange = (pixels.width * 0.1f).toInt() until (pixels.width * 0.9f).toInt()
        val yRange = (pixels.height * 0.1f).toInt() until (pixels.height * 0.9f).toInt()

        var sampled = 0
        var midtones = 0
        for (y in yRange) {
            for (x in xRange) {
                val luminance = pixels[x, y].green
                sampled++
                if (luminance > 0.15f && luminance < 0.85f) midtones++
            }
        }

        assertTrue("Captured no pixels to inspect", sampled > 0)
        val midtoneFraction = midtones.toFloat() / sampled
        assertTrue(
            "$midtones of $sampled sampled pixels (${midtoneFraction * 100}%) are grey midtones. " +
                "A 1-bit black/white source rendered nearest-neighbour must stay black/white - " +
                "this means a smoothing filterQuality crept back into ProxyPreview.",
            midtoneFraction < 0.005f
        )
    }

    /** Pure black / pure white vertical stripes, one pixel wide. */
    private fun stripeSlip(): PrintSlip {
        val bitmap = Bitmap.createBitmap(
            STRIPE_BITMAP_SIZE,
            STRIPE_BITMAP_SIZE,
            Bitmap.Config.ARGB_8888
        )
        for (y in 0 until STRIPE_BITMAP_SIZE) {
            for (x in 0 until STRIPE_BITMAP_SIZE) {
                bitmap.setPixel(x, y, if (x % 2 == 0) Color.BLACK else Color.WHITE)
            }
        }
        return PrintSlip(
            bitmap = bitmap,
            faceName = "Stripes",
            faceIndex = 0,
            totalSlips = 1,
            label = null
        )
    }

    private companion object {
        val CONTAINER_WIDTH = 400.dp
        val CONTAINER_HEIGHT = 700.dp

        /** Small enough that the upscale factor is enormous, exaggerating any smoothing. */
        const val STRIPE_BITMAP_SIZE = 8

        /** Tall enough to be taller than the viewport, like a real slip. */
        const val SLIP_HEIGHT_PX = 1100
    }
}
