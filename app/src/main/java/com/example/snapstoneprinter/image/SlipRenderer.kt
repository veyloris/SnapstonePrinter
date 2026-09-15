package com.example.snapstoneprinter.image

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SlipRenderer {
    suspend fun render(
        plan: List<SlipContent>,
        art: List<Bitmap?>,
        contrast: Float,
        brightness: Float
    ): List<PrintSlip>
}

class AndroidSlipRenderer : SlipRenderer {
    override suspend fun render(
        plan: List<SlipContent>,
        art: List<Bitmap?>,
        contrast: Float,
        brightness: Float
    ): List<PrintSlip> = withContext(Dispatchers.Default) {
        val prepared = art.map { source ->
            source?.let { ImageProcessor.prepareArt(it, contrast, brightness) }
        }
        ImageProcessor.composeSlips(plan, prepared)
    }
}
