package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

/**
 * 副本战斗结果处理器
 * 参考 sw-exporter 的 run-logger 插件
 */
class BattleDungeonResultHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        result["dungeonId"] = data["dungeon_id"]
        result["stageId"] = data["stage_id"]
        result["win"] = data["win"]
        result["clearTime"] = data["clear_time"]

        // 掉落奖励
        val reward = data["reward"] as? Map<String, Any?>
        if (reward != null) {
            result["reward"] = mapOf(
                "mana" to reward["mana"],
                "crystal" to reward["crystal"],
                "energy" to reward["energy"],
                "randomScroll" to reward["random_scroll"],
                "rune" to reward["rune"]
            )
        }

        // 掉落符文详情
        val droppedRune = data["rune"] as? Map<String, Any?>
        if (droppedRune != null) {
            result["droppedRune"] = RuneParser.parseRune(droppedRune)
        }

        return result
    }
}
