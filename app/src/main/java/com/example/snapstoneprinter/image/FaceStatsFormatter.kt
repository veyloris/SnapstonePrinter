package com.example.snapstoneprinter.image

object FaceStatsFormatter {
    fun format(power: String?, toughness: String?, loyalty: String?, defense: String?): List<String> = buildList {
        if (!power.isNullOrBlank() && !toughness.isNullOrBlank()) {
            add("${power.trim()}/${toughness.trim()}")
        }
        if (!loyalty.isNullOrBlank()) add("Loyalty: ${loyalty.trim()}")
        if (!defense.isNullOrBlank()) add("Defense: ${defense.trim()}")
    }
}
