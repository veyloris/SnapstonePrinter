package com.example.snapstoneprinter.data.print

import com.example.snapstoneprinter.image.PrintSlip

data class ExportedSlips(val jobId: String, val uris: List<String>)

interface SlipExporter {
    suspend fun export(jobId: String, slips: List<PrintSlip>): ExportedSlips
    suspend fun discardUnshared(batch: ExportedSlips)
}
