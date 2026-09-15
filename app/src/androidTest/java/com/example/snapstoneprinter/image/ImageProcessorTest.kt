package com.example.snapstoneprinter.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.data.model.CardFace
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.util.ManaCostFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ImageProcessorTest {

    @Test
    fun legacyCompositionRejectsUnpreparedArtWidth() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ImageProcessor.compositeCardProxy(pixelCard(), checkerboard(600, 7))
        }
        assertTrue(exception.message.orEmpty().contains("width"))
        assertTrue(exception.message.orEmpty().contains("360"))
    }

    @Test
    fun cardCompositionRejectsUnpreparedArtWidth() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageProcessor.composePrintSlips(pixelCard(), listOf(checkerboard(600, 7)))
        }
    }

    @Test
    fun planCompositionRejectsUnpreparedArtWidth() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageProcessor.composeSlips(SlipPlanner.plan(pixelCard()), listOf(checkerboard(600, 7)))
        }
    }

    @Test
    fun preparedPatternSurvivesCompositionAndPngExactly() {
        val pattern = checkerboard(360, 7)
        val original = pattern.copy(Bitmap.Config.ARGB_8888, false)!!
        val slip = ImageProcessor.composePrintSlips(pixelCard(), listOf(pattern)).single().bitmap
        assertPatternAndMargins(slip, pattern)
        assertTrue("composition must not modify input art", pattern.sameAs(original))
        assertFalse("composition must not recycle input art", pattern.isRecycled)

        val png = ByteArrayOutputStream().use { out ->
            assertTrue(slip.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)!!
        assertPatternAndMargins(decoded, pattern)
    }

    private fun pixelCard() = ScryfallCard(name = "Art", type_line = "", layout = "normal")

    private fun checkerboard(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
                }
            }
        }

    private fun pixelCardArtTop(): Int {
        // Match the fixture's specified single title row and empty type row, not image pixels.
        fun rowHeight(text: String, size: Float, style: Int): Int {
            val paint = TextPaint().apply {
                textSize = size
                typeface = Typeface.create(Typeface.SANS_SERIF, style)
                isAntiAlias = true
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, 360).build().height
        }
        return 12 + rowHeight("Art", 24f, Typeface.BOLD) + 8 +
            rowHeight("", 18f, Typeface.NORMAL) + 12
    }

    private fun assertPatternAndMargins(slip: Bitmap, pattern: Bitmap) {
        assertEquals(384, slip.width)
        val top = pixelCardArtTop()
        for (y in 0 until pattern.height) {
            for (x in 0 until slip.width) {
                val actual = slip.getPixel(x, top + y)
                val expected = if (x in 12 until 372) pattern.getPixel(x - 12, y) else Color.WHITE
                assertEquals("art/margin pixel ($x, ${top + y})", expected, actual)
                assertTrue("art must be binary", actual == Color.BLACK || actual == Color.WHITE)
            }
        }
        for (y in slip.height - 48 until slip.height) {
            for (x in 0 until slip.width) assertEquals(Color.WHITE, slip.getPixel(x, y))
        }
    }

    @Test
    fun testApplyFloydSteinbergDithering() {
        val src = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.GRAY)

        val dithered = ImageProcessor.applyFloydSteinbergDithering(src)
        assertNotNull(dithered)
        assertEquals(src.width, dithered.width)
        assertEquals(src.height, dithered.height)

        val pixels = IntArray(100)
        dithered.getPixels(pixels, 0, 10, 0, 0, 10, 10)
        // assertTrue, never the bare Kotlin `assert(...)` intrinsic: that one is compiled out
        // unless the JVM is started with -ea, which instrumentation runs are not. Every `assert`
        // in an androidTest is dead code that silently passes.
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue("red channel must be pure black or white, got $r", r == 0 || r == 255)
            assertTrue("green channel must be pure black or white, got $g", g == 0 || g == 255)
            assertTrue("blue channel must be pure black or white, got $b", b == 0 || b == 255)
        }
    }

    @Test
    fun testDitheringWithContrastAndBrightness() {
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.DKGRAY)

        val dithered = ImageProcessor.applyFloydSteinbergDithering(
            src = src,
            contrast = 1.5f,
            brightness = 30f
        )
        assertEquals(8, dithered.width)
        assertEquals(8, dithered.height)

        val pixels = IntArray(64)
        dithered.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            assertTrue("red channel must be pure black or white, got $r", r == 0 || r == 255)
        }
    }

    @Test
    fun testDarkGradientDoesNotCollapseToSolidBlack() {
        // A very dark gradient: pre-auto-levels this dithered into an illegible black smear.
        val width = 64
        val height = 64
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (x * 20) / width // 0..19 out of 255
                src.setPixel(x, y, Color.rgb(v, v, v))
            }
        }

        val dithered = ImageProcessor.applyFloydSteinbergDithering(src)
        val pixels = IntArray(width * height)
        dithered.getPixels(pixels, 0, width, 0, 0, width, height)

        val whiteCount = pixels.count { (it shr 16 and 0xFF) == 255 }
        val blackCount = pixels.count { (it shr 16 and 0xFF) == 0 }
        assertEquals(
            "dithering must emit only pure black or pure white",
            pixels.size.toLong(),
            (whiteCount + blackCount).toLong()
        )

        val whiteRatio = whiteCount.toFloat() / pixels.size
        val detail = "$whiteCount white / $blackCount black of ${pixels.size}, ratio=$whiteRatio"

        // Threshold is measured, not guessed. Auto-levels stretches this 0..19 ramp across the
        // full 0..255 range and Floyd-Steinberg preserves the mean, so the CORRECT pipeline emits
        // 2026 / 4096 = 49.5% white. Removing auto-levels (the regression this test exists to
        // catch) leaves the raw dark ramp and emits only 131 / 4096 = 3.2% white - a near-solid
        // black smear. A 30%..70% acceptance band sits ~19pp below the good value and ~27pp above
        // the regression: loose enough to survive legitimate tone-mapping tweaks, tight enough
        // that a black smear (or a blown-out white page) genuinely fails.
        assertTrue(
            "auto-levels should recover detail: expected white ratio > 0.30, got $detail",
            whiteRatio > 0.30f
        )
        assertTrue(
            "image should not collapse to black: expected black ratio < 0.70, got $detail",
            blackCount < pixels.size * 0.70f
        )
        assertTrue(
            "image should not blow out to white: expected white ratio < 0.70, got $detail",
            whiteRatio < 0.70f
        )
    }

    @Test
    fun testCompositeCardProxy() {
        val card = ScryfallCard(
            name = "Colossal Dreadmaw",
            mana_cost = "{4}{G}{G}",
            type_line = "Creature — Dinosaur",
            oracle_text = "Trample\nWhen Colossal Dreadmaw enters the battlefield, it threatens your opponents.",
            power = "6",
            toughness = "6",
            image_uris = null,
            layout = "normal"
        )

        val dummyArt = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        dummyArt.eraseColor(Color.LTGRAY)

        val composite = ImageProcessor.compositeCardProxy(card, dummyArt)
        assertNotNull(composite)
        // Immutable spec: output width is always exactly 384 pixels.
        assertEquals(384, composite.width)
        assertEquals(ImageProcessor.OUTPUT_WIDTH, composite.width)

        // Deliberately tightened from the original `> 100`, which was weak enough to pass even if
        // the art and every line of text had been dropped. The bound below is derived from the
        // spec rather than guessed: the 100x100 art is scaled to the full content width
        // (384 - 2*12 padding = 360px) and, being square, contributes 360px of height on its own;
        // every slip then carries a 48px tear-off feed band. So a correct render cannot be
        // shorter than 408px plus the title/type/oracle/PT text.
        val contentWidth = ImageProcessor.OUTPUT_WIDTH - (12 * 2)
        val scaledArtHeight = contentWidth // square source art
        val minHeight = scaledArtHeight + ImageProcessor.TRAILING_FEED_WHITESPACE_PX
        assertTrue(
            "expected height > $minHeight (${scaledArtHeight}px scaled art + " +
                "${ImageProcessor.TRAILING_FEED_WHITESPACE_PX}px feed + text), " +
                "got ${composite.height}",
            composite.height > minHeight
        )

        // The mana cost is appended to the title line as plain text, so a formatting failure must
        // still leave ink on the page rather than aborting composition.
        assertTrue("composed proxy must contain rendered ink", contentInk(composite) > 500)
    }

    /**
     * A transform-layout card exactly as Scryfall returns one: every top-level data field is null
     * and the real data lives in `card_faces[]`. Only one face carries art, so this deliberately
     * goes down the single-slip `effective*` fallback path of [ImageProcessor.compositeCardProxy].
     *
     * [manaCost] and [oracleText] are the only variables, so any pixel difference between two
     * renders is attributable to the FACE data and nothing else.
     */
    private fun singleFaceDfc(manaCost: String?, oracleText: String?) = ScryfallCard(
        name = "Delver of Secrets // Insectile Aberration",
        mana_cost = null,
        type_line = null,
        oracle_text = null,
        power = null,
        toughness = null,
        image_uris = null,
        layout = "transform",
        card_faces = listOf(
            CardFace(
                name = "Delver of Secrets",
                mana_cost = manaCost,
                type_line = "Creature — Human Wizard",
                oracle_text = oracleText,
                power = "1",
                toughness = "1",
                image_uris = ImageUris(artCrop = "https://img/front.jpg")
            )
        )
    )

    /**
     * Number of non-white (ink) pixels in the composed content area, i.e. everything above the
     * trailing tear-off feed band. All fixtures below render text-only (null art), so every ink
     * pixel counted here is glyph ink.
     */
    private fun contentInk(bmp: Bitmap, fromY: Int = 0): Int {
        val toY = bmp.height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX
        val rows = toY - fromY
        if (rows <= 0) return 0
        val pixels = IntArray(bmp.width * rows)
        bmp.getPixels(pixels, 0, bmp.width, 0, fromY, bmp.width, rows)
        return pixels.count { it != Color.WHITE }
    }

    @Test
    fun testCompositeCardProxy_doubleFacedCardRendersFaceData() {
        val oracle = "At the beginning of your upkeep, look at the top card of your library."
        val full = ImageProcessor.compositeCardProxy(singleFaceDfc("{U}", oracle), null)
        val noMana = ImageProcessor.compositeCardProxy(singleFaceDfc(null, oracle), null)
        val noOracle = ImageProcessor.compositeCardProxy(singleFaceDfc("{U}", null), null)

        assertEquals(384, full.width)

        // 1. Ink is actually present in the text region. If the renderer silently drew nothing
        //    but a white page, everything below would still "pass" on height alone.
        val fullInk = contentInk(full)
        assertTrue(
            "composed slip must contain rendered text, got $fullInk ink pixels",
            fullInk > 500
        )

        // 2. Ink is present in the LOWER HALF of the content area specifically - that is where the
        //    face oracle text and P/T land, below the title/type header (and below the art when
        //    art is supplied). Proves the oracle block is not being dropped off the bottom.
        val contentHeight = full.height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX
        val lowerInk = contentInk(full, fromY = contentHeight / 2)
        assertTrue(
            "oracle text / P-T region below the header must contain ink, got $lowerInk pixels",
            lowerInk > 200
        )

        // 3. The face's ORACLE TEXT really drives the render. Top-level oracle_text is null, so if
        //    the renderer ever stopped reading card_faces[0] these two would be pixel-identical.
        assertFalse(
            "per-face oracle text must change the render (top-level oracle_text is null)",
            full.sameAs(noOracle)
        )
        val noOracleInk = contentInk(noOracle)
        assertTrue(
            "face oracle text must add a body of ink (with=$fullInk, without=$noOracleInk)",
            fullInk > noOracleInk + 300
        )
        assertTrue(
            "face oracle text must add height (with=${full.height}, without=${noOracle.height})",
            full.height > noOracle.height
        )

        // 4. The face's MANA COST really drives the render. It is appended to the title line, so it
        //    adds ink without necessarily adding height - height alone could never detect this.
        assertFalse(
            "per-face mana cost must change the render (top-level mana_cost is null)",
            full.sameAs(noMana)
        )
        val noManaInk = contentInk(noMana)
        assertTrue(
            "face mana cost must add ink to the title line (with=$fullInk, without=$noManaInk)",
            fullInk > noManaInk + 20
        )
    }

    @Test
    fun testComposePrintSlips_eachSlipRendersItsOwnFaceOracleAndMana() {
        val base = transformCard()
        val baseFaces = base.card_faces!!
        // Identical card except that ONLY card_faces[1]'s mana cost and oracle text change.
        val backChanged = base.copy(
            card_faces = listOf(
                baseFaces[0],
                baseFaces[1].copy(
                    mana_cost = "{3}{R}{R}",
                    oracle_text = "Menace, haste and first strike. Whenever this creature " +
                        "attacks, draw a card and each opponent loses two life."
                )
            )
        )

        // Text-only slips, so every pixel difference is attributable to the face data.
        val baseSlips = ImageProcessor.composePrintSlips(base)
        val changedSlips = ImageProcessor.composePrintSlips(backChanged)
        assertEquals(2, baseSlips.size)
        assertEquals(2, changedSlips.size)

        // The FRONT slip must be byte-identical: back-face data must never leak into slip 0.
        assertTrue(
            "front slip must not change when only card_faces[1] oracle/mana change",
            baseSlips[0].bitmap.sameAs(changedSlips[0].bitmap)
        )

        // The BACK slip must differ: proves slip 1 was composed from card_faces[1]'s OWN oracle
        // text and mana cost rather than from shared top-level or front-face data.
        assertFalse(
            "back slip must re-render when card_faces[1] oracle/mana change",
            baseSlips[1].bitmap.sameAs(changedSlips[1].bitmap)
        )

        val baseInk = contentInk(baseSlips[1].bitmap)
        val changedInk = contentInk(changedSlips[1].bitmap)
        assertTrue(
            "longer back-face oracle text must add ink (was $baseInk, now $changedInk)",
            changedInk > baseInk + 300
        )

        // And the two faces of the SAME card must not render the same text either.
        val frontInk = contentInk(baseSlips[0].bitmap)
        assertTrue("front slip must contain rendered text, got $frontInk ink pixels", frontInk > 500)
        assertTrue("back slip must contain rendered text, got $baseInk ink pixels", baseInk > 200)
    }

    /**
     * Mana-cost formatting is cosmetic and must never be load-bearing.
     *
     * The P0 this guards against was an ICU-invalid regex in [ManaCostFormatter]'s initialiser that
     * made `renderSlip` throw for EVERY card, whatever the mana cost was. These fixtures push
     * hostile / malformed `mana_cost` values all the way through the real render path on-device and
     * require a usable bitmap out the other end.
     */
    @Test
    fun testRenderIsResilientToHostileManaCosts() {
        val hostile = listOf(
            "{2}{U}{U}", "{W/U}", "{U/P}", "{X}", "{1}{G} // {3}{R}",
            "", "   ", "{", "}", "{{", "}}", "{}", "{unclosed", "no braces at all"
        )

        for (cost in hostile) {
            val card = ScryfallCard(
                name = "Resilience Probe",
                mana_cost = cost,
                type_line = "Instant",
                oracle_text = "Draw a card.",
                layout = "normal"
            )

            val composite = ImageProcessor.compositeCardProxy(card, null)
            assertNotNull("render must not return null for mana_cost=\"$cost\"", composite)
            assertEquals(
                "width must stay 384 for mana_cost=\"$cost\"",
                ImageProcessor.OUTPUT_WIDTH,
                composite.width
            )
            assertTrue(
                "render must still draw the card text for mana_cost=\"$cost\"",
                contentInk(composite) > 300
            )
        }
    }

    /** A null mana cost must render exactly like a blank one - no crash, no stray separator. */
    @Test
    fun testRenderHandlesNullManaCost() {
        fun probe(cost: String?) = ImageProcessor.compositeCardProxy(
            ScryfallCard(
                name = "Null Cost",
                mana_cost = cost,
                type_line = "Land",
                oracle_text = "Tap for one mana.",
                layout = "normal"
            ),
            null
        )

        val nullCost = probe(null)
        val blankCost = probe("")
        assertEquals(ImageProcessor.OUTPUT_WIDTH, nullCost.width)
        assertTrue(
            "null and blank mana costs must render identically",
            nullCost.sameAs(blankCost)
        )
    }

    @Test
    fun testCompositeCardProxy_appendsTrailingFeedWhitespace() {
        val card = ScryfallCard(name = "Feed Test", layout = "normal")
        val composite = ImageProcessor.compositeCardProxy(card, null)

        val width = composite.width
        val height = composite.height
        assertTrue(height > ImageProcessor.TRAILING_FEED_WHITESPACE_PX)

        // Every row inside the trailing feed band must be pure white.
        val band = IntArray(width * ImageProcessor.TRAILING_FEED_WHITESPACE_PX)
        composite.getPixels(
            band, 0, width,
            0, height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX,
            width, ImageProcessor.TRAILING_FEED_WHITESPACE_PX
        )
        for (pixel in band) {
            assertEquals(Color.WHITE, pixel)
        }
    }

    // ------------------------------------------------------------------
    // Multi-slip generation
    // ------------------------------------------------------------------

    private fun art(color: Int, size: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun transformCard() = ScryfallCard(
        name = "Delver of Secrets // Insectile Aberration",
        layout = "transform",
        card_faces = listOf(
            CardFace(
                name = "Delver of Secrets",
                mana_cost = "{U}",
                type_line = "Creature — Human Wizard",
                oracle_text = "At the beginning of your upkeep, look at the top card of your library.",
                power = "1",
                toughness = "1",
                image_uris = ImageUris(artCrop = "https://img/front.jpg")
            ),
            CardFace(
                name = "Insectile Aberration",
                type_line = "Creature — Human Insect",
                oracle_text = "Flying",
                power = "3",
                toughness = "2",
                image_uris = ImageUris(artCrop = "https://img/back.jpg")
            )
        )
    )

    @Test
    fun testComposePrintSlips_normalCardYieldsOneSlipOf384px() {
        val card = ScryfallCard(
            name = "Colossal Dreadmaw",
            mana_cost = "{4}{G}{G}",
            type_line = "Creature — Dinosaur",
            oracle_text = "Trample",
            power = "6",
            toughness = "6",
            image_uris = ImageUris(artCrop = "https://img/dreadmaw.jpg"),
            layout = "normal"
        )

        val slips = ImageProcessor.composePrintSlips(card, listOf(art(Color.LTGRAY)))
        assertEquals(1, slips.size)
        val slip = slips.single()
        assertEquals(384, slip.bitmap.width)
        assertEquals(ImageProcessor.OUTPUT_WIDTH, slip.bitmap.width)
        assertEquals("Colossal Dreadmaw", slip.faceName)
        assertEquals(0, slip.faceIndex)
        assertEquals(1, slip.totalSlips)
        assertNull(slip.label)
    }

    @Test
    fun testComposePrintSlips_transformCardYieldsTwoSlipsBoth384px() {
        val slips = ImageProcessor.composePrintSlips(
            transformCard(),
            listOf(art(Color.LTGRAY), art(Color.DKGRAY))
        )

        assertEquals(2, slips.size)
        for (slip in slips) {
            assertEquals(384, slip.bitmap.width)
            assertEquals(ImageProcessor.OUTPUT_WIDTH, slip.bitmap.width)
            assertEquals(2, slip.totalSlips)
        }
        assertEquals("Delver of Secrets", slips[0].faceName)
        assertEquals("Insectile Aberration", slips[1].faceName)
        assertEquals("Transforms into: Insectile Aberration", slips[0].label)
        assertEquals("Transforms from: Delver of Secrets", slips[1].label)
    }

    @Test
    fun testComposePrintSlips_twoSlipsAreDistinctBitmaps() {
        val slips = ImageProcessor.composePrintSlips(
            transformCard(),
            listOf(art(Color.LTGRAY), art(Color.DKGRAY))
        )
        assertEquals(2, slips.size)

        val front = slips[0].bitmap
        val back = slips[1].bitmap
        assertFalse("slips must not be the same bitmap instance", front === back)
        assertFalse("slips must not be pixel-identical duplicates", front.sameAs(back))
    }

    @Test
    fun testComposePrintSlips_splitCardStaysOnOneSlip() {
        // Fire // Ice: two faces but a single shared top-level image. Must never print twice.
        val split = ScryfallCard(
            name = "Fire // Ice",
            type_line = "Instant // Instant",
            image_uris = ImageUris(artCrop = "https://img/fire-ice.jpg"),
            layout = "split",
            card_faces = listOf(
                CardFace(name = "Fire", mana_cost = "{1}{R}", type_line = "Instant", oracle_text = "Deal 2 damage."),
                CardFace(name = "Ice", mana_cost = "{1}{U}", type_line = "Instant", oracle_text = "Tap target permanent.")
            )
        )

        val slips = ImageProcessor.composePrintSlips(split, listOf(art(Color.LTGRAY)))
        assertEquals(1, slips.size)
        assertEquals(384, slips.single().bitmap.width)
        assertNull(slips.single().label)
    }

    @Test
    fun testComposePrintSlips_everySlipGetsItsOwnTrailingFeed() {
        val slips = ImageProcessor.composePrintSlips(
            transformCard(),
            listOf(art(Color.LTGRAY), art(Color.DKGRAY))
        )

        for (slip in slips) {
            val bmp = slip.bitmap
            val width = bmp.width
            val height = bmp.height
            assertTrue(height > ImageProcessor.TRAILING_FEED_WHITESPACE_PX)

            val band = IntArray(width * ImageProcessor.TRAILING_FEED_WHITESPACE_PX)
            bmp.getPixels(
                band, 0, width,
                0, height - ImageProcessor.TRAILING_FEED_WHITESPACE_PX,
                width, ImageProcessor.TRAILING_FEED_WHITESPACE_PX
            )
            for (pixel in band) {
                assertEquals(Color.WHITE, pixel)
            }
        }
    }

    @Test
    fun testComposePrintSlips_withoutArtStillProducesTwoSlips() {
        val slips = ImageProcessor.composePrintSlips(transformCard())
        assertEquals(2, slips.size)
        for (slip in slips) {
            assertEquals(384, slip.bitmap.width)
        }
        assertFalse(slips[0].bitmap.sameAs(slips[1].bitmap))
    }
}
