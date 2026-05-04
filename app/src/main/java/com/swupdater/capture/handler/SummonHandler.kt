package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

/**
 * 召唤结果处理器
 * 记录召唤类型和获得的魔灵
 */
class SummonHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        result["summonType"] = data["summon_type"]
        result["cost"] = data["cost"]
        result["costType"] = data["cost_type"]

        // 召唤获得的魔灵列表
        val unitList = data["unit_list"] as? List<*>
        if (unitList != null) {
            val units = unitList.mapNotNull { unit ->
                (unit as? Map<String, Any?>)?.let {
                    mapOf(
                        "masterId" to it["unit_master_id"],
                        "grade" to it["class"],
                        "level" to it["unit_level"]
                    )
                }
            }
            result["units"] = units
            result["count"] = units.size
        }

        return result
    }
}
