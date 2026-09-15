package com.example.snapstoneprinter.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

class PrintReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receipt = readReceipt(intent)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val receiptView = TextView(this).apply {
            text = receipt.toString()
            contentDescription = "print-receiver-receipt"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        column.addView(ScrollView(this).apply { addView(receiptView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        for ((label, result) in listOf("Return OK" to RESULT_OK, "Return Cancel" to RESULT_CANCELED)) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    setResult(result)
                    finish()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(column)
    }

    private fun readReceipt(incoming: Intent): JSONObject {
        val receipt = JSONObject()
            .put("action", incoming.action ?: JSONObject.NULL)
            .put("mime", incoming.type ?: JSONObject.NULL)
            .put("uid", Process.myUid())
            .put("readGrant", incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            .put("writeGrant", incoming.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            .put("error", JSONObject.NULL)
        try {
            val stream = incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            val clip = incoming.clipData
            val clipUri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            val index = stream?.lastPathSegment?.let { filename ->
                Regex("slip_([0-9]+)\\.png").matchEntire(filename)?.groupValues?.get(1)?.toIntOrNull()
            }
            receipt.put("streamUri", stream?.toString() ?: JSONObject.NULL)
                .put("clipUri", clipUri?.toString() ?: JSONObject.NULL)
                .put("clipCount", clip?.itemCount ?: 0)
                .put("urisMatch", stream != null && stream == clipUri && clip?.itemCount == 1)
                .put("slipIndex", index ?: JSONObject.NULL)
            require(incoming.action == Intent.ACTION_SEND && incoming.type == "image/png") {
                "Expected ACTION_SEND image/png."
            }
            require(stream != null && stream.scheme == "content") { "Expected a content URI stream." }
            val bitmap = contentResolver.openInputStream(stream).use { input ->
                BitmapFactory.decodeStream(input) ?: throw IOException("The shared stream did not decode as an image.")
            }
            try {
                receipt.put("width", bitmap.width)
                    .put("height", bitmap.height)
                    .put("pixelSha256", pixelHash(bitmap))
            } finally {
                bitmap.recycle()
            }
        } catch (failure: Exception) {
            receipt.put("error", "${failure.javaClass.simpleName}: ${failure.message}")
        }
        return receipt
    }

    private fun pixelHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) {
                digest.update((pixel ushr 24).toByte())
                digest.update((pixel ushr 16).toByte())
                digest.update((pixel ushr 8).toByte())
                digest.update(pixel.toByte())
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
