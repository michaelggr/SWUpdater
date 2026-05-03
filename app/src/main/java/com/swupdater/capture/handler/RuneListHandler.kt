package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

class RuneListHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val runeList = (data["runes"] as? List<*>) ?: emptyList<Any?>()

        val runes = runeList.mapNotNull { rune ->
            (rune as? Map<String, Any?>)?.let { parseRune(it) }
        }

        return mapOf(
            "runeList" to runes,
            "count" to runes.size
        )
    }

    private fun parseRune(rune: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "runeId" to rune["rune_id"],
            "masterId" to rune["rune_master_id"],
            "slot" to rune["slot_no"],
            "grade" to rune["class"],
            "level" to rune["upgrade"],
            "set" to rune["set_id"],
            "priEff" to rune["pri_eff"],
            "prefixEff" to rune["prefix_eff"],
            "secEff" to rune["sec_eff"],
            "occupiedType" to rune["occupied_type"],
            "unitId" to rune["unit_id"]
        )
    }
}
