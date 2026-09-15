package com.example.snapstoneprinter.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.util.ManaCostFormatter

object ImageProcessor {

    private const val TAG = "ImageProcessor"

    /** Thermal printer head width. Immutable spec - never change. */
    const val OUTPUT_WIDTH = 384

    /**
     * Blank white rows appended below the composed proxy so a tear-off at the printer's cutter
     * never clips the last line of text.
     */
    const val TRAILING_FEED_WHITESPACE_PX = 48

    /** Extra vertical gap before each [SecondaryFace] block, wider than the normal 8-12px rhythm
     *  so the other half of a split/flip/adventure card reads as visually separate. */
    private const val SECONDARY_FACE_GAP_PX = 24f

    /** Horizontal breathing room between the name column and the right-justified mana cost. */
    private const val TITLE_COST_GAP_PX = 12f

    const val DEFAULT_CONTRAST = Tonemap.DEFAULT_CONTRAST
    const val DEFAULT_BRIGHTNESS = Tonemap.DEFAULT_BRIGHTNESS

    private const val PADDING = 12
    const val ART_WIDTH = OUTPUT_WIDTH - 2 * PADDING

    /** Prepare art before composition; see prepareArtResamplesBeforeToneAndDither. */
    @JvmOverloads
    fun prepareArt(
        src: Bitmap,
        contrast: Float = DEFAULT_CONTRAST,
        brightness: Float = DEFAULT_BRIGHTNESS
    ): Bitmap {
        val height = maxOf(1, (src.height.toLong() * ART_WIDTH / src.width).toInt())
        val resized = Bitmap.createScaledBitmap(src, ART_WIDTH, height, true)
        return applyFloydSteinbergDithering(resized, contrast, brightness)
    }

    /**
     * Converts a Bitmap to monochrome.
     *
     * Order of operations (fixed):
     *  1. RGB -> luminance
     *  2. auto-levels (2nd..98th percentile histogram stretch)
     *  3. contrast / brightness
     *  4. Floyd-Steinberg error diffusion (7/3/5/1 over 16)
     *
     * @param contrast multiplier around mid grey; [DEFAULT_CONTRAST] is neutral.
     * @param brightness offset in luminance units; [DEFAULT_BRIGHTNESS] is neutral.
     */
    @JvmOverloads
    fun applyFloydSteinbergDithering(
        src: Bitmap,
        contrast: Float = DEFAULT_CONTRAST,
        brightness: Float = DEFAULT_BRIGHTNESS
    ): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1-3: luminance, then auto-levels, then contrast/brightness. Always BEFORE dithering.
        val gray = Tonemap.prepareForDithering(
            luminance = Tonemap.toLuminance(pixels),
            contrast = contrast,
            brightness = brightness
        )

        // 4: Floyd-Steinberg error diffusion. Kernel is immutable spec.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldPixel = gray[idx]
                val newPixel = if (oldPixel >= 128f) 255f else 0f
                gray[idx] = newPixel

                val error = oldPixel - newPixel

                if (x + 1 < width) {
                    gray[idx + 1] += error * 7f / 16f
                }
                if (y + 1 < height) {
                    if (x - 1 >= 0) {
                        gray[(y + 1) * width + x - 1] += error * 3f / 16f
                    }
                    gray[(y + 1) * width + x] += error * 5f / 16f
                    if (x + 1 < width) {
                        gray[(y + 1) * width + x + 1] += error * 1f / 16f
                    }
                }
            }
        }

        val outPixels = IntArray(width * height)
        for (i in outPixels.indices) {
            val g = gray[i].coerceIn(0f, 255f).toInt()
            outPixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }

        val dest = createBitmap(width, height)
        dest.setPixels(outPixels, 0, width, 0, 0, width, height)
        return dest
    }

    /**
     * Composition entry point.
     *
     * Turns a fetched card into the ordered list of slips to print. A normal card yields exactly
     * one slip; a true double-faced card (see [SlipPlanner.hasPerFaceArt]) yields one slip per
     * face, each composed from THAT face's own art / mana cost / type line / oracle text / P-T.
     *
     * Every slip is independently [OUTPUT_WIDTH] px wide and gets its own
     * [TRAILING_FEED_WHITESPACE_PX] tear-off band.
     *
     * @param ditheredArt supply [prepareArt] output, indexed by slip; omit entries for text-only slips.
     */
    @JvmOverloads
    fun composePrintSlips(
        card: ScryfallCard,
        ditheredArt: List<Bitmap?> = emptyList()
    ): List<PrintSlip> = composeSlips(SlipPlanner.plan(card), ditheredArt)

    /**
     * Renders a pre-resolved [plan] (see [SlipPlanner.plan]). Split out from [composePrintSlips] so
     * callers can download the per-face art referenced by the plan before composing.
     */
    fun composeSlips(
        plan: List<SlipContent>,
        ditheredArt: List<Bitmap?> = emptyList()
    ): List<PrintSlip> {
        ditheredArt.forEach(::requirePreparedArt)
        return plan.map { content ->
            PrintSlip(
                bitmap = renderSlip(content, ditheredArt.getOrNull(content.faceIndex)),
                faceName = content.name,
                faceIndex = content.faceIndex,
                totalSlips = content.totalSlips,
                label = content.label,
                fullImageUrl = content.fullImageUrl
            )
        }
    }

    /**
     * Composites card text and the dithered art_crop image into a single Bitmap whose width is
     * always exactly [OUTPUT_WIDTH] pixels.
     *
     * No card frames are drawn: the text is rendered natively on Canvas below the dithered art in
     * high-contrast sans-serif on pure white. Mana costs are plain text - no pip symbols.
     *
     */
    fun compositeCardProxy(card: ScryfallCard, ditheredArt: Bitmap?): Bitmap {
        requirePreparedArt(ditheredArt)
        return renderSlip(SlipPlanner.singleSlipContent(card), ditheredArt)
    }

    private fun requirePreparedArt(art: Bitmap?) {
        require(art == null || art.width == ART_WIDTH) {
            "Art width must be $ART_WIDTH pixels; received ${art?.width}. Call prepareArt before composition."
        }
    }

    /**
     * Draws one slip: optional small header label, name + plain-text mana cost, type line, dithered
     * art, oracle text, power/toughness, then the trailing blank feed band.
     */
    private fun renderSlip(content: SlipContent, ditheredArt: Bitmap?): Bitmap {
        val canvasWidth = OUTPUT_WIDTH
        val padding = PADDING
        val textWidth = canvasWidth - (padding * 2)

        // Setup high-contrast sans-serif paints
        val labelPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }

        val typePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }

        val oraclePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }

        val statsPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }

        // Measure heights dynamically
        var currentY = padding.toFloat()

        val labelText = content.label
        var labelLayout: StaticLayout? = null
        if (!labelText.isNullOrBlank()) {
            labelLayout = StaticLayout.Builder
                .obtain(labelText, 0, labelText.length, labelPaint, textWidth)
                .build()
            currentY += labelLayout.height + 6f
        }

        val primaryBlock = measureFaceBlock(
            name = content.name,
            manaCost = content.manaCost,
            typeLine = content.typeLine,
            oracleText = content.oracleText,
            power = content.power,
            toughness = content.toughness,
            loyalty = content.loyalty,
            defense = content.defense,
            textWidth = textWidth,
            titlePaint = titlePaint,
            typePaint = typePaint,
            oraclePaint = oraclePaint,
            statsPaint = statsPaint
        )

        val imgHeight = ditheredArt?.height ?: 0
        val artGap = if (imgHeight > 0) imgHeight + 12f else 0f

        currentY = primaryBlock.advance(currentY, artGap)

        // Split / flip / adventure: the other half's text goes on THIS slip, underneath, never as
        // a separate ACTION_SEND. Extra vertical whitespace is the only separator - no rule line,
        // to stay inside the "no borders" spec.
        val secondaryBlocks = content.secondaryFaces.map { face ->
            currentY += SECONDARY_FACE_GAP_PX
            val block = measureFaceBlock(
                name = face.name,
                manaCost = face.manaCost,
                typeLine = face.typeLine,
                oracleText = face.oracleText,
                power = face.power,
                toughness = face.toughness,
                loyalty = face.loyalty,
                defense = face.defense,
                textWidth = textWidth,
                titlePaint = titlePaint,
                typePaint = typePaint,
                oraclePaint = oraclePaint,
                statsPaint = statsPaint
            )
            currentY = block.advance(currentY)
            block
        }

        currentY += padding.toFloat()
        // Trailing blank feed so the tear-off never clips the last line. Per slip.
        currentY += TRAILING_FEED_WHITESPACE_PX
        val totalHeight = currentY.toInt()

        // Create bitmap and canvas
        val resultBitmap = createBitmap(canvasWidth, totalHeight)
        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.WHITE)

        // Draw components
        var drawY = padding.toFloat()

        if (labelLayout != null) {
            canvas.withTranslation(padding.toFloat(), drawY) { labelLayout.draw(this) }
            drawY += labelLayout.height + 6f
        }

        drawY = primaryBlock.draw(canvas, padding.toFloat(), drawY, artGap) { c, gapY ->
            if (ditheredArt != null && imgHeight > 0) {
                val srcRect = Rect(0, 0, ditheredArt.width, ditheredArt.height)
                val destRect = Rect(padding, gapY.toInt(), padding + textWidth, gapY.toInt() + imgHeight)
                c.drawBitmap(ditheredArt, srcRect, destRect, null)
            }
        }

        secondaryBlocks.forEach { block ->
            drawY += SECONDARY_FACE_GAP_PX
            drawY = block.draw(canvas, padding.toFloat(), drawY)
        }

        return resultBitmap
    }

    /**
     * Reuse each face's measured layouts for drawing; CardStatsRenderingTest checks stat ink
     * and allocated space for primary, secondary, and separate-slip faces.
     */
    private class FaceBlock(
        val titleRow: TitleRow,
        val typeLayout: StaticLayout,
        val oracleLayout: StaticLayout,
        val statsLayout: StaticLayout?
    ) {
        /** Y position after this block, given [extraGap] inserted between the type and oracle text. */
        fun advance(startY: Float, extraGap: Float = 0f): Float {
            var y = startY + titleRow.height + 8f
            y += typeLayout.height + 12f + extraGap
            y += oracleLayout.height
            if (statsLayout != null) y += 8f + statsLayout.height
            return y
        }

        /**
         * Draws this block starting at [startY], returning the Y position after it. [extraGap]
         * must match the value passed to [advance] for the same block; [drawGap] is invoked with
         * the gap's Y position (used by the primary face to place its art between type and oracle).
         */
        fun draw(
            canvas: Canvas,
            padding: Float,
            startY: Float,
            extraGap: Float = 0f,
            drawGap: (Canvas, Float) -> Unit = { _, _ -> }
        ): Float {
            var y = startY
            canvas.withTranslation(padding, y) { titleRow.draw(this) }
            y += titleRow.height + 8f

            canvas.withTranslation(padding, y) { typeLayout.draw(this) }
            y += typeLayout.height + 12f

            if (extraGap > 0f) {
                drawGap(canvas, y)
                y += extraGap
            }

            canvas.withTranslation(padding, y) { oracleLayout.draw(this) }
            y += oracleLayout.height

            if (statsLayout != null) {
                y += 8f
                canvas.withTranslation(padding, y) { statsLayout.draw(this) }
                y += statsLayout.height
            }
            return y
        }
    }

    private fun measureFaceBlock(
        name: String,
        manaCost: String?,
        typeLine: String?,
        oracleText: String?,
        power: String?,
        toughness: String?,
        loyalty: String?,
        defense: String?,
        textWidth: Int,
        titlePaint: TextPaint,
        typePaint: TextPaint,
        oraclePaint: TextPaint,
        statsPaint: TextPaint
    ): FaceBlock {
        val titleRow = buildTitleRow(name, safeManaCost(manaCost), textWidth, titlePaint)

        val typeText = typeLine ?: ""
        val typeLayout = StaticLayout.Builder.obtain(typeText, 0, typeText.length, typePaint, textWidth).build()

        val oracleTextSafe = oracleText ?: ""
        val oracleLayout = StaticLayout.Builder
            .obtain(oracleTextSafe, 0, oracleTextSafe.length, oraclePaint, textWidth)
            .build()

        val statsText = FaceStatsFormatter.format(power, toughness, loyalty, defense).joinToString("\n")
        val statsLayout = if (statsText.isNotEmpty()) {
            StaticLayout.Builder.obtain(statsText, 0, statsText.length, statsPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .build()
        } else {
            null
        }

        return FaceBlock(titleRow, typeLayout, oracleLayout, statsLayout)
    }

    /**
     * Name flush left, mana cost flush right - one row, matching how a physical card (and the
     * power/toughness line below) lays it out. Two independent [StaticLayout]s rather than one
     * combined string: the name is width-capped to leave room for the cost column, so a long
     * combined name (e.g. "Bonecrusher Giant // Stomp") wraps onto a second line instead of
     * colliding with the cost instead of running under it.
     */
    private class TitleRow(
        private val nameLayout: StaticLayout,
        private val costLayout: StaticLayout?
    ) {
        val height: Int = maxOf(nameLayout.height, costLayout?.height ?: 0)

        fun draw(canvas: Canvas) {
            nameLayout.draw(canvas)
            costLayout?.draw(canvas)
        }
    }

    /** Minimum share of [textWidth] left for the name column even with an unusually wide cost. */
    private const val MIN_NAME_WIDTH_FRACTION = 0.4

    private fun buildTitleRow(name: String, manaCost: String, textWidth: Int, titlePaint: TextPaint): TitleRow {
        if (manaCost.isEmpty()) {
            val nameLayout = StaticLayout.Builder.obtain(name, 0, name.length, titlePaint, textWidth).build()
            return TitleRow(nameLayout, null)
        }

        val costWidth = titlePaint.measureText(manaCost)
        val nameWidth = (textWidth - costWidth - TITLE_COST_GAP_PX)
            .toInt()
            .coerceAtLeast((textWidth * MIN_NAME_WIDTH_FRACTION).toInt())
        val nameLayout = StaticLayout.Builder.obtain(name, 0, name.length, titlePaint, nameWidth).build()
        val costLayout = StaticLayout.Builder.obtain(manaCost, 0, manaCost.length, titlePaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .build()
        return TitleRow(nameLayout, costLayout)
    }

    /**
     * Formats a mana cost for the title line, degrading gracefully instead of failing the render.
     *
     * Mana-cost formatting is purely cosmetic: it strips the braces off `{2}{U}{U}`. A problem
     * there must never be able to take down the whole bitmap composition, so any failure falls
     * back to the raw Scryfall string. Printing "{2}{U}{U}" is a blemish; printing nothing at all
     * because the app crashed is a P0.
     *
     * [Throwable] is caught deliberately and not narrowed to [Exception]. The real outage this
     * guards against was an invalid regex thrown from a static initialiser, which surfaces as
     * `ExceptionInInitializerError` and thereafter `NoClassDefFoundError` - both `Error`s, so a
     * `catch (e: Exception)` would have sailed straight past them and crashed the process anyway.
     */
    private fun safeManaCost(raw: String?): String = try {
        ManaCostFormatter.normalizeManaCost(raw)
    } catch (t: Throwable) {
        Log.w(TAG, "Mana cost formatting failed; falling back to the raw value", t)
        raw?.trim().orEmpty()
    }
}
