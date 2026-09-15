package com.example.snapstoneprinter.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.print.PrinterTarget
import com.example.snapstoneprinter.data.print.PrinterTargetStore
import com.example.snapstoneprinter.data.repository.CardRepository
import com.example.snapstoneprinter.image.ArtDownloader
import com.example.snapstoneprinter.image.ArtResult
import com.example.snapstoneprinter.image.ImageProcessor
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream

/** Which image the preview pane is showing. The PRINTED output is always the thermal composite. */
enum class PreviewMode {
    /** The 384 px dithered slip - a proof of what the printer emits. Nearest-neighbour only. */
    THERMAL,

    /** The full-resolution Scryfall card image. Purely a reference; never printed. */
    FULL_CARD
}

/**
 * One past pull. Holds every slip that pull produced so a reprint needs no network round-trip.
 *
 * In-memory for the session only: the bitmaps are the point of the entry, and persisting ~20
 * multi-megabyte bitmaps to disk would cost more than re-rolling the card.
 */
data class HistoryEntry(
    val id: Long,
    val cardName: String,
    val typeLine: String?,
    val slips: List<PrintSlip>
) {
    val slipCount: Int get() = slips.size
}

/**
 * An in-flight print job.
 *
 * Slips are dispatched STRICTLY ONE AT A TIME: [index] only advances once the previous
 * `ACTION_SEND` activity has returned. `ACTION_SEND_MULTIPLE` is deliberately never used - the
 * cheap Bluetooth thermal printer apps this targets handle multi-stream sends badly, dropping or
 * interleaving images.
 */
data class SlipDispatch(
    val requestId: Long,
    val uris: List<Uri>,
    val index: Int,
    val label: String
) {
    val current: Uri get() = uris[index]
    val hasNext: Boolean get() = index + 1 < uris.size
    val total: Int get() = uris.size
}

data class ProxyGeneratorUiState(
    val currentCard: ScryfallCard? = null,
    /**
     * Every slip produced by the current card. One entry for a normal card, one per face for a
     * true double-faced card (transform / modal_dfc / reversible_card).
     */
    val slips: List<PrintSlip> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Non-null when art was expected but could not be fetched. Never silently swallowed. */
    val artError: String? = null,
    val isFunny: Boolean = false,
    /** Pre-dither contrast multiplier. [ImageProcessor.DEFAULT_CONTRAST] == auto-levels only. */
    val contrast: Float = ImageProcessor.DEFAULT_CONTRAST,
    /** Pre-dither brightness offset. [ImageProcessor.DEFAULT_BRIGHTNESS] == auto-levels only. */
    val brightness: Float = ImageProcessor.DEFAULT_BRIGHTNESS,
    /** True while a debounced re-dither is running. Never blanks the preview. */
    val isRedithering: Boolean = false,
    val previewMode: PreviewMode = PreviewMode.THERMAL,
    val history: List<HistoryEntry> = emptyList(),
    val dispatch: SlipDispatch? = null,
    /** The remembered printer app, or null when the chooser should be shown. */
    val printerTarget: PrinterTarget? = null
) {
    /** First slip - the front face. Convenience for the interim single-preview UI. */
    val primarySlip: PrintSlip?
        get() = slips.firstOrNull()

    /** Bitmap of the first slip, or null when nothing has been generated yet. */
    val proxyBitmap: Bitmap?
        get() = primarySlip?.bitmap

    val hasMultipleSlips: Boolean
        get() = slips.size > 1

    val canPrint: Boolean
        get() = !isLoading && slips.isNotEmpty()

    val isToneMappingNeutral: Boolean
        get() = contrast == ImageProcessor.DEFAULT_CONTRAST &&
            brightness == ImageProcessor.DEFAULT_BRIGHTNESS
}

/**
 * Owns the fetch -> download art -> dither -> compose -> dispatch pipeline.
 *
 * Extends [AndroidViewModel] rather than holding a `Context` field: a raw `Context` in a ViewModel
 * is the classic way to leak an Activity across a configuration change. The [Application] handed
 * to [AndroidViewModel] is process-scoped and therefore safe.
 */
class ProxyGeneratorViewModel(
    application: Application,
    private val repository: CardRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProxyGeneratorUiState())
    val uiState: StateFlow<ProxyGeneratorUiState> = _uiState.asStateFlow()

    private val artDownloader = ArtDownloader(application)

    /**
     * The UNDITHERED art for the current pull, one entry per slip.
     *
     * This is what makes the tone sliders cheap: changing contrast re-runs Floyd-Steinberg over
     * this cached source, never a second network fetch.
     */
    private var sourceArt: List<Bitmap?> = emptyList()
    private var currentPlan: List<SlipContent> = emptyList()

    /** Debounce handle for the tone sliders, so a drag does not queue a dither per frame. */
    private var reditherJob: Job? = null

    init {
        viewModelScope.launch {
            PrinterTargetStore.targetFlow(getApplication()).collect { target ->
                _uiState.update { it.copy(printerTarget = target) }
            }
        }
    }

    fun toggleIsFunny(enabled: Boolean) {
        _uiState.update { it.copy(isFunny = enabled) }
    }

    fun setPreviewMode(mode: PreviewMode) {
        _uiState.update { it.copy(previewMode = mode) }
    }

    // ------------------------------------------------------------------
    // Tone mapping
    // ------------------------------------------------------------------

    /** Pre-dither contrast multiplier. Triggers a debounced re-dither of the CACHED source art. */
    fun setContrast(value: Float) {
        _uiState.update { it.copy(contrast = value.coerceIn(MIN_CONTRAST, MAX_CONTRAST)) }
        scheduleRedither()
    }

    /** Pre-dither brightness offset. Triggers a debounced re-dither of the CACHED source art. */
    fun setBrightness(value: Float) {
        _uiState.update { it.copy(brightness = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)) }
        scheduleRedither()
    }

    /** Restores neutral tone-mapping, i.e. auto-levels only. */
    fun resetToneMapping() {
        _uiState.update {
            it.copy(
                contrast = ImageProcessor.DEFAULT_CONTRAST,
                brightness = ImageProcessor.DEFAULT_BRIGHTNESS
            )
        }
        scheduleRedither()
    }

    /**
     * Coalesces slider movement into one re-dither.
     *
     * Floyd-Steinberg is a serial O(w*h) pass over a ~600x450 image plus a full slip recomposition.
     * At 60 drag events per second that is unshippable, so each change cancels the pending job and
     * restarts a [REDITHER_DEBOUNCE_MS] timer. The last value wins.
     */
    private fun scheduleRedither() {
        if (currentPlan.isEmpty()) return
        reditherJob?.cancel()
        reditherJob = viewModelScope.launch {
            delay(REDITHER_DEBOUNCE_MS)
            _uiState.update { it.copy(isRedithering = true) }
            try {
                val state = _uiState.value
                val plan = currentPlan
                val art = sourceArt
                val slips = withContext(Dispatchers.Default) {
                    composeFrom(plan, art, state.contrast, state.brightness)
                }
                _uiState.update { it.copy(slips = slips, isRedithering = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Re-dither failed", e)
                _uiState.update { it.copy(isRedithering = false) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    /** Always excludes lands - see [CardRepository.getRandomCard]. */
    fun fetchRandomCard() {
        generateProxy(notFoundMessage = "No random card found - try again") {
            repository.getRandomCard(_uiState.value.isFunny)
        }
    }

    /** MomirVig mode: a random creature of [cmc] - see [CardRepository.getMomirVigCreature]. */
    fun fetchMomirVigCreature(cmc: Int) {
        generateProxy(notFoundMessage = "No creature found at CMC $cmc") {
            repository.getMomirVigCreature(cmc, _uiState.value.isFunny)
        }
    }

    /**
     * Fetches one exact card by name instead of rolling randomly.
     *
     * Doubles as the way to force-generate split/flip/adventure/transform/modal_dfc cards for
     * testing the renderer - those layouts are a small slice of the random pool, and this skips
     * waiting on RNG to hit one.
     */
    fun fetchCardByName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        generateProxy(notFoundMessage = "No card found by that name") {
            repository.getCardByName(trimmed)
        }
    }

    private fun generateProxy(notFoundMessage: String, fetchBlock: suspend () -> ScryfallCard) {
        reditherJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, artError = null, slips = emptyList())
            }
            try {
                val card = fetchBlock()
                _uiState.update { it.copy(currentCard = card) }

                // One entry per slip. A true DFC plans two, each pointing at its OWN face art;
                // split/flip/adventure plan a single slip off the shared top-level image_uris.
                val plan = SlipPlanner.plan(card)

                val failures = mutableListOf<String>()
                val art = plan.map { content ->
                    val url = content.artUrl
                    if (url.isNullOrBlank()) {
                        Log.w(TAG, "No art URL for face '${content.name}'")
                        failures += "${content.name}: Scryfall listed no art URL"
                        null
                    } else {
                        when (val result = artDownloader.fetch(url)) {
                            is ArtResult.Success -> result.bitmap
                            is ArtResult.Failure -> {
                                failures += "${content.name}: ${result.reason}"
                                null
                            }
                        }
                    }
                }

                currentPlan = plan
                sourceArt = art

                val state = _uiState.value
                val slips = withContext(Dispatchers.Default) {
                    composeFrom(plan, art, state.contrast, state.brightness)
                }

                _uiState.update {
                    it.copy(
                        slips = slips,
                        isLoading = false,
                        artError = failures.takeIf { f -> f.isNotEmpty() }
                            ?.joinToString("; ")
                            ?.let { reason -> "Art unavailable - printing text only ($reason)" },
                        history = it.history.prepended(
                            HistoryEntry(
                                id = System.currentTimeMillis(),
                                cardName = card.effectiveName,
                                typeLine = card.effectiveTypeLine,
                                slips = slips
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy generation failed", e)
                val message = if (e is HttpException && e.code() == 404) {
                    notFoundMessage
                } else {
                    e.message ?: "Unknown error"
                }
                _uiState.update { it.copy(isLoading = false, error = message) }
            }
        }
    }

    private fun composeFrom(
        plan: List<SlipContent>,
        art: List<Bitmap?>,
        contrast: Float,
        brightness: Float
    ): List<PrintSlip> {
        val dithered = art.map { source ->
            source?.let {
                ImageProcessor.prepareArt(
                    src = it,
                    contrast = contrast,
                    brightness = brightness
                )
            }
        }
        return ImageProcessor.composeSlips(plan, dithered)
    }

    private fun List<HistoryEntry>.prepended(entry: HistoryEntry): List<HistoryEntry> =
        (listOf(entry) + this).take(MAX_HISTORY)

    // ------------------------------------------------------------------
    // Printing / dispatch
    // ------------------------------------------------------------------

    /** Queues EVERY slip of the current card for sequential dispatch. */
    fun printCurrentCard() {
        val state = _uiState.value
        dispatchSlips(state.slips, state.currentCard?.effectiveName ?: "Proxy")
    }

    /** Re-dispatches a past pull straight from its cached bitmaps - no network, no re-dither. */
    fun reprint(entry: HistoryEntry) {
        dispatchSlips(entry.slips, entry.cardName)
    }

    private fun dispatchSlips(slips: List<PrintSlip>, label: String) {
        if (slips.isEmpty()) return
        viewModelScope.launch {
            try {
                val uris = withContext(Dispatchers.IO) {
                    val dir = cacheImagesDir()
                    val stamp = System.currentTimeMillis()
                    slips.mapIndexed { index, slip -> writeSlipPng(dir, slip, stamp, index) }
                }
                _uiState.update {
                    it.copy(
                        dispatch = SlipDispatch(
                            requestId = System.nanoTime(),
                            uris = uris,
                            index = 0,
                            label = label
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache slips for sharing", e)
                _uiState.update { it.copy(error = "Failed to save image for sharing: ${e.message}") }
            }
        }
    }

    /**
     * Called once the `ACTION_SEND` activity for the current slip has RETURNED. Only then does the
     * next slip go out - that sequencing is the whole point.
     */
    fun onSlipDispatched() {
        _uiState.update { state ->
            val dispatch = state.dispatch ?: return@update state
            if (dispatch.hasNext) {
                state.copy(dispatch = dispatch.copy(index = dispatch.index + 1))
            } else {
                state.copy(dispatch = null)
            }
        }
    }

    /** Aborts the whole job, e.g. when no app on the device can handle `image/png`. */
    fun cancelDispatch(reason: String? = null) {
        _uiState.update { it.copy(dispatch = null, error = reason ?: it.error) }
    }

    /** True when the remembered target still exists and can be launched directly. */
    fun isTargetUsable(target: PrinterTarget): Boolean =
        PrinterTargetStore.isResolvable(getApplication(), target.component)

    /** The visible "change target" affordance: next print goes back through the chooser. */
    fun forgetPrinterTarget() {
        viewModelScope.launch {
            try {
                PrinterTargetStore.forget(getApplication())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear the remembered print target", e)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, artError = null) }
    }

    // ------------------------------------------------------------------
    // Cache plumbing
    // ------------------------------------------------------------------

    /** Must match the `<cache-path name="shared_images" path="images/" />` entry in file_paths.xml. */
    private fun cacheImagesDir(): File {
        val dir = File(getApplication<Application>().cacheDir, CACHE_IMAGES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** ONE PNG PER SLIP. Never a combined sheet - each file is one physical strip of paper. */
    private fun writeSlipPng(dir: File, slip: PrintSlip, stamp: Long, index: Int): Uri {
        val file = File(dir, "slip_${stamp}_${index + 1}of${slip.totalSlips}.png")
        FileOutputStream(file).use { out ->
            slip.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val application = getApplication<Application>()
        return FileProvider.getUriForFile(
            application,
            "${application.packageName}$FILE_PROVIDER_SUFFIX",
            file
        )
    }

    companion object {
        private const val TAG = "ProxyGeneratorVM"

        const val MIN_CONTRAST = 0.5f
        const val MAX_CONTRAST = 3.0f
        const val MIN_BRIGHTNESS = -128f
        const val MAX_BRIGHTNESS = 128f

        /** Long enough to swallow a drag, short enough to feel live. */
        const val REDITHER_DEBOUNCE_MS = 140L

        /** Session history depth. */
        const val MAX_HISTORY = 20

        const val CACHE_IMAGES_DIR = "images"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }
}
