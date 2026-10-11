package io.github.saldraykee22.popi.ai

import io.github.saldraykee22.popi.domain.Project

/**
 * Modele gidecek kısa, okunabilir proje özetini üretir.
 *
 * Gizlilik: [io.github.saldraykee22.popi.domain.Asset.uri] (dosya yolu) ve dosya adı
 * özete hiçbir zaman girmez; yalnız kimlikler, tür, süre ve zaman aralıkları gider.
 */
object ProjectSummary {
    fun summarize(project: Project): String {
        val assetsById = project.assets.associateBy { it.id }
        return buildString {
            appendLine("Proje: ${project.id}, tuval ${project.canvas.w}x${project.canvas.h} @${project.canvas.fps}fps")
            appendLine("Varlıklar:")
            project.assets.forEach { asset ->
                appendLine("- ${asset.id}: ${asset.kind}, ${asset.durationMs} ms")
            }
            project.tracks.forEach { track ->
                when (track.kind) {
                    "video", "audio" -> {
                        appendLine("İz ${track.id} (${track.kind}), sıra:")
                        track.clips.forEach { clip ->
                            val asset = assetsById[clip.assetId]
                            val kind = asset?.kind ?: "bilinmiyor"
                            val duration = asset?.durationMs?.let { "$it ms" } ?: "bilinmiyor"
                            val volume = if (track.kind == "audio") ", seviye ${clip.volume ?: 1f}" else ""
                            appendLine("- ${clip.id}: varlık ${clip.assetId} ($kind, $duration), ${clip.inMs}-${clip.outMs} ms$volume")
                        }
                    }
                    "text" -> {
                        appendLine("Metin izi ${track.id}:")
                        track.items.forEach { item ->
                            appendLine("- ${item.id}: \"${item.text}\", ${item.startMs} ms, ${item.durationMs} ms, ${item.style.position}")
                        }
                    }
                    else -> appendLine("İz ${track.id} (${track.kind})")
                }
            }
        }.trimEnd()
    }
}
