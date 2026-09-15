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
import com.example.snapstoneprinter.image.ArtSource
import com.example.snapstoneprinter.image.AndroidSlipRenderer
import com.example.snapstoneprinter.image.ImageProcessor
import com.example.snapstoneprinter.image.PrintSlip
import com.example.snapstoneprinter.image.SlipContent
import com.example.snapstoneprinter.image.SlipPlanner
import com.example.snapstoneprinter.image.SlipRenderer
import kotlinx.coroutines.CancellationException
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

data class ToneSettings(val contrast: Float, val brightness: Float)

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
    val printerTarget: PrinterTarget? = null,
    val currentPullId: Long? = null,
    val appliedTone: ToneSettings? = null,
    val renderError: String? = null
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
        get() = !isLoading && !isRedithering && slips.isNotEmpty()

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
    private val repository: CardRepository,
    private val artSource: ArtSource = ArtDownloader(application),
    private val slipRenderer: SlipRenderer = AndroidSlipRenderer()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProxyGeneratorUiState())
    val uiState: StateFlow<ProxyGeneratorUiState> = _uiState.asStateFlow()

    private data class DownloadedBundle(
        val generation: Long,
        val card: ScryfallCard,
        val plan: List<SlipContent>,
        val art: List<Bitmap?>,
        val artWarning: String?
    )

    private var activeGeneration = 0L
    private var toneRevision = 0L
    private var downloadedBundle: DownloadedBundle? = null
    private var generationJob: Job? = null
    private var renderJob: Job? = null

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
        requestTone(ToneSettings(value.coerceIn(MIN_CONTRAST, MAX_CONTRAST), _uiState.value.brightness))
    }

    /** Pre-dither brightness offset. Triggers a debounced re-dither of the CACHED source art. */
    fun setBrightness(value: Float) {
        requestTone(ToneSettings(_uiState.value.contrast, value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)))
    }

    /** Restores neutral tone-mapping, i.e. auto-levels only. */
    fun resetToneMapping() {
        requestTone(ToneSettings(ImageProcessor.DEFAULT_CONTRAST, ImageProcessor.DEFAULT_BRIGHTNESS))
    }

    private fun requestTone(tone: ToneSettings) {
        toneRevision += 1
        renderJob?.cancel()
        val bundle = downloadedBundle
        _uiState.update {
            it.copy(
                contrast = tone.contrast, brightness = tone.brightness, renderError = null,
                isRedithering = bundle != null && it.currentPullId == bundle.generation
            )
        }
        bundle?.let { startRender(it, debounce = true) }
    }

    private fun ownsRender(generation: Long, revision: Long): Boolean =
        generation == activeGeneration && revision == toneRevision

    private fun startRender(bundle: DownloadedBundle, debounce: Boolean) {
        renderJob?.cancel()
        val revision = toneRevision
        val tone = ToneSettings(_uiState.value.contrast, _uiState.value.brightness)
        _uiState.update {
            val hasPreview = it.currentPullId == bundle.generation
            it.copy(isLoading = !hasPreview, isRedithering = hasPreview, renderError = null)
        }
        renderJob = viewModelScope.launch {
            try {
                if (debounce) delay(REDITHER_DEBOUNCE_MS)
                val slips = slipRenderer.render(bundle.plan, bundle.art, tone.contrast, tone.brightness)
                if (!ownsRender(bundle.generation, revision)) return@launch
                require(slips.isNotEmpty() && slips.size == bundle.plan.size) {
                    "Renderer must return one nonempty result per planned slip"
                }
                _uiState.update { state ->
                    val history = if (state.currentPullId == bundle.generation) {
                        state.history.map { entry ->
                            if (entry.id == bundle.generation) entry.copy(slips = slips) else entry
                        }
                    } else {
                        state.history.prepended(
                            HistoryEntry(bundle.generation, bundle.card.effectiveName, bundle.card.effectiveTypeLine, slips)
                        )
                    }
                    state.copy(
                        currentCard = bundle.card, slips = slips, currentPullId = bundle.generation,
                        appliedTone = tone, history = history, isLoading = false, isRedithering = false,
                        error = null, renderError = null, artError = bundle.artWarning
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!ownsRender(bundle.generation, revision)) return@launch
                Log.e(TAG, "Slip render failed", e)
                val state = _uiState.value
                val applied = state.appliedTone
                if (state.currentPullId == bundle.generation && applied != null) {
                    _uiState.update {
                        it.copy(
                            contrast = applied.contrast, brightness = applied.brightness,
                            isLoading = false, isRedithering = false, error = null,
                            renderError = "Could not apply tone changes. Previous preview and settings kept."
                        )
                    }
                } else {
                    failInitialGeneration(e.message ?: "Unknown error")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    /** Always excludes lands - see [CardRepository.getRandomCard]. */
    fun fetchRandomCard() {
        val isFunny = _uiState.value.isFunny
        generateProxy(notFoundMessage = "No random card found - try again") {
            repository.getRandomCard(isFunny)
        }
    }

    /** MomirVig mode: a random creature of [cmc] - see [CardRepository.getMomirVigCreature]. */
    fun fetchMomirVigCreature(cmc: Int) {
        val isFunny = _uiState.value.isFunny
        generateProxy(notFoundMessage = "No creature found at CMC $cmc") {
            repository.getMomirVigCreature(cmc, isFunny)
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
        activeGeneration += 1
        toneRevision += 1
        val generation = activeGeneration
        generationJob?.cancel()
        renderJob?.cancel()
        downloadedBundle = null
        _uiState.update {
            it.copy(
                isLoading = true, isRedithering = false, currentCard = null, slips = emptyList(),
                currentPullId = null, appliedTone = null, error = null, artError = null, renderError = null
            )
        }
        generationJob = viewModelScope.launch {
            try {
                val card = fetchBlock()
                if (generation != activeGeneration) return@launch

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
                        when (val result = artSource.fetch(url)) {
                            is ArtResult.Success -> result.bitmap
                            is ArtResult.Failure -> {
                                failures += "${content.name}: ${result.reason}"
                                null
                            }
                        }
                    }
                }

                if (generation != activeGeneration) return@launch
                val bundle = DownloadedBundle(
                    generation, card, plan.toList(), art.toList(),
                    failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
                        ?.let { reason -> "Art unavailable - printing text only ($reason)" }
                )
                downloadedBundle = bundle
                startRender(bundle, debounce = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != activeGeneration) return@launch
                Log.e(TAG, "Proxy generation failed", e)
                val message = if (e is HttpException && e.code() == 404) {
                    notFoundMessage
                } else {
                    e.message ?: "Unknown error"
                }
                failInitialGeneration(message)
            }
        }
    }

    private fun failInitialGeneration(message: String) {
        downloadedBundle = null
        _uiState.update {
            it.copy(
                isLoading = false, isRedithering = false, currentCard = null, slips = emptyList(),
                currentPullId = null, appliedTone = null, error = message, renderError = null, artError = null
            )
        }
    }

    private fun List<HistoryEntry>.prepended(entry: HistoryEntry): List<HistoryEntry> =
        (listOf(entry) + this).take(MAX_HISTORY)

    // ------------------------------------------------------------------
    // Printing / dispatch
    // ------------------------------------------------------------------

    /** Queues EVERY slip of the current card for sequential dispatch. */
    fun printCurrentCard() {
        val state = _uiState.value
        if (!state.canPrint || state.dispatch != null) return
        dispatchSlips(state.slips, state.currentCard?.effectiveName ?: "Proxy")
    }

    /** Re-dispatches a past pull straight from its cached bitmaps - no network, no re-dither. */
    fun reprint(entry: HistoryEntry) {
        tryReprint(entry)
    }

    internal fun resolveHistoryEntry(id: Long): HistoryEntry? = _uiState.value.history.firstOrNull { it.id == id }

    fun tryReprint(entry: HistoryEntry): Boolean {
        val state = _uiState.value
        if (state.dispatch != null ||
            (entry.id == state.currentPullId && (state.isLoading || state.isRedithering))) return false
        val current = resolveHistoryEntry(entry.id) ?: return false
        dispatchSlips(current.slips, current.cardName)
        return true
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
        _uiState.update { it.copy(error = null, artError = null, renderError = null) }
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
