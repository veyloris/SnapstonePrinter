package com.example.snapstoneprinter.image

import com.example.snapstoneprinter.data.model.CardFace
import com.example.snapstoneprinter.data.model.ScryfallCard

/**
 * The resolved, renderer-agnostic content of a single slip.
 *
 * Deliberately free of any `android.*` type so the whole slip-planning decision (how many slips,
 * which art, which label) is covered by plain JVM unit tests.
 */
data class SlipContent(
    val faceIndex: Int,
    val totalSlips: Int,
    val name: String,
    val manaCost: String?,
    val typeLine: String?,
    val oracleText: String?,
    val power: String?,
    val toughness: String?,
    /** Art URL for THIS face - never the top-level one when the card is multi-slip. */
    val artUrl: String?,
    /** Layout-aware header line, or null for single-slip cards. */
    val label: String?,
    /**
     * Full-resolution card image for THIS face (`normal`, then `large`). Never dithered - this is
     * only what the "full card" preview toggle shows next to the thermal composite.
     */
    val fullImageUrl: String? = null,
    /**
     * The OTHER face(s) of a one-slip multi-face card (split / flip / adventure), printed
     * underneath this face's own block on the SAME physical slip - these layouts share one piece
     * of art and are never split into separate `ACTION_SEND` dispatches like a true DFC.
     *
     * Empty for every other card, including true two-slip DFCs (their back face is its own
     * [SlipContent], not a [SecondaryFace] of the front).
     */
    val secondaryFaces: List<SecondaryFace> = emptyList(),
    val loyalty: String? = null,
    val defense: String? = null
)

/**
 * The rules text of a face that shares a slip with another face (split / flip / adventure), e.g.
 * "Ice" on a Fire // Ice slip, or "Stomp" on a Bonecrusher Giant slip.
 *
 * Deliberately NOT run through [ScryfallCard]'s `effective*` fallbacks - those exist to patch a
 * missing top-level field from `card_faces[0]`, which is meaningless here: this face's own raw
 * value is exactly what should print, blank or not.
 */
data class SecondaryFace(
    val name: String,
    val manaCost: String?,
    val typeLine: String?,
    val oracleText: String?,
    val power: String?,
    val toughness: String?,
    val loyalty: String? = null,
    val defense: String? = null
)

/**
 * Decides whether a fetched card prints as one slip or several, and what each slip contains.
 *
 * ## The critical distinction
 *
 * `card_faces[]` alone does NOT mean "double-faced". Both of these groups populate `card_faces[]`:
 *
 * - **Two slips** - `transform`, `modal_dfc`, `reversible_card`: physically two printed sides, so
 *   every face carries its **own** `image_uris`.
 * - **One slip** - `split`, `flip`, `adventure`: physically one card with one piece of art, so
 *   there is a single shared **top-level** `image_uris` and the faces have none.
 *
 * The trigger is therefore the *structural* test in [hasPerFaceArt]: `card_faces` is non-empty AND
 * every face has its own non-null, usable `image_uris`. Keying off `card_faces` presence alone
 * would make Fire // Ice print twice with duplicate art.
 *
 * Layout strings are only consulted for the *wording* of the slip label, never to decide the slip
 * count. `meld` cards arrive from `/cards/random` as individual cards and so naturally fall into
 * the single-slip path.
 */
object SlipPlanner {

    const val LAYOUT_TRANSFORM = "transform"
    const val LAYOUT_MODAL_DFC = "modal_dfc"
    const val LAYOUT_REVERSIBLE_CARD = "reversible_card"

    /**
     * The structural two-slip test: at least two faces, and every one of them owns a usable
     * `image_uris`. This - not the layout string - is what decides the slip count.
     */
    fun hasPerFaceArt(card: ScryfallCard): Boolean {
        val faces = card.card_faces
        if (faces == null || faces.size < 2) return false
        return faces.all { !it.bestArtUrl().isNullOrBlank() }
    }

    /** Number of slips [card] will print as. 1 for everything that is not truly double-faced. */
    fun slipCount(card: ScryfallCard): Int =
        if (hasPerFaceArt(card)) card.card_faces!!.size else 1

    /**
     * The small header line for the slip at [faceIndex].
     *
     * - `transform` front: "Transforms into: {back face name}"
     * - `transform` back:  "Transforms from: {front face name}"
     * - `modal_dfc` / `reversible_card`: "Other side: {other face name}"
     * - anything else that still passes the structural test: "Other side: {other face name}"
     * - single-slip cards: null
     */
    fun labelFor(card: ScryfallCard, faceIndex: Int): String? {
        if (!hasPerFaceArt(card)) return null
        val faces = card.card_faces ?: return null
        if (faceIndex !in faces.indices) return null

        val otherName = faces[(faceIndex + 1) % faces.size].name.nullIfBlank() ?: return null

        return when (card.layout.normalizedLayout()) {
            LAYOUT_TRANSFORM ->
                if (faceIndex == 0) "Transforms into: $otherName" else "Transforms from: $otherName"

            LAYOUT_MODAL_DFC, LAYOUT_REVERSIBLE_CARD -> "Other side: $otherName"

            // Unknown / unexpected multi-face layout that still has per-face art.
            else -> "Other side: $otherName"
        }
    }

    /**
     * Resolves [card] into the ordered list of slips to compose. Size is always [slipCount].
     */
    fun plan(card: ScryfallCard): List<SlipContent> {
        val faces = card.card_faces
        if (faces == null || !hasPerFaceArt(card)) return listOf(singleSlipContent(card))

        val total = faces.size
        return faces.mapIndexed { index, face ->
            SlipContent(
                faceIndex = index,
                totalSlips = total,
                name = face.name.nullIfBlank() ?: card.name,
                manaCost = face.mana_cost.nullIfBlank(),
                typeLine = face.type_line.nullIfBlank(),
                oracleText = face.oracle_text.nullIfBlank(),
                power = face.power.nullIfBlank(),
                toughness = face.toughness.nullIfBlank(),
                artUrl = face.bestArtUrl(),
                label = labelFor(card, index),
                fullImageUrl = face.bestFullUrl(),
                loyalty = face.loyalty.nullIfBlank(),
                defense = face.defense.nullIfBlank()
            )
        }
    }

    /** Keep printed face fields local; CardFixturePlanningTest covers aggregate-field rejection. */
    fun singleSlipContent(card: ScryfallCard): SlipContent {
        val face = card.card_faces?.takeIf { it.size >= 2 }?.first()
        return SlipContent(
            faceIndex = 0,
            totalSlips = 1,
            name = if (face != null) face.name.nullIfBlank() ?: card.name else card.effectiveName,
            manaCost = if (face != null) face.mana_cost.nullIfBlank() else card.effectiveManaCost,
            typeLine = if (face != null) face.type_line.nullIfBlank() else card.effectiveTypeLine,
            oracleText = if (face != null) face.oracle_text.nullIfBlank() else card.effectiveOracleText,
            power = if (face != null) face.power.nullIfBlank() else card.effectivePower,
            toughness = if (face != null) face.toughness.nullIfBlank() else card.effectiveToughness,
            artUrl = card.effectiveImageUrl,
            label = null,
            fullImageUrl = card.effectiveNormalUrl,
            secondaryFaces = secondaryFacesFor(card),
            loyalty = if (face != null) face.loyalty.nullIfBlank() else card.effectiveLoyalty,
            defense = if (face != null) face.defense.nullIfBlank() else card.effectiveDefense
        )
    }

    /**
     * The card_faces beyond the first, for a card that stays on ONE slip (split / flip /
     * adventure). Empty for a true two-slip DFC - [hasPerFaceArt] is the same structural test
     * used everywhere else in this file, so a transform/modal_dfc/reversible_card never gets its
     * back face duplicated here as well as dispatched as its own slip.
     *
     * Scryfall omits `oracle_text` from the top level for ALL of split / flip / adventure (only
     * `card_faces[0].oracle_text` is reachable via the `effective*` fallback), so without this the
     * second half of the card - Ice's ability, Stomp's spell text, Kenzo's abilities - never
     * printed anywhere.
     */
    private fun secondaryFacesFor(card: ScryfallCard): List<SecondaryFace> {
        val faces = card.card_faces ?: return emptyList()
        if (faces.size < 2 || hasPerFaceArt(card)) return emptyList()
        return faces.drop(1).mapNotNull { face ->
            val name = face.name.nullIfBlank() ?: return@mapNotNull null
            SecondaryFace(
                name = name,
                manaCost = face.mana_cost.nullIfBlank(),
                typeLine = face.type_line.nullIfBlank(),
                oracleText = face.oracle_text.nullIfBlank(),
                power = face.power.nullIfBlank(),
                toughness = face.toughness.nullIfBlank(),
                loyalty = face.loyalty.nullIfBlank(),
                defense = face.defense.nullIfBlank()
            )
        }
    }

    /** Ordered art URLs, one per slip. Convenience for the download step in the ViewModel. */
    fun artUrls(card: ScryfallCard): List<String?> = plan(card).map { it.artUrl }

    /** art_crop first, then the full card image, exactly like the top-level resolvers. */
    private fun CardFace.bestArtUrl(): String? {
        val uris = image_uris ?: return null
        return uris.artCrop.nullIfBlank() ?: uris.normal.nullIfBlank() ?: uris.large.nullIfBlank()
    }

    /** Full-card image for this face, mirroring [ScryfallCard.effectiveNormalUrl]. */
    private fun CardFace.bestFullUrl(): String? {
        val uris = image_uris ?: return null
        return uris.normal.nullIfBlank() ?: uris.large.nullIfBlank()
    }

    private fun String?.normalizedLayout(): String? = this?.trim()?.lowercase().nullIfBlank()

    private fun String?.nullIfBlank(): String? = if (this.isNullOrBlank()) null else this
}
