package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

class RuneListHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val runeList = (data["runes"] as? List<*>) ?: emptyList<Any?>()

        val runes = runeList.mapNotNull { rune ->
            (rune as? Map<String, Any?>)?.let { RuneParser.parseRuneWithOwner(it) }
        }

        return mapOf(
            "runeList" to runes,
            "count" to runes.size
        )
    }
}
