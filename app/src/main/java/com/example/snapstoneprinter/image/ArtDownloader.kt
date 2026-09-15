package com.example.snapstoneprinter.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.createBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.example.snapstoneprinter.data.api.ScryfallHeaderInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Fetches card art and hands back a SOFTWARE [Bitmap] the dithering pipeline can actually read.
 *
 * ## Why this is not a one-liner
 *
 * The original implementation was:
 *
 * ```
 * val result = imageLoader.execute(request)
 * return (result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
 * ```
 *
 * Three separate ways to silently return `null` were hiding in there, and every one of them
 * produced an art-less slip with no error anywhere:
 *
 * 1. **Hardware bitmaps.** Coil defaults to [Bitmap.Config.HARDWARE] on API 26+. Those live in
 *    graphics memory and [Bitmap.getPixels] throws on them, so the dither must never see one.
 *    `allowHardware(false)` is the first line of defence; [toSoftwareBitmap] copies as a second.
 * 2. **The bare `as? BitmapDrawable` cast.** Any other [Drawable] subclass - a crossfade wrapper,
 *    an animated drawable, a vector - yields `null` from the cast. We now rasterise ANY drawable.
 * 3. **[ErrorResult] swallowed.** A timeout or an HTTP error became `null` indistinguishable from
 *    "this face has no art". Failures are now logged AND returned as [ArtResult.Failure] so the
 *    ViewModel can surface them instead of quietly printing a text-only slip.
 *
 * The loader also gets deliberately generous timeouts: `cards.scryfall.io` round-trips at roughly
 * half a second from an emulator, and Coil's default OkHttp client is tuned for thumbnails, not
 * for a blocking step in a print pipeline.
 */
class ArtDownloader(context: Context) : ArtSource {

    private val appContext = context.applicationContext

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // cards.scryfall.io enforces the same "no default HTTP-library User-Agent" rule as
            // api.scryfall.com (rejects with HTTP 400, rule=generic_user_agent). This client is
            // separate from RetrofitClient's, so it needs its own copy of the header.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", ScryfallHeaderInterceptor.DEFAULT_USER_AGENT)
                        .build()
                )
            }
            .build()
    }

    private val imageLoader by lazy {
        ImageLoader.Builder(appContext)
            .okHttpClient { okHttpClient }
            // Belt and braces: even a request that forgot allowHardware(false) stays readable.
            .allowHardware(false)
            .allowRgb565(false)
            .build()
    }

    override suspend fun fetch(url: String): ArtResult = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(appContext)
            .data(url)
            // MUST stay false: a HARDWARE-config bitmap cannot be read pixel-by-pixel, and the
            // Floyd-Steinberg pass is nothing but a pixel-by-pixel read.
            .allowHardware(false)
            .allowRgb565(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            // Keep original source pixels available for later tone adjustments.
            .size(Size.ORIGINAL)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()

        when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                val bitmap = result.drawable.toSoftwareBitmap()
                if (bitmap == null) {
                    val reason = "Decoded ${result.drawable.javaClass.simpleName} could not be " +
                        "converted to a readable bitmap"
                    Log.e(TAG, "$reason for $url")
                    ArtResult.Failure(reason)
                } else {
                    Log.i(
                        TAG,
                        "Art ready: ${bitmap.width}x${bitmap.height} " +
                            "config=${bitmap.config} source=${result.dataSource} url=$url"
                    )
                    ArtResult.Success(bitmap)
                }
            }

            is ErrorResult -> {
                val reason = result.throwable.message
                    ?: result.throwable.javaClass.simpleName
                Log.e(TAG, "Art download FAILED for $url: $reason", result.throwable)
                ArtResult.Failure(reason)
            }
        }
    }

    /**
     * Rasterises any [Drawable] into a software [Bitmap].
     *
     * The fast path reuses a [BitmapDrawable]'s backing bitmap, but only when it is already a
     * software config; otherwise it is copied out of graphics memory. Everything else is drawn
     * into a fresh ARGB_8888 canvas at its intrinsic size.
     */
    private fun Drawable.toSoftwareBitmap(): Bitmap? {
        if (this is BitmapDrawable) {
            val source = bitmap
            if (source != null) {
                return if (source.config == Bitmap.Config.HARDWARE) {
                    Log.w(TAG, "Got a HARDWARE bitmap despite allowHardware(false); copying")
                    source.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    source
                }
            }
        }

        val width = intrinsicWidth
        val height = intrinsicHeight
        if (width <= 0 || height <= 0) return null

        return try {
            val output = createBitmap(width, height)
            setBounds(0, 0, width, height)
            draw(Canvas(output))
            output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rasterise ${javaClass.simpleName}", e)
            null
        }
    }

    companion object {
        private const val TAG = "ArtDownloader"

        /** Per-phase timeout. Generous on purpose: an art-less slip is worse than a slow one. */
        const val TIMEOUT_SECONDS = 30L

        /** Whole-call ceiling, including redirects and retries. */
        const val CALL_TIMEOUT_SECONDS = 45L
    }
}

/** Outcome of an art fetch. [Failure] carries a reason so it can be shown, never swallowed. */
sealed interface ArtResult {
    data class Success(val bitmap: Bitmap) : ArtResult
    data class Failure(val reason: String) : ArtResult
}
