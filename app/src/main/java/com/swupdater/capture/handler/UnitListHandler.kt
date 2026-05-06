package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

class UnitListHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val unitList = (data["unit_list"] as? List<*>) ?: emptyList<Any?>()

        val units = unitList.mapNotNull { unit ->
            (unit as? Map<String, Any?>)?.let { parseUnit(it) }
        }

        return mapOf(
            "unitList" to units,
            "count" to units.size
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseUnit(unit: Map<String, Any?>): Map<String, Any?> {
        val runes = (unit["runes"] as? List<*>)?.mapNotNull { rune ->
            (rune as? Map<String, Any?>)?.let { RuneParser.parseRune(it) }
        } ?: emptyList()

        return mapOf(
            "unitId" to unit["unit_id"],
            "masterId" to unit["unit_master_id"],
            "grade" to unit["class"],
            "level" to unit["unit_level"],
            "con" to unit["con"],
            "atk" to unit["atk"],
            "def" to unit["def"],
            "spd" to unit["spd"],
            "critRate" to unit["cri_rate"],
            "critDmg" to unit["cri_dmg"],
            "resist" to unit["resist"],
            "accuracy" to unit["accuracy"],
            "skills" to unit["skills"],
            "runes" to runes
        )
    }
}
