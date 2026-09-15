package com.example.snapstoneprinter.data.print

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.image.PrintSlip
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSlipExporterTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun slip(color: Int): PrintSlip {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return PrintSlip(bitmap, "../Card title", 0, 1, null)
    }

    @Test
    fun exportsOrderedReadablePngsAndRejectsForgedCleanup() = runBlocking {
        val id = UUID.randomUUID().toString()
        val exporter = AndroidSlipExporter(application, Dispatchers.Unconfined)
        val batch = exporter.export(id, listOf(slip(Color.BLACK), slip(Color.WHITE)))
        assertEquals(id, batch.jobId)
        assertEquals(2, batch.uris.size)
        for ((index, uri) in batch.uris.withIndex()) {
            assertEquals("content", Uri.parse(uri).scheme)
            val bitmap = application.contentResolver.openInputStream(Uri.parse(uri)).use { BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap)
            requireNotNull(bitmap)
            assertEquals(4, bitmap.width)
            assertEquals(3, bitmap.height)
            assertEquals(if (index == 0) Color.BLACK else Color.WHITE, bitmap.getPixel(0, 0))
            assertTrue(File(application.cacheDir, "images/$id/slip_$index.png").isFile)
        }
        try {
            exporter.discardUnshared(batch.copy())
            fail("A structurally equal but unknown batch must not own cleanup")
        } catch (_: IllegalArgumentException) {
            assertTrue(File(application.cacheDir, "images/$id/slip_0.png").exists())
        }
        exporter.discardUnshared(batch)
        assertFalse(File(application.cacheDir, "images/$id").exists())
        try {
            exporter.discardUnshared(batch)
            fail("Discarded batches must no longer authorize cleanup")
        } catch (_: IllegalArgumentException) {
            // Rejected batch identity owns no files.
        }
    }

    @Test
    fun compressFalseAndPartialWriteFail() = runBlocking {
        for (throwOnSecond in listOf(false, true)) {
            val id = UUID.randomUUID().toString()
            val neighbor = File(application.cacheDir, "images/sentinel-${UUID.randomUUID()}.txt")
            neighbor.parentFile!!.mkdirs()
            neighbor.writeText("keep")
            var calls = 0
            val exporter = AndroidSlipExporter(application, Dispatchers.Unconfined, PngEncoder { bitmap, output ->
                calls++
                if (calls == 2) {
                    if (throwOnSecond) throw IOException("second write failed")
                    false
                } else bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            })
            try {
                exporter.export(id, listOf(slip(Color.BLACK), slip(Color.WHITE)))
                fail("Partial or rejected PNG output cannot succeed")
            } catch (_: IOException) {
                assertFalse(File(application.cacheDir, "images/$id").exists())
                assertEquals("keep", neighbor.readText())
            } finally {
                neighbor.delete()
            }
        }
    }

    @Test
    fun invalidIdsEmptyInputAndExistingDirectoriesFailWithoutDeleting() = runBlocking {
        val exporter = AndroidSlipExporter(application, Dispatchers.Unconfined)
        for (id in listOf("../outside", "", "1-1-1-1-1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
            try {
                exporter.export(id, listOf(slip(Color.BLACK)))
                fail("Invalid ID must be rejected")
            } catch (_: IllegalArgumentException) {
                // ID validation precedes directory creation.
            }
        }
        val id = UUID.randomUUID().toString()
        try {
            exporter.export(id, emptyList())
            fail("Empty input must be rejected")
        } catch (_: IllegalArgumentException) {
            assertFalse(File(application.cacheDir, "images/$id").exists())
        }
        val existing = File(application.cacheDir, "images/$id")
        existing.mkdirs()
        val sentinel = File(existing, "sentinel")
        sentinel.writeText("keep")
        try {
            exporter.export(id, listOf(slip(Color.BLACK)))
            fail("An existing directory is not owned by this export")
        } catch (_: IOException) {
            assertEquals("keep", sentinel.readText())
        } finally {
            sentinel.delete()
            existing.delete()
        }
    }

    @Test
    fun cancellationImmediatelyBeforeCompletionCleansOnlyOwnedFiles() = runBlocking {
        val id = UUID.randomUUID().toString()
        lateinit var child: Job
        val exporter = AndroidSlipExporter(application, Dispatchers.Unconfined, PngEncoder { bitmap, output ->
            val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            child.cancel(CancellationException("cancel before return"))
            encoded
        })
        val result = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            exporter.export(id, listOf(slip(Color.BLACK)))
        }
        child = result
        result.start()
        try {
            result.await()
            fail("Cancelled export must not return success")
        } catch (_: CancellationException) {
            assertFalse(File(application.cacheDir, "images/$id").exists())
        }
    }

    @Test
    fun failedBatchPreservesEarlierSuccessfulBatch() = runBlocking {
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        var calls = 0
        val exporter = AndroidSlipExporter(application, Dispatchers.Unconfined, PngEncoder { bitmap, output ->
            calls++
            if (calls == 1) bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) else false
        })
        val successful = exporter.export(firstId, listOf(slip(Color.BLACK)))
        try {
            exporter.export(secondId, listOf(slip(Color.WHITE)))
            fail("Rejected encoding must fail only its own batch")
        } catch (_: IOException) {
            assertFalse(File(application.cacheDir, "images/$secondId").exists())
            val decoded = application.contentResolver.openInputStream(Uri.parse(successful.uris.single())).use {
                BitmapFactory.decodeStream(it)
            }
            assertEquals(Color.BLACK, requireNotNull(decoded).getPixel(0, 0))
        } finally {
            exporter.discardUnshared(successful)
        }
    }
}
