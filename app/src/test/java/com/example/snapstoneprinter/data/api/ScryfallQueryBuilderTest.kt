package com.example.snapstoneprinter.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScryfallQueryBuilderTest {

    @Test
    fun snapstoneOffExcludesFunny() {
        assertEquals("-t:land -is:funny -is:extra", ScryfallQueryBuilder.build(false))
        assertEquals("-t:land -is:funny -is:extra", ScryfallQueryBuilder.build())
    }

    @Test
    fun snapstoneOnIncludesOrdinaryAndFunnyPool() {
        assertEquals("-t:land -is:extra", ScryfallQueryBuilder.build(true))
    }

    @Test
    fun momirOffExcludesFunny() {
        for (cmc in listOf(0, 3, 16)) {
            assertEquals("cmc=$cmc t:creature -is:funny -is:extra", ScryfallQueryBuilder.buildMomirVig(cmc, false))
            assertEquals("cmc=$cmc t:creature -is:funny -is:extra", ScryfallQueryBuilder.buildMomirVig(cmc))
        }
    }

    @Test
    fun momirOnPreservesCreatureAndCmc() {
        for (cmc in listOf(0, 3, 16)) {
            assertEquals("cmc=$cmc t:creature -is:extra", ScryfallQueryBuilder.buildMomirVig(cmc, true))
        }
    }

    @Test
    fun invalidCmcRejectsBothToggleStates() {
        for (cmc in listOf(-1, 17)) {
            for (isFunny in listOf(false, true)) {
                assertThrows(IllegalArgumentException::class.java) {
                    ScryfallQueryBuilder.buildMomirVig(cmc, isFunny)
                }
            }
        }
    }
}
