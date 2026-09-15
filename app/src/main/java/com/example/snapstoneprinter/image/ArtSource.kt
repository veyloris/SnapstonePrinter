package com.example.snapstoneprinter.image

interface ArtSource {
    suspend fun fetch(url: String): ArtResult
}
