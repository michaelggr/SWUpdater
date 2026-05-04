package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

class LoginHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        result["wizardId"] = data["wizard_id"]
        result["wizardName"] = data["wizard_name"]
        result["wizardLevel"] = data["wizard_level"]
        result["wizardMug"] = data["wizard_mug"]
        result["mana"] = data["mana"]
        result["crystal"] = data["crystal"]
        result["socialPoint"] = data["social_point"]
        result["guildId"] = data["guild_id"]
        result["vipLevel"] = data["vip_level"]
        result["energy"] = data["energy"]
        result["arenaScore"] = data["arena_score"]

        return result
    }
}
