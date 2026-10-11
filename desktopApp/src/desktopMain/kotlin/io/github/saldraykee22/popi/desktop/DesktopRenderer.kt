package io.github.saldraykee22.popi.desktop

import io.github.saldraykee22.popi.RenderPreview
import io.github.saldraykee22.popi.RenderRequest
import io.github.saldraykee22.popi.RenderResult
import io.github.saldraykee22.popi.RendererPort
import io.github.saldraykee22.popi.domain.Project
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/** JVM renderer that shells out to the user's ffmpeg/ffprobe installation. */
class DesktopRenderer(
    private val ffmpeg: String = "ffmpeg",
    private val ffprobe: String = "ffprobe",
) : RendererPort {
    override fun preview(project: Project) = RenderPreview(project.tracks.firstOrNull { it.kind == "video" }?.clips?.sumOf { it.outMs - it.inMs } ?: 0L, project.canvas.w, project.canvas.h)

    override fun export(request: RenderRequest, onComplete: (Result<RenderResult>) -> Unit) {
        onComplete(runCatching { RenderResult(export(request.project, Paths.get(request.outputPath)).toString()) })
    }

    fun export(project: Project, output: Path): Path {
        val videoTrack = project.tracks.firstOrNull { it.kind == "video" }
            ?: error("Project has no video track")
        require(videoTrack.clips.isNotEmpty()) { "Önce video klip ekleyin" }
        require(project.canvas.w == 1080 && project.canvas.h == 1920) { "Desktop export requires 1080x1920 portrait output" }
        ensureTool(ffmpeg)
        ensureTool(ffprobe)

        val videoClips = videoTrack.clips
        val videoPaths = videoClips.map { clip ->
            val asset = project.assets.firstOrNull { it.id == clip.assetId } ?: error("Missing asset: ${clip.assetId}")
            require(asset.kind == "video") { "Video izi video asset gerektirir: ${asset.id}" }
            resolvePath(asset.uri)
        }
        val inputArgs = mutableListOf<String>()
        videoPaths.forEach { inputArgs += listOf("-i", it.toString()) }
        val music = project.tracks.firstOrNull { it.kind == "audio" }?.clips.orEmpty()
        music.forEach { clip ->
            val asset = project.assets.firstOrNull { it.id == clip.assetId } ?: error("Missing asset: ${clip.assetId}")
            require(asset.kind == "audio") { "Ses izi audio asset gerektirir: ${asset.id}" }
            val path = resolvePath(asset.uri)
            inputArgs += listOf("-stream_loop", "-1", "-i", path.toString())
        }

        val graph = mutableListOf<String>()
        val segmentSeconds = videoClips.map { (it.outMs - it.inMs).also { ms -> require(ms > 0) { "Invalid clip duration" } } }
        videoClips.forEachIndexed { index, clip ->
            val start = seconds(clip.inMs)
            val duration = seconds(segmentSeconds[index])
            graph += "[$index:v:0]trim=start=$start:duration=$duration,setpts=PTS-STARTPTS,scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1,format=yuv420p[v$index]"
        }
        val videoJoin = buildString {
            videoClips.indices.forEach { append("[v$it]") }
            append("concat=n=${videoClips.size}:v=1:a=0[vbase]")
        }
        graph += videoJoin
        var currentVideo = "vbase"
        project.tracks.firstOrNull { it.kind == "text" }?.items.orEmpty().forEachIndexed { index, item ->
            val next = if (index == project.tracks.first { it.kind == "text" }.items.lastIndex) "vout" else "vtext$index"
            val y = when (item.style.position) { "top" -> "40"; "bottom" -> "h-th-40"; else -> "(h-th)/2" }
            val color = item.style.color.removePrefix("#").let { if (it.length == 6) "0x$it" else "white" }
            val text = escapeDrawText(item.text)
            val fontFile = drawTextFontFile()?.let { "fontfile='${escapeDrawText(it.toString())}':" }.orEmpty()
            graph += "[$currentVideo]drawtext=${fontFile}text='$text':fontcolor=$color:fontsize=${item.style.sizeSp}:x=(w-text_w)/2:y=$y:enable='between(t,${seconds(item.startMs)},${seconds(item.startMs + item.durationMs)})'[$next]"
            currentVideo = next
        }
        if (currentVideo == "vbase") graph += "[vbase]null[vout]"

        val audioLabels = mutableListOf<String>()
        videoClips.forEachIndexed { index, clip ->
            val label = "clipAudio$index"
            val duration = seconds(segmentSeconds[index])
            if (hasAudio(videoPaths[index])) {
                graph += "[$index:a:0]atrim=start=${seconds(clip.inMs)}:duration=$duration,asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo[$label]"
            } else {
                graph += "anullsrc=r=48000:cl=stereo,atrim=duration=$duration[$label]"
            }
            audioLabels += label
        }
        graph += buildString {
            audioLabels.forEach { append("[$it]") }
            append("concat=n=${audioLabels.size}:v=0:a=1[abase]")
        }
        val mixLabels = mutableListOf("abase")
        music.forEachIndexed { index, clip ->
            val inputIndex = videoClips.size + index
            val label = "music$index"
            val duration = seconds(videoClips.sumOf { it.outMs - it.inMs })
            graph += "[$inputIndex:a:0]atrim=start=${seconds(clip.inMs)}:duration=$duration,asetpts=PTS-STARTPTS,volume=${clip.volume ?: 1f},aformat=sample_rates=48000:channel_layouts=stereo[$label]"
            mixLabels += label
        }
        graph += buildString {
            mixLabels.forEach { append("[$it]") }
            append("amix=inputs=${mixLabels.size}:duration=longest:normalize=0[aout]")
        }

        output.toAbsolutePath().parent?.toFile()?.mkdirs()
        val command = mutableListOf(ffmpeg, "-y", "-hide_banner", "-loglevel", "error") + inputArgs +
            listOf("-filter_complex", graph.joinToString(";"), "-map", "[vout]", "-map", "[aout]", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", output.toAbsolutePath().toString())
        runChecked(command, "ffmpeg export")
        return output.toAbsolutePath()
    }

    private fun hasAudio(path: Path): Boolean {
        val result = runChecked(listOf(ffprobe, "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", path.toString()), "ffprobe")
        return result.isNotBlank()
    }

    private fun ensureTool(tool: String) {
        try {
            val p = ProcessBuilder(tool, "-version").redirectErrorStream(true).start()
            if (!p.waitFor().let { it == 0 }) throw IllegalStateException("Could not run $tool from PATH")
        } catch (e: IOException) {
            throw IllegalStateException("$tool was not found. Install ffmpeg and ffprobe and add them to PATH.", e)
        }
    }

    private fun runChecked(args: List<String>, action: String): String {
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            check(exit == 0) { "$action başarısız (exit=$exit): $output\nKomut: ${args.joinToString(" ")}" }
            return output.trim()
        } catch (e: IOException) {
            throw IllegalStateException("Could not start $action. ffmpeg and ffprobe must be on PATH.", e)
        }
    }

    private fun drawTextFontFile(): Path? {
        val windows = System.getenv("WINDIR")?.let { Paths.get(it, "Fonts", "arial.ttf") }
        val candidates = listOfNotNull(
            windows,
            Paths.get("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            Paths.get("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
            Paths.get("/System/Library/Fonts/Supplemental/Arial.ttf"),
            Paths.get("/System/Library/Fonts/Helvetica.ttc"),
        )
        return candidates.firstOrNull(Files::isRegularFile)
    }
    private fun resolvePath(value: String): Path = if (value.startsWith("file:")) Paths.get(URI(value)) else Paths.get(value)
    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
    private fun escapeDrawText(value: String): String = value.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'").replace("%", "\\%").replace(",", "\\,").replace("[", "\\[").replace("]", "\\]")
}
