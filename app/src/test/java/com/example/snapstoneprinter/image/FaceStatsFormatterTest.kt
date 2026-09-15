package com.example.snapstoneprinter.image

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceStatsFormatterTest {
    @Test
    fun statsKeepZeroAndVariableStrings() {
        assertEquals(listOf("*/1+*", "Loyalty: 0", "Defense: X"),
            FaceStatsFormatter.format(" * ", " 1+* ", " 0 ", " X "))
    }

    @Test
    fun statsAbsentProducesNoLine() {
        assertEquals(emptyList<String>(), FaceStatsFormatter.format(null, null, null, null))
        assertEquals(emptyList<String>(), FaceStatsFormatter.format(" ", "", " ", ""))
    }

    @Test
    fun incompletePowerToughnessDoesNotHideOtherStats() {
        assertEquals(listOf("Loyalty: 3"), FaceStatsFormatter.format("2", null, "3", null))
        assertEquals(listOf("Defense: 0"), FaceStatsFormatter.format(" ", "2", null, "0"))
    }

    @Test
    fun eachStatKindCanStandAlone() {
        assertEquals(listOf("0/0"), FaceStatsFormatter.format("0", "0", null, null))
        assertEquals(listOf("Loyalty: X"), FaceStatsFormatter.format(null, null, "X", null))
        assertEquals(listOf("Defense: 3"), FaceStatsFormatter.format(null, null, null, "3"))
    }
}
