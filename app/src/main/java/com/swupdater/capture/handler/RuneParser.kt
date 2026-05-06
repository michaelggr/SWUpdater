package com.swupdater.capture.handler

object RuneParser {

    fun parseRune(rune: Map<String, Any?>): Map<String, Any?> = mapOf(
        "runeId" to rune["rune_id"],
        "masterId" to rune["rune_master_id"],
        "slot" to rune["slot_no"],
        "grade" to rune["class"],
        "level" to rune["upgrade"],
        "set" to rune["set_id"],
        "priEff" to rune["pri_eff"],
        "prefixEff" to rune["prefix_eff"],
        "secEff" to rune["sec_eff"]
    )

    fun parseRuneWithOwner(rune: Map<String, Any?>): Map<String, Any?> =
        parseRune(rune) + mapOf(
            "occupiedType" to rune["occupied_type"],
            "unitId" to rune["unit_id"]
        )
}
