package com.swupdater.capture

import com.swupdater.capture.handler.ArtifactListHandler
import com.swupdater.capture.handler.BattleDungeonResultHandler
import com.swupdater.capture.handler.LoginHandler
import com.swupdater.capture.handler.RuneListHandler
import com.swupdater.capture.handler.SummonHandler
import com.swupdater.capture.handler.UnitListHandler

interface GameCommandHandler {
    fun handle(data: Map<String, Any?>): Map<String, Any?>
}

object GameCommandMapper {

    private val handlers = mutableMapOf<String, GameCommandHandler>()

    init {
        // 核心数据命令
        register("HubUserLogin", LoginHandler())
        register("HubUnitList", UnitListHandler())
        register("HubUserRunes", RuneListHandler())
        register("HubGetRuneList", RuneListHandler())
        register("HubGetArtifactList", ArtifactListHandler())

        // 扩展命令（参考 sw-exporter 的插件体系）
        register("BattleDungeonResult", BattleDungeonResultHandler())
        register("BattleDungeonResult_V2", BattleDungeonResultHandler())
        register("Summon", SummonHandler())
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
