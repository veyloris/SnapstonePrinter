package com.example.snapstoneprinter.image

import com.example.snapstoneprinter.data.model.ScryfallCard
import com.example.snapstoneprinter.data.model.CardFace
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFixturePlanningTest {
    private val adapter = Moshi.Builder().build().adapter(ScryfallCard::class.java)

    private fun fixture(name: String): ScryfallCard = requireNotNull(
        adapter.fromJson(requireNotNull(javaClass.getResource("/scryfall/$name.json")).readText())
    )

    @Test
    fun splitPrimaryDoesNotUseCombinedCostOrType() {
        val card = fixture("fire-ice")
        val slip = SlipPlanner.plan(card).single()
        assertEquals("Fire // Ice", card.effectiveName)
        assertEquals(listOf("Fire", "{1}{R}", "Instant"), listOf(slip.name, slip.manaCost, slip.typeLine))
        val secondary = slip.secondaryFaces.single()
        assertEquals(listOf("Ice", "{1}{U}", "Instant"), listOf(secondary.name, secondary.manaCost, secondary.typeLine))
        assertEquals(card.effectiveImageUrl, slip.artUrl)
        assertEquals(card.effectiveNormalUrl, slip.fullImageUrl)
    }

    @Test
    fun adventurePrimaryUsesCreatureFields() {
        val card = fixture("bonecrusher-giant")
        val slip = SlipPlanner.plan(card).single()
        assertEquals(listOf("Bonecrusher Giant", "{2}{R}", "Creature — Giant", "4", "3"),
            listOf(slip.name, slip.manaCost, slip.typeLine, slip.power, slip.toughness))
        val secondary = slip.secondaryFaces.single()
        assertEquals(listOf("Stomp", "{1}{R}", "Instant — Adventure"),
            listOf(secondary.name, secondary.manaCost, secondary.typeLine))
    }

    @Test
    fun jaceKeepsStartingLoyalty() {
        val card = fixture("jace")
        assertEquals("3", card.loyalty)
        val slip = SlipPlanner.plan(card).single()
        assertEquals("3", slip.loyalty)
        assertNull(slip.defense)
    }

    @Test
    fun battleFrontKeepsDefenseAndBackKeepsPowerToughness() {
        val card = fixture("invasion-zendikar")
        assertEquals("3", card.card_faces!!.first().defense)
        val (front, back) = SlipPlanner.plan(card)
        assertEquals(listOf("Invasion of Zendikar", null, null, null, "3"),
            listOf(front.name, front.power, front.toughness, front.loyalty, front.defense))
        assertEquals(listOf("Awakened Skyclave", "4", "4", null, null),
            listOf(back.name, back.power, back.toughness, back.loyalty, back.defense))
        assertTrue(front.secondaryFaces.isEmpty())
        assertTrue(back.secondaryFaces.isEmpty())
    }

    @Test
    fun faceBlankCostDoesNotInheritOtherCost() {
        val card = fixture("fire-ice")
        val faces = card.card_faces!!
        val changed = card.copy(
            mana_cost = "{9}", type_line = "Aggregate", oracle_text = "Aggregate rules",
            power = "9", toughness = "9", loyalty = "9", defense = "9",
            card_faces = listOf(
                faces[0].copy(name = " ", mana_cost = " ", type_line = null, oracle_text = "",
                    power = null, toughness = "", loyalty = null, defense = " "),
                faces[1].copy(loyalty = "0", defense = "X")
            )
        )
        val slip = SlipPlanner.plan(changed).single()
        assertEquals(card.name, slip.name)
        assertEquals(List<String?>(7) { null }, listOf(slip.manaCost, slip.typeLine, slip.oracleText,
            slip.power, slip.toughness, slip.loyalty, slip.defense))
        assertEquals("0", slip.secondaryFaces.single().loyalty)
        assertEquals("X", slip.secondaryFaces.single().defense)
    }

    @Test
    fun perFaceStatisticsDoNotInheritAggregateOrSiblingValues() {
        val card = fixture("invasion-zendikar")
        val faces = card.card_faces!!
        val changed = card.copy(loyalty = "9", defense = "9", card_faces = listOf(
            faces[0].copy(loyalty = "0"), faces[1]
        ))
        val (front, back) = SlipPlanner.plan(changed)
        assertEquals("0", front.loyalty)
        assertEquals("3", front.defense)
        assertNull(back.loyalty)
        assertNull(back.defense)
    }

    @Test
    fun normalAndMeldPlanUnchanged() {
        for (layout in listOf("normal", "meld")) {
            val card = ScryfallCard(name = "Legacy", mana_cost = "{2}", type_line = "Creature",
                oracle_text = "Rules", power = "2", toughness = "3", layout = layout)
            val slip = SlipPlanner.plan(card).single()
            assertEquals(listOf("Legacy", "{2}", "Creature", "Rules", "2", "3"),
                listOf(slip.name, slip.manaCost, slip.typeLine, slip.oracleText, slip.power, slip.toughness))
            assertNull(slip.loyalty)
            assertNull(slip.defense)
            assertNull(slip.label)
            assertTrue(slip.secondaryFaces.isEmpty())
        }
    }

    @Test
    fun legacySingleFaceResolversPreferTopLevelThenPrimaryFace() {
        val card = ScryfallCard(name = "Legacy", loyalty = "2", defense = " ", card_faces = listOf(
            CardFace(name = "Face", loyalty = "3", defense = "0")
        ))
        assertEquals("2", card.effectiveLoyalty)
        assertEquals("0", card.effectiveDefense)
        val slip = SlipPlanner.plan(card).single()
        assertEquals("Legacy", slip.name)
        assertEquals("2", slip.loyalty)
        assertEquals("0", slip.defense)
    }

    @Test
    fun absentStatisticsDeserializeAsNull() {
        val card = requireNotNull(adapter.fromJson("""{"name":"No stats","card_faces":[{"name":"Face"}]}"""))
        assertNull(card.loyalty)
        assertNull(card.defense)
        assertNull(card.card_faces!!.single().loyalty)
        assertNull(card.card_faces!!.single().defense)
    }

    @Test
    fun unnamedSecondaryFacesRemainOmitted() {
        val card = fixture("fire-ice")
        val faces = card.card_faces!!
        assertTrue(SlipPlanner.plan(card.copy(card_faces = listOf(faces[0], faces[1].copy(name = " "))))
            .single().secondaryFaces.isEmpty())
    }
}
