package com.example.snapstoneprinter.data.repository

import com.example.snapstoneprinter.data.api.ScryfallApiService
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CardRepositoryTest {

    /**
     * @param layouts layout value handed back per successive call; the last value repeats forever.
     */
    private class FakeScryfallApiService(
        private val layouts: List<String> = listOf("normal"),
        private val cardByNameLayout: String = "normal"
    ) : ScryfallApiService {
        var lastQuery: String? = "UNINITIALIZED"
        var callCount = 0
        var lastFuzzy: String? = "UNINITIALIZED"

        override suspend fun getRandomCard(query: String?): ScryfallCard {
            lastQuery = query
            val layout = layouts[minOf(callCount, layouts.lastIndex)]
            callCount++
            return ScryfallCard(
                name = "Black Lotus",
                mana_cost = "{0}",
                type_line = "Artifact",
                oracle_text = "T, Sacrifice Black Lotus: Add three mana of any one color.",
                power = null,
                toughness = null,
                image_uris = ImageUris(artCrop = "https://example.com/art.jpg"),
                layout = layout
            )
        }

        override suspend fun getCardByName(fuzzy: String): ScryfallCard {
            lastFuzzy = fuzzy
            return ScryfallCard(
                name = fuzzy,
                image_uris = ImageUris(artCrop = "https://example.com/art.jpg"),
                layout = cardByNameLayout
            )
        }
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun testGetRandomCard_noToggle_excludesLands() = runTest {
        val fakeApi = FakeScryfallApiService()
        val repository = CardRepository(fakeApi)
        val card = repository.getRandomCard(isFunny = false)

        assertEquals("Black Lotus", card.name)
        assertEquals("-t:land -is:funny -is:extra", fakeApi.lastQuery)
    }

    @Test
    fun testGetRandomCard_withFunnyToggle_excludesLands() = runTest {
        val fakeApi = FakeScryfallApiService()
        val repository = CardRepository(fakeApi)
        val card = repository.getRandomCard(isFunny = true)

        assertEquals("Black Lotus", card.name)
        assertEquals("-t:land -is:extra", fakeApi.lastQuery)
    }

    // ------------------------------------------------- junk layout re-rolling

    @Test
    fun testPlayableCard_isReturnedWithoutReroll() = runTest {
        val fakeApi = FakeScryfallApiService(listOf("normal"))
        val repository = CardRepository(fakeApi)
        repository.getRandomCard()

        assertEquals(1, fakeApi.callCount)
    }

    @Test
    fun testJunkLayouts_triggerReroll() = runTest {
        val fakeApi = FakeScryfallApiService(listOf("token", "emblem", "art_series", "normal"))
        val repository = CardRepository(fakeApi)
        val card = repository.getRandomCard()

        assertEquals(4, fakeApi.callCount)
        assertEquals("normal", card.layout)
    }

    @Test
    fun testDoubleFacedToken_triggersReroll() = runTest {
        val fakeApi = FakeScryfallApiService(listOf("double_faced_token", "transform"))
        val repository = CardRepository(fakeApi)
        val card = repository.getRandomCard()

        assertEquals(2, fakeApi.callCount)
        assertEquals("transform", card.layout)
    }

    @Test
    fun testRerollIsCapped() = runTest {
        val fakeApi = FakeScryfallApiService(listOf("token"))
        val repository = CardRepository(fakeApi)
        val card = repository.getRandomCard()

        assertEquals(CardRepository.MAX_REROLL_ATTEMPTS, fakeApi.callCount)
        // Best-effort fallback rather than an exception.
        assertEquals("token", card.layout)
    }

    // -------------------------------------------------------- named lookup

    @Test
    fun testGetCardByName_passesQueryThroughAsFuzzy() = runTest {
        val fakeApi = FakeScryfallApiService()
        val repository = CardRepository(fakeApi)
        val card = repository.getCardByName("fire ice")

        assertEquals("fire ice", fakeApi.lastFuzzy)
        assertEquals("fire ice", card.name)
    }

    @Test
    fun testGetCardByName_doesNotRerollJunkLayouts() = runTest {
        // Unlike getRandomCard, a named lookup never re-rolls - the caller asked for THIS card.
        // The fake deliberately returns a JUNK layout here: if getCardByName were ever routed
        // through the reroll path, this card would trigger it, callCount would rise above 0, and
        // the returned layout would end up as the reroll's fallback rather than "token".
        val fakeApi = FakeScryfallApiService(cardByNameLayout = "token")
        val repository = CardRepository(fakeApi)
        val card = repository.getCardByName("black lotus")

        assertEquals(0, fakeApi.callCount)
        assertEquals("token", card.layout)
    }

    @Test
    fun testMultiFacedLayoutsAreNotTreatedAsJunk() = runTest {
        for (layout in listOf("transform", "modal_dfc", "split", "flip", "adventure", "saga")) {
            val fakeApi = FakeScryfallApiService(listOf(layout))
            val repository = CardRepository(fakeApi)
            repository.getRandomCard()
            assertTrue("$layout should not be re-rolled", fakeApi.callCount == 1)
        }
    }

    // -------------------------------------------------------- MomirVig

    @Test
    fun testGetMomirVigCreature_noToggle_filtersByCmcAndCreature() = runTest {
        val fakeApi = FakeScryfallApiService()
        val repository = CardRepository(fakeApi)
        repository.getMomirVigCreature(cmc = 3, isFunny = false)

        assertEquals("cmc=3 t:creature -is:funny -is:extra", fakeApi.lastQuery)
    }

    @Test
    fun testGetMomirVigCreature_withFunnyToggle() = runTest {
        val fakeApi = FakeScryfallApiService()
        val repository = CardRepository(fakeApi)
        repository.getMomirVigCreature(cmc = 0, isFunny = true)

        assertEquals("cmc=0 t:creature -is:extra", fakeApi.lastQuery)
    }

    @Test
    fun testGetMomirVigCreature_rerollsJunkLayouts() = runTest {
        val fakeApi = FakeScryfallApiService(listOf("token", "normal"))
        val repository = CardRepository(fakeApi)
        val card = repository.getMomirVigCreature(cmc = 5)

        assertEquals(2, fakeApi.callCount)
        assertEquals("normal", card.layout)
    }

    @Test
    fun invalidCmcRejectsBeforeApiCall() = runTest {
        val fakeApi = FakeScryfallApiService()
        val repository = CardRepository(fakeApi)

        for (invalidCmc in listOf(-1, 17)) {
            for (isFunny in listOf(false, true)) {
                try {
                    repository.getMomirVigCreature(cmc = invalidCmc, isFunny = isFunny)
                    fail("Expected IllegalArgumentException for cmc=$invalidCmc")
                } catch (e: IllegalArgumentException) {
                    assertEquals(0, fakeApi.callCount)
                    assertEquals("UNINITIALIZED", fakeApi.lastQuery)
                }
            }
        }
    }
}
