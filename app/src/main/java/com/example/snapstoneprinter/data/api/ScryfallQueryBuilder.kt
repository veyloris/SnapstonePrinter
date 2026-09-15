package com.example.snapstoneprinter.data.api

/**
 * Builds the `q` parameter for `cards/random`.
 *
 * Junk-pull filtering is done here first (server side) so that most requests never even return a
 * token/emblem/art-series object; [com.example.snapstoneprinter.data.model.CardLayouts] then acts
 * as a second, authoritative client-side guard.
 */
object ScryfallQueryBuilder {

    /** Scryfall's catch-all negation for tokens, emblems, art series, memorabilia, planes, ... */
    const val EXCLUDE_EXTRAS = "-is:extra"

    const val NON_LAND = "-t:land"

    const val CREATURE_ONLY = "t:creature"

    const val FUNNY = "is:funny"

    /** Inclusive bounds on the CMC Momir Vig can be activated for. */
    val MOMIR_VIG_CMC_RANGE = 0..16

    /**
     * Lands are never a valid pull: the app exists to proxy a card for Snapstone Wielder, which
     * only ever targets a nonland card, so every random query excludes them unconditionally.
     *
     * @param isFunny include silver-bordered / acorn cards.
     */
    fun build(isFunny: Boolean = false): String {
        val terms = mutableListOf(NON_LAND)
        if (!isFunny) terms += "-$FUNNY"
        terms += EXCLUDE_EXTRAS
        return terms.joinToString(" ")
    }

    /**
     * Momir Vig's ability conjures a token copy of a random CREATURE card of the chosen converted
     * mana cost - never a land, instant, or anything else.
     *
     * @param cmc converted mana cost to activate Momir Vig for; must be in [MOMIR_VIG_CMC_RANGE].
     * @param isFunny include silver-bordered / acorn cards.
     */
    fun buildMomirVig(cmc: Int, isFunny: Boolean = false): String {
        require(cmc in MOMIR_VIG_CMC_RANGE) {
            "cmc must be in $MOMIR_VIG_CMC_RANGE, got $cmc"
        }
        val terms = mutableListOf("cmc=$cmc", CREATURE_ONLY)
        if (!isFunny) terms += "-$FUNNY"
        terms += EXCLUDE_EXTRAS
        return terms.joinToString(" ")
    }
}
