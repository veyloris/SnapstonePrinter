package com.example.snapstoneprinter.image

import com.example.snapstoneprinter.data.model.CardFace
import com.example.snapstoneprinter.data.model.ImageUris
import com.example.snapstoneprinter.data.model.ScryfallCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-slip decision is structural, not layout-driven: `card_faces[]` is non-empty AND every
 * face owns its own `image_uris`.
 *
 * transform / modal_dfc / reversible_card satisfy that (two physical printed sides, two pieces of
 * art). split / flip / adventure do NOT - they are one physical card with one shared top-level
 * `image_uris`, so they must stay on one slip. meld arrives from /cards/random as an individual
 * card with no faces at all.
 */
class SlipPlannerTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun perFaceArt(url: String) = ImageUris(artCrop = url, normal = "$url-normal")

    private val transformCard = ScryfallCard(
        name = "Delver of Secrets // Insectile Aberration",
        layout = "transform",
        card_faces = listOf(
            CardFace(
                name = "Delver of Secrets",
                mana_cost = "{U}",
                type_line = "Creature — Human Wizard",
                oracle_text = "At the beginning of your upkeep, look at the top card of your library.",
                power = "1",
                toughness = "1",
                image_uris = perFaceArt("https://img/delver-front.jpg")
            ),
            CardFace(
                name = "Insectile Aberration",
                mana_cost = "",
                type_line = "Creature — Human Insect",
                oracle_text = "Flying",
                power = "3",
                toughness = "2",
                image_uris = perFaceArt("https://img/delver-back.jpg")
            )
        )
    )

    private val modalDfcCard = ScryfallCard(
        name = "Agadeem's Awakening // Agadeem, the Undercrypt",
        layout = "modal_dfc",
        card_faces = listOf(
            CardFace(
                name = "Agadeem's Awakening",
                mana_cost = "{X}{B}{B}{B}",
                type_line = "Sorcery",
                image_uris = perFaceArt("https://img/agadeem-front.jpg")
            ),
            CardFace(
                name = "Agadeem, the Undercrypt",
                type_line = "Land",
                image_uris = perFaceArt("https://img/agadeem-back.jpg")
            )
        )
    )

    private val reversibleCard = ScryfallCard(
        name = "Propaganda // Propaganda",
        layout = "reversible_card",
        card_faces = listOf(
            CardFace(
                name = "Propaganda",
                mana_cost = "{2}{U}",
                type_line = "Enchantment",
                image_uris = perFaceArt("https://img/propaganda-a.jpg")
            ),
            CardFace(
                name = "Propaganda (Alt)",
                mana_cost = "{2}{U}",
                type_line = "Enchantment",
                image_uris = perFaceArt("https://img/propaganda-b.jpg")
            )
        )
    )

    /** Fire // Ice: two faces, but a SINGLE shared top-level image. Must stay one slip. */
    private val splitCard = ScryfallCard(
        name = "Fire // Ice",
        type_line = "Instant // Instant",
        image_uris = ImageUris(artCrop = "https://img/fire-ice.jpg"),
        layout = "split",
        card_faces = listOf(
            CardFace(name = "Fire", mana_cost = "{1}{R}", type_line = "Instant", oracle_text = "Deal 2 damage divided as you choose."),
            CardFace(name = "Ice", mana_cost = "{1}{U}", type_line = "Instant", oracle_text = "Tap target permanent. Draw a card.")
        )
    )

    private val flipCard = ScryfallCard(
        name = "Erayo, Soratami Ascendant // Erayo's Essence",
        image_uris = ImageUris(artCrop = "https://img/erayo.jpg"),
        layout = "flip",
        card_faces = listOf(
            CardFace(name = "Erayo, Soratami Ascendant", mana_cost = "{1}{U}", type_line = "Legendary Creature — Moonfolk Monk", power = "1", toughness = "1"),
            CardFace(name = "Erayo's Essence", type_line = "Legendary Enchantment")
        )
    )

    private val adventureCard = ScryfallCard(
        name = "Brazen Borrower // Petty Theft",
        image_uris = ImageUris(artCrop = "https://img/brazen-borrower.jpg"),
        layout = "adventure",
        card_faces = listOf(
            CardFace(name = "Brazen Borrower", mana_cost = "{1}{U}{U}", type_line = "Creature — Faerie Rogue", power = "3", toughness = "1"),
            CardFace(name = "Petty Theft", mana_cost = "{1}{U}", type_line = "Instant — Adventure")
        )
    )

    /** meld results arrive from /cards/random as individual cards - no card_faces at all. */
    private val meldCard = ScryfallCard(
        name = "Bruna, the Fading Light",
        mana_cost = "{5}{W}{W}",
        type_line = "Legendary Creature — Angel Horror",
        oracle_text = "When you cast this spell, you may return target Angel or Human creature card from your graveyard to the battlefield.",
        power = "5",
        toughness = "7",
        image_uris = ImageUris(artCrop = "https://img/bruna.jpg"),
        layout = "meld"
    )

    private val normalCard = ScryfallCard(
        name = "Colossal Dreadmaw",
        mana_cost = "{4}{G}{G}",
        type_line = "Creature — Dinosaur",
        oracle_text = "Trample",
        power = "6",
        toughness = "6",
        image_uris = ImageUris(artCrop = "https://img/dreadmaw.jpg"),
        layout = "normal"
    )

    /** Passes the structural test but carries a layout the app has never seen. */
    private val unknownLayoutCard = ScryfallCard(
        name = "Front Side // Back Side",
        layout = "some_future_dfc_layout",
        card_faces = listOf(
            CardFace(name = "Front Side", type_line = "Artifact", image_uris = perFaceArt("https://img/future-a.jpg")),
            CardFace(name = "Back Side", type_line = "Artifact", image_uris = perFaceArt("https://img/future-b.jpg"))
        )
    )

    // ------------------------------------------------------------------
    // Slip count
    // ------------------------------------------------------------------

    @Test
    fun testTwoSlips_forTransform() {
        assertTrue(SlipPlanner.hasPerFaceArt(transformCard))
        assertEquals(2, SlipPlanner.slipCount(transformCard))
        assertEquals(2, SlipPlanner.plan(transformCard).size)
    }

    @Test
    fun testTwoSlips_forModalDfc() {
        assertTrue(SlipPlanner.hasPerFaceArt(modalDfcCard))
        assertEquals(2, SlipPlanner.slipCount(modalDfcCard))
    }

    @Test
    fun testTwoSlips_forReversibleCard() {
        assertTrue(SlipPlanner.hasPerFaceArt(reversibleCard))
        assertEquals(2, SlipPlanner.slipCount(reversibleCard))
    }

    @Test
    fun testOneSlip_forSplit() {
        // Regression: keying off card_faces presence alone made Fire // Ice print twice.
        assertFalse(SlipPlanner.hasPerFaceArt(splitCard))
        assertEquals(1, SlipPlanner.slipCount(splitCard))
        assertEquals(1, SlipPlanner.plan(splitCard).size)
    }

    @Test
    fun testOneSlip_forFlip() {
        assertFalse(SlipPlanner.hasPerFaceArt(flipCard))
        assertEquals(1, SlipPlanner.slipCount(flipCard))
    }

    @Test
    fun testOneSlip_forAdventure() {
        assertFalse(SlipPlanner.hasPerFaceArt(adventureCard))
        assertEquals(1, SlipPlanner.slipCount(adventureCard))
    }

    @Test
    fun testOneSlip_forMeld() {
        assertFalse(SlipPlanner.hasPerFaceArt(meldCard))
        assertEquals(1, SlipPlanner.slipCount(meldCard))
    }

    @Test
    fun testOneSlip_forNormal() {
        assertFalse(SlipPlanner.hasPerFaceArt(normalCard))
        assertEquals(1, SlipPlanner.slipCount(normalCard))
    }

    @Test
    fun testOneSlip_whenOnlySomeFacesHaveArt() {
        val half = transformCard.copy(
            card_faces = listOf(
                transformCard.card_faces!![0],
                transformCard.card_faces!![1].copy(image_uris = null)
            )
        )
        assertFalse(SlipPlanner.hasPerFaceArt(half))
        assertEquals(1, SlipPlanner.slipCount(half))
    }

    @Test
    fun testOneSlip_whenFaceImageUrisIsEmpty() {
        val blank = transformCard.copy(
            card_faces = transformCard.card_faces!!.map { it.copy(image_uris = ImageUris()) }
        )
        assertFalse(SlipPlanner.hasPerFaceArt(blank))
        assertEquals(1, SlipPlanner.slipCount(blank))
    }

    @Test
    fun testOneSlip_whenSingleFaceHasArt() {
        val oneFace = transformCard.copy(card_faces = listOf(transformCard.card_faces!![0]))
        assertFalse(SlipPlanner.hasPerFaceArt(oneFace))
        assertEquals(1, SlipPlanner.slipCount(oneFace))
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    fun testTransformLabels_areDirectional() {
        assertEquals("Transforms into: Insectile Aberration", SlipPlanner.labelFor(transformCard, 0))
        assertEquals("Transforms from: Delver of Secrets", SlipPlanner.labelFor(transformCard, 1))
    }

    @Test
    fun testModalDfcLabels() {
        assertEquals("Other side: Agadeem, the Undercrypt", SlipPlanner.labelFor(modalDfcCard, 0))
        assertEquals("Other side: Agadeem's Awakening", SlipPlanner.labelFor(modalDfcCard, 1))
    }

    @Test
    fun testReversibleCardLabels() {
        assertEquals("Other side: Propaganda (Alt)", SlipPlanner.labelFor(reversibleCard, 0))
        assertEquals("Other side: Propaganda", SlipPlanner.labelFor(reversibleCard, 1))
    }

    @Test
    fun testUnknownMultiFaceLayout_fallsBackToOtherSide() {
        assertEquals("Other side: Back Side", SlipPlanner.labelFor(unknownLayoutCard, 0))
        assertEquals("Other side: Front Side", SlipPlanner.labelFor(unknownLayoutCard, 1))
    }

    @Test
    fun testNullLayoutWithPerFaceArt_fallsBackToOtherSide() {
        val noLayout = transformCard.copy(layout = null)
        assertEquals("Other side: Insectile Aberration", SlipPlanner.labelFor(noLayout, 0))
    }

    @Test
    fun testLayoutMatchingIsCaseAndWhitespaceInsensitive() {
        val messy = transformCard.copy(layout = "  TRANSFORM ")
        assertEquals("Transforms into: Insectile Aberration", SlipPlanner.labelFor(messy, 0))
    }

    @Test
    fun testSingleSlipCards_haveNoLabel() {
        assertNull(SlipPlanner.labelFor(splitCard, 0))
        assertNull(SlipPlanner.labelFor(flipCard, 0))
        assertNull(SlipPlanner.labelFor(adventureCard, 0))
        assertNull(SlipPlanner.labelFor(meldCard, 0))
        assertNull(SlipPlanner.labelFor(normalCard, 0))
        assertNull(SlipPlanner.plan(splitCard).single().label)
        assertNull(SlipPlanner.plan(normalCard).single().label)
    }

    // ------------------------------------------------------------------
    // Plan contents
    // ------------------------------------------------------------------

    @Test
    fun testPlan_usesPerFaceDataAndPerFaceArt() {
        val (front, back) = SlipPlanner.plan(transformCard)

        assertEquals("Delver of Secrets", front.name)
        assertEquals("{U}", front.manaCost)
        assertEquals("Creature — Human Wizard", front.typeLine)
        assertEquals("1", front.power)
        assertEquals("1", front.toughness)
        assertEquals("https://img/delver-front.jpg", front.artUrl)
        assertEquals(0, front.faceIndex)
        assertEquals(2, front.totalSlips)

        assertEquals("Insectile Aberration", back.name)
        assertEquals("Flying", back.oracleText)
        assertEquals("3", back.power)
        assertEquals("https://img/delver-back.jpg", back.artUrl)
        assertEquals(1, back.faceIndex)
        assertEquals(2, back.totalSlips)

        assertNotEquals(front.artUrl, back.artUrl)
    }

    @Test
    fun testPlan_singleSlipUsesPrimaryFaceAndSharedArt() {
        val content = SlipPlanner.plan(splitCard).single()
        assertEquals("Fire", content.name)
        assertEquals("https://img/fire-ice.jpg", content.artUrl)
        assertEquals(0, content.faceIndex)
        assertEquals(1, content.totalSlips)
        assertEquals("{1}{R}", content.manaCost)
        assertEquals("Deal 2 damage divided as you choose.", content.oracleText)
    }

    // ------------------------------------------------------------------
    // Secondary faces - split / flip / adventure carry the OTHER face's text on the same slip,
    // never a separate ACTION_SEND. Regression coverage for the bug where Scryfall omits
    // oracle_text at the top level for these layouts, silently dropping the second half entirely.
    // ------------------------------------------------------------------

    @Test
    fun testSecondaryFaces_forSplit() {
        val content = SlipPlanner.plan(splitCard).single()
        val ice = content.secondaryFaces.single()
        assertEquals("Ice", ice.name)
        assertEquals("{1}{U}", ice.manaCost)
        assertEquals("Instant", ice.typeLine)
        assertEquals("Tap target permanent. Draw a card.", ice.oracleText)
        assertNull(ice.power)
        assertNull(ice.toughness)
    }

    @Test
    fun testSecondaryFaces_forFlip() {
        val content = SlipPlanner.plan(flipCard).single()
        val essence = content.secondaryFaces.single()
        assertEquals("Erayo's Essence", essence.name)
        assertEquals("Legendary Enchantment", essence.typeLine)
        assertNull(essence.manaCost)
    }

    @Test
    fun testSecondaryFaces_forAdventure() {
        val content = SlipPlanner.plan(adventureCard).single()
        val theft = content.secondaryFaces.single()
        assertEquals("Petty Theft", theft.name)
        assertEquals("{1}{U}", theft.manaCost)
        assertEquals("Instant — Adventure", theft.typeLine)
    }

    @Test
    fun testSecondaryFaces_emptyForTrueTwoSlipDfc() {
        // The back face is already its own SlipContent - it must never ALSO show up as a
        // secondary face of the front, or Insectile Aberration would print twice.
        val (front, back) = SlipPlanner.plan(transformCard)
        assertTrue(front.secondaryFaces.isEmpty())
        assertTrue(back.secondaryFaces.isEmpty())
    }

    @Test
    fun testSecondaryFaces_emptyForMeldAndNormalCards() {
        assertTrue(SlipPlanner.plan(meldCard).single().secondaryFaces.isEmpty())
        assertTrue(SlipPlanner.plan(normalCard).single().secondaryFaces.isEmpty())
    }

    @Test
    fun testPlan_normalCardIsUnchanged() {
        val content = SlipPlanner.plan(normalCard).single()
        assertEquals("Colossal Dreadmaw", content.name)
        assertEquals("{4}{G}{G}", content.manaCost)
        assertEquals("Creature — Dinosaur", content.typeLine)
        assertEquals("Trample", content.oracleText)
        assertEquals("6", content.power)
        assertEquals("6", content.toughness)
        assertEquals("https://img/dreadmaw.jpg", content.artUrl)
        assertNull(content.label)
    }

    @Test
    fun testArtUrls_areOnePerSlip() {
        assertEquals(
            listOf("https://img/delver-front.jpg", "https://img/delver-back.jpg"),
            SlipPlanner.artUrls(transformCard)
        )
        assertEquals(listOf("https://img/fire-ice.jpg"), SlipPlanner.artUrls(splitCard))
    }
}
