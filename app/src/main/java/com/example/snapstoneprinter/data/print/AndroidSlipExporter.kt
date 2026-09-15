package com.example.snapstoneprinter.data.print

import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.example.snapstoneprinter.image.PrintSlip
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal fun interface PngEncoder {
    fun encode(bitmap: Bitmap, output: OutputStream): Boolean
}

class AndroidSlipExporter internal constructor(
    private val application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val encoder: PngEncoder = PngEncoder { bitmap, output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
) : SlipExporter {
    private class OwnedDirectory(val directory: File, val imagesRoot: File) {
        val files = mutableListOf<File>()
    }

    private val registry = IdentityHashMap<ExportedSlips, OwnedDirectory>()
    private val registryMutex = Mutex()

    override suspend fun export(jobId: String, slips: List<PrintSlip>): ExportedSlips {
        require(isCanonicalJobId(jobId)) { "Expected a canonical UUID job ID." }
        require(slips.isNotEmpty()) { "At least one slip is required." }
        val snapshot = slips.toList()
        var owned: OwnedDirectory? = null
        var batch: ExportedSlips? = null
        try {
            return withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                val imagesRoot = File(application.cacheDir.canonicalFile, "images")
                if (imagesRoot.canonicalFile != imagesRoot ||
                    (!imagesRoot.isDirectory && !imagesRoot.mkdirs())) {
                    throw IOException("Could not create the image cache directory.")
                }
                val directory = File(imagesRoot, jobId)
                if (!directory.mkdir()) throw IOException("Could not create a new print-job directory.")
                val ownership = OwnedDirectory(directory, imagesRoot)
                owned = ownership
                val uris = snapshot.mapIndexed { index, slip ->
                    currentCoroutineContext().ensureActive()
                    val file = File(directory, "slip_$index.png")
                    if (!file.createNewFile()) throw IOException("Could not create a new slip file.")
                    ownership.files += file
                    FileOutputStream(file).use { output ->
                        if (!encoder.encode(slip.bitmap, output)) throw IOException("Could not encode slip PNG.")
                    }
                    FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file).toString()
                }
                currentCoroutineContext().ensureActive()
                val result = ExportedSlips(jobId, Collections.unmodifiableList(ArrayList(uris)))
                batch = result
                registryMutex.withLock { registry[result] = ownership }
                currentCoroutineContext().ensureActive()
                result
            }
        } catch (failure: Throwable) {
            owned?.let { ownership ->
                try {
                    cleanupContext {
                        registryMutex.withLock {
                            batch?.let { registry.remove(it) }
                            deleteOwned(ownership)
                        }
                    }
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    override suspend fun discardUnshared(batch: ExportedSlips) {
        cleanupContext {
            registryMutex.withLock {
                val owned = requireNotNull(registry[batch]) { "Batch is not owned by this exporter." }
                deleteOwned(owned)
                registry.remove(batch)
            }
        }
    }

    private suspend fun cleanupContext(cleanup: suspend () -> Unit) {
        withContext(NonCancellable + ioDispatcher) {
            withTimeout(5_000) { cleanup() }
        }
    }

    private suspend fun deleteOwned(owned: OwnedDirectory) {
        val directory = owned.directory
        if (owned.imagesRoot.canonicalFile != owned.imagesRoot ||
            directory.canonicalFile != directory || directory.parentFile != owned.imagesRoot) {
            throw IOException("Print-job directory ownership changed; cleanup refused.")
        }
        for (file in owned.files) {
            currentCoroutineContext().ensureActive()
            if (file.parentFile != directory || file.canonicalFile != file) {
                throw IOException("Slip path ownership changed; cleanup refused.")
            }
            if (file.exists() && !file.delete()) throw IOException("Could not remove an unshared slip.")
        }
        currentCoroutineContext().ensureActive()
        if (directory.exists() && !directory.delete()) throw IOException("Could not remove the unshared job directory.")
    }
}
