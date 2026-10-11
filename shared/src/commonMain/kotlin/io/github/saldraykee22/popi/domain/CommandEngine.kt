package io.github.saldraykee22.popi.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class CommandEngine(initial: Project = Project()) {
    var project: Project = initial
        private set
    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private var nextId = 1

    fun registerAssets(assets: List<Asset>) {
        val known = project.assets.map { it.id }.toSet()
        project = project.copy(assets = project.assets + assets.filterNot { it.id in known })
    }

    fun executeJson(json: String): Project {
        val parsed = try { Json.parseToJsonElement(json) } catch (e: Exception) { throw DomainError.InvalidCommand("JSON: ${e.message}") }
        val commands = if (parsed is JsonArray) parsed else JsonArray(listOf(parsed))
        val historyOp = (commands.singleOrNull() as? JsonObject)?.get("op")?.jsonPrimitive?.content
        if (historyOp == "undo") return undo()
        if (historyOp == "redo") return redo()
        if (commands.any { it is JsonObject && it["op"]?.jsonPrimitive?.content in setOf("undo", "redo") }) {
            throw DomainError.InvalidCommand("undo/redo her çağrıda tek komut olmalı")
        }
        // Transactional: all commands validate and reduce against a temporary snapshot first.
        var candidate = project
        var generated = nextId
        val results = mutableListOf<Project>()
        for (element in commands) {
            val obj = element as? JsonObject ?: throw DomainError.InvalidCommand("Komut nesne olmalı")
            val op = obj.string("op")
            when (op) {
                "clarify" -> throw DomainError.ClarificationRequired(obj.string("question"))
                "unsupported" -> throw DomainError.Unsupported(obj.string("reason"))
                "import_clip" -> { candidate = Reducer.importClip(candidate, obj, "c$generated"); generated++ }
                else -> candidate = Reducer.apply(candidate, obj)
            }
            results += candidate
        }
        if (results.isNotEmpty()) {
            undo.addLast(project); project = candidate; redo.clear(); nextId = generated
        }
        return project
    }

    fun undo(): Project {
        if (undo.isEmpty()) throw DomainError.EmptyHistory
        redo.addLast(project); project = undo.removeLast(); return project
    }
    fun redo(): Project {
        if (redo.isEmpty()) throw DomainError.EmptyHistory
        undo.addLast(project); project = redo.removeLast(); return project
    }
    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()
}

object ProjectJson {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    fun encode(project: Project): String = json.encodeToString(project)
    fun decode(value: String): Project = json.decodeFromString(value)
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?: throw DomainError.InvalidCommand("'$name' alanı gerekli")
private fun JsonObject.long(name: String, default: Long? = null): Long = this[name]?.jsonPrimitive?.longOrNull
    ?: default ?: throw DomainError.InvalidCommand("'$name' tam sayı olmalı")
private fun JsonObject.float(name: String, default: Float): Float = this[name]?.jsonPrimitive?.floatOrNull ?: default

private object Reducer {
    fun importClip(project: Project, c: JsonObject, id: String): Project {
        val asset = project.assets.firstOrNull { it.id == c.string("assetId") } ?: throw DomainError.NotFound("asset", c.string("assetId"))
        if (asset.kind != "video") throw DomainError.InvalidRange("import_clip yalnız video asset alır")
        val trackId = c.string("trackId")
        val track = project.tracks.find { it.id == trackId && it.kind == "video" } ?: throw DomainError.NotFound("video track", trackId)
        val index = c.long("index", track.clips.size.toLong()).toInt()
        if (index !in 0..track.clips.size) throw DomainError.InvalidRange("index aralık dışında")
        val clip = Clip(id, asset.id, 0, asset.durationMs)
        return project.replaceTrack(track.copy(clips = track.clips.toMutableList().apply { add(index, clip) }))
    }

    fun apply(p: Project, c: JsonObject): Project = when (c.string("op")) {
        "trim_clip" -> {
            val (track, clip) = p.clip(c.string("clipId")); val asset = p.assets.firstOrNull { it.id == clip.assetId } ?: throw DomainError.NotFound("asset", clip.assetId)
            val start = c.long("inMs", clip.inMs); val end = c.long("outMs", clip.outMs)
            if (start < 0 || start >= end || end > asset.durationMs) throw DomainError.InvalidRange("Trim 0 <= inMs < outMs <= asset.durationMs olmalı")
            p.replaceTrack(track.copy(clips = track.clips.map { if (it.id == clip.id) clip.copy(inMs = start, outMs = end) else it }))
        }
        "move_clip" -> {
            val (track, clip) = p.clip(c.string("clipId")); val to = c.long("toIndex").toInt()
            if (to !in track.clips.indices) throw DomainError.InvalidRange("toIndex aralık dışında")
            val list = track.clips.toMutableList(); list.remove(clip); list.add(to, clip); p.replaceTrack(track.copy(clips = list))
        }
        "delete_clip" -> { val (track, clip) = p.clip(c.string("clipId")); p.replaceTrack(track.copy(clips = track.clips.filterNot { it.id == clip.id })) }
        "add_text" -> {
            val id = c.string("trackId"); val track = p.tracks.firstOrNull { it.id == id && it.kind == "text" } ?: throw DomainError.NotFound("text track", id)
            val start = c.long("startMs"); val duration = c.long("durationMs"); if (start < 0 || duration <= 0) throw DomainError.InvalidRange("Metin aralığı geçersiz")
            val style = c["style"]?.let { Json.decodeFromJsonElement<TextStyle>(it) } ?: TextStyle()
            p.replaceTrack(track.copy(items = track.items + TextItem(c["id"]?.jsonPrimitive?.content ?: "x${track.items.size + 1}", c.string("text"), start, duration, style)))
        }
        "edit_text" -> {
            val id = c.string("itemId"); val track = p.tracks.firstOrNull { it.kind == "text" && it.items.any { item -> item.id == id } } ?: throw DomainError.NotFound("text item", id)
            p.replaceTrack(track.copy(items = track.items.map { item -> if (item.id != id) item else {
                val start = c.long("startMs", item.startMs); val duration = c.long("durationMs", item.durationMs)
                if (start < 0 || duration <= 0) throw DomainError.InvalidRange("Metin aralığı geçersiz")
                item.copy(text = c["text"]?.jsonPrimitive?.content ?: item.text, startMs = start, durationMs = duration, style = c["style"]?.let { Json.decodeFromJsonElement(it) } ?: item.style)
            } }))
        }
        "add_audio" -> {
            val assetId = c.string("assetId"); val asset = p.assets.firstOrNull { it.id == assetId } ?: throw DomainError.NotFound("asset", assetId)
            if (asset.kind != "audio") throw DomainError.InvalidRange("add_audio audio asset ister")
            val id = c.string("trackId"); val track = p.tracks.firstOrNull { it.id == id && it.kind == "audio" } ?: throw DomainError.NotFound("audio track", id)
            val start = c.long("inMs", 0); val end = c.long("outMs", asset.durationMs); val volume = c.float("volume", 1f)
            if (start < 0 || start >= end || end > asset.durationMs || volume !in 0f..2f) throw DomainError.InvalidRange("Ses aralığı/hacmi geçersiz")
            p.replaceTrack(track.copy(clips = track.clips + Clip(c["id"]?.jsonPrimitive?.content ?: "a${track.clips.size + 1}", assetId, start, end, volume)))
        }
        "set_volume" -> {
            val (track, clip) = p.clip(c.string("clipId")); val volume = c.float("volume", Float.NaN)
            if (track.kind != "audio" || volume !in 0f..2f) throw DomainError.InvalidRange("Ses seviyesi 0.0–2.0 olmalı")
            p.replaceTrack(track.copy(clips = track.clips.map { if (it.id == clip.id) it.copy(volume = volume) else it }))
        }
        "export_project" -> throw DomainError.Unsupported("export_project uygulama adaptöründe çalıştırılır")
        "clarify" -> throw DomainError.ClarificationRequired(c.string("question"))
        "unsupported" -> throw DomainError.Unsupported(c.string("reason"))
        else -> throw DomainError.InvalidCommand("Bilinmeyen op: ${c.string("op")}")
    }
    private fun Project.clip(id: String): Pair<Track, Clip> {
        val track = tracks.firstOrNull { it.clips.any { clip -> clip.id == id } }
            ?: throw DomainError.NotFound("clip", id)
        val clip = track.clips.firstOrNull { it.id == id } ?: throw DomainError.NotFound("clip", id)
        return track to clip
    }
    private fun Project.replaceTrack(track: Track) = copy(tracks = tracks.map { if (it.id == track.id) track else it })
}

