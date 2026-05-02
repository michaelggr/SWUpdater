package com.swupdater.capture

import com.swupdater.capture.handler.ArtifactListHandler
import com.swupdater.capture.handler.LoginHandler
import com.swupdater.capture.handler.RuneListHandler
import com.swupdater.capture.handler.UnitListHandler

interface GameCommandHandler {
    fun handle(data: Map<String, Any?>): Map<String, Any?>
}

object GameCommandMapper {

    private val handlers = mutableMapOf<String, GameCommandHandler>()

    init {
        register("HubUserLogin", LoginHandler())
        register("HubUnitList", UnitListHandler())
        register("HubUserRunes", RuneListHandler())
        register("HubGetRuneList", RuneListHandler())
        register("HubGetArtifactList", ArtifactListHandler())
    }

    fun register(command: String, handler: GameCommandHandler) {
        handlers[command] = handler
    }

    fun getHandler(command: String): GameCommandHandler? {
        return handlers[command]
    }

    fun getRegisteredCommands(): Set<String> {
        return handlers.keys.toSet()
    }
}
