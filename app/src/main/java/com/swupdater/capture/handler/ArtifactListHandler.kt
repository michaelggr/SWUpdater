package com.swupdater.capture.handler

import com.swupdater.capture.GameCommandHandler

class ArtifactListHandler : GameCommandHandler {

    override fun handle(data: Map<String, Any?>): Map<String, Any?> {
        val artifactList = (data["artifact_list"] as? List<*>) ?: emptyList<Any?>()

        val artifacts = artifactList.mapNotNull { artifact ->
            (artifact as? Map<String, Any?>)?.let { parseArtifact(it) }
        }

        return mapOf(
            "artifactList" to artifacts,
            "count" to artifacts.size
        )
    }

    private fun parseArtifact(artifact: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "id" to artifact["id"],
            "masterId" to artifact["master_id"],
            "level" to artifact["level"],
            "grade" to artifact["class"],
            "slot" to artifact["slot_no"],
            "priEff" to artifact["pri_eff"],
            "secEff" to artifact["sec_eff"]
        )
    }
}
