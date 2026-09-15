package com.example.snapstoneprinter.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.model.CardFace
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardStatsRenderingTest {
    private val baseCard = ScryfallCard(name = "Stats probe", type_line = "Permanent", oracle_text = "Rules.")
    private val baseFace = CardFace(name = "Face", type_line = "Permanent", oracle_text = "Rules.")

    private val statFaces = listOf(
        baseFace.copy(power = "0", toughness = "*"),
        baseFace.copy(loyalty = "3"),
        baseFace.copy(defense = "0")
    )

    @Test
    fun primaryStatsAllocateAndDrawEachKind() {
        val empty = ImageProcessor.composePrintSlips(baseCard).single().bitmap
        for (stats in statFaces) {
            val card = baseCard.copy(power = stats.power, toughness = stats.toughness,
                loyalty = stats.loyalty, defense = stats.defense)
            assertAddedStatInk(empty, ImageProcessor.composePrintSlips(card).single().bitmap)
        }
    }

    @Test
    fun secondaryStatsAllocateAndDrawEachKind() {
        val base = baseCard.copy(layout = "split", card_faces = listOf(baseFace, baseFace.copy(name = "Other")))
        val empty = ImageProcessor.composePrintSlips(base).single().bitmap
        for (stats in statFaces) {
            val card = base.copy(card_faces = listOf(baseFace, stats.copy(name = "Other")))
            assertAddedStatInk(empty, ImageProcessor.composePrintSlips(card).single().bitmap)
        }
    }

    @Test
    fun multiSlipStatsStayOnOwningFace() {
        val front = baseFace.copy(image_uris = ImageUris(artCrop = "https://example.com/front"))
        val back = baseFace.copy(name = "Back", image_uris = ImageUris(artCrop = "https://example.com/back"))
        val base = baseCard.copy(layout = "transform", card_faces = listOf(front, back))
        val empty = ImageProcessor.composePrintSlips(base)
        for (stats in statFaces) {
            val changedBack = back.copy(power = stats.power, toughness = stats.toughness,
                loyalty = stats.loyalty, defense = stats.defense)
            val changed = ImageProcessor.composePrintSlips(base.copy(card_faces = listOf(front, changedBack)))
            assertEquals(2, changed.size)
            assertTrue("Other face's statistics must leave the front unchanged",
                empty[0].bitmap.sameAs(changed[0].bitmap))
            assertAddedStatInk(empty[1].bitmap, changed[1].bitmap)
        }
    }

    @Test
    fun absentStatsHaveNoAllocatedOrDrawnLine() {
        val empty = ImageProcessor.composePrintSlips(baseCard).single().bitmap
        val blank = ImageProcessor.composePrintSlips(baseCard.copy(power = "", toughness = "",
            loyalty = " ", defense = "")).single().bitmap
        assertTrue(empty.sameAs(blank))
    }

    private fun assertAddedStatInk(empty: Bitmap, populated: Bitmap) {
        assertEquals(ImageProcessor.OUTPUT_WIDTH, populated.width)
        assertTrue("A stat line must receive vertical space", populated.height > empty.height)
        val startY = empty.height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX - 12
        val endY = populated.height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX - 12
        val pixels = IntArray(populated.width * (endY - startY))
        populated.getPixels(pixels, 0, populated.width, 0, startY, populated.width, endY - startY)
        assertTrue("The additional stat area must contain drawn ink", pixels.any { it != Color.WHITE })
        val feed = IntArray(populated.width * ImageProcessor.TRAILING_FEED_WHITESPACE_PX)
        populated.getPixels(feed, 0, populated.width, 0,
            populated.height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX,
            populated.width, ImageProcessor.TRAILING_FEED_WHITESPACE_PX)
        assertTrue("Statistics must leave the feed band white", feed.all { it == Color.WHITE })
    }
}
