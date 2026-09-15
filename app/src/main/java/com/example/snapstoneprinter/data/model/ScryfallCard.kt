package com.example.snapstoneprinter.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ImageUris(
    @Json(name = "art_crop") val artCrop: String? = null,
    @Json(name = "normal") val normal: String? = null,
    @Json(name = "png") val png: String? = null,
    @Json(name = "large") val large: String? = null
)

/**
 * A single face of a multi-faced Scryfall card.
 *
 * Keep each face's printed fields separate from aggregate card fields; see
 * CardFixturePlanningTest.splitPrimaryDoesNotUseCombinedCostOrType.
 */
@JsonClass(generateAdapter = true)
data class CardFace(
    val name: String? = null,
    @Json(name = "mana_cost") val mana_cost: String? = null,
    @Json(name = "type_line") val type_line: String? = null,
    @Json(name = "oracle_text") val oracle_text: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    @Json(name = "image_uris") val image_uris: ImageUris? = null,
    val loyalty: String? = null,
    val defense: String? = null
)

@JsonClass(generateAdapter = true)
data class ScryfallCard(
    val name: String,
    @Json(name = "mana_cost") val mana_cost: String? = null,
    @Json(name = "type_line") val type_line: String? = null,
    @Json(name = "oracle_text") val oracle_text: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    @Json(name = "image_uris") val image_uris: ImageUris? = null,
    @Json(name = "layout") val layout: String? = null,
    @Json(name = "card_faces") val card_faces: List<CardFace>? = null,
    val loyalty: String? = null,
    val defense: String? = null
) {

    /** The face we fall back to when a top-level field is absent. Scryfall always orders front-first. */
    val primaryFace: CardFace?
        get() = card_faces?.firstOrNull()

    /** True when this card carries a `card_faces[]` array (transform, modal_dfc, split, flip, ...). */
    val isMultiFaced: Boolean
        get() = !card_faces.isNullOrEmpty()

    // ---------------------------------------------------------------------
    // Use these for legacy card-level access; use raw face fields when printing
    // a specific face, as covered by CardFixturePlanningTest.
    // ---------------------------------------------------------------------

    val effectiveName: String
        get() = name.nullIfBlank() ?: primaryFace?.name.nullIfBlank() ?: ""

    val effectiveManaCost: String?
        get() = mana_cost.nullIfBlank() ?: primaryFace?.mana_cost.nullIfBlank()

    val effectiveTypeLine: String?
        get() = type_line.nullIfBlank() ?: primaryFace?.type_line.nullIfBlank()

    val effectiveOracleText: String?
        get() = oracle_text.nullIfBlank() ?: primaryFace?.oracle_text.nullIfBlank()

    val effectivePower: String?
        get() = power.nullIfBlank() ?: primaryFace?.power.nullIfBlank()

    val effectiveToughness: String?
        get() = toughness.nullIfBlank() ?: primaryFace?.toughness.nullIfBlank()

    val effectiveLoyalty: String?
        get() = loyalty.nullIfBlank() ?: primaryFace?.loyalty.nullIfBlank()

    val effectiveDefense: String?
        get() = defense.nullIfBlank() ?: primaryFace?.defense.nullIfBlank()

    /** The art_crop URL used as the source for the dithering pipeline. */
    val effectiveArtCropUrl: String?
        get() = image_uris?.artCrop.nullIfBlank() ?: primaryFace?.image_uris?.artCrop.nullIfBlank()

    /** The full-card URL (normal, then large) used as a fallback when no art_crop exists. */
    val effectiveNormalUrl: String?
        get() = image_uris?.normal.nullIfBlank()
            ?: image_uris?.large.nullIfBlank()
            ?: primaryFace?.image_uris?.normal.nullIfBlank()
            ?: primaryFace?.image_uris?.large.nullIfBlank()

    /** Best available image for rendering: art_crop first, then the full card image. */
    val effectiveImageUrl: String?
        get() = effectiveArtCropUrl ?: effectiveNormalUrl
}

private fun String?.nullIfBlank(): String? = if (this.isNullOrBlank()) null else this
