package io.github.saldraykee22.popi.desktop

import io.github.saldraykee22.popi.domain.Asset
import io.github.saldraykee22.popi.domain.Clip
import io.github.saldraykee22.popi.domain.Project
import io.github.saldraykee22.popi.domain.TextItem
import io.github.saldraykee22.popi.domain.Track
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRendererTest {
    @Test fun ffmpegExportHasPortraitResolutionAndExpectedDuration() {
        val dir = Files.createTempDirectory("popi-ffmpeg-test")
        val first = dir.resolve("first.mp4")
        val second = dir.resolve("second.mp4")
        val music = dir.resolve("music.m4a")
        makeVideo(first, "440")
        makeVideo(second, "660")
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=220:duration=2", "-c:a", "aac", music.toString())
        val project = Project(
            assets = listOf(Asset("v1", "video", first.toUri().toString(), 2000), Asset("v2", "video", second.toUri().toString(), 2000), Asset("music", "audio", music.toUri().toString(), 2000)),
            tracks = listOf(
                Track("v1", "video", listOf(Clip("c1", "v1", 0, 2000), Clip("c2", "v2", 0, 2000))),
                Track("t1", "text", items = listOf(TextItem("text1", "Merhaba Dünya", 0, 1500))),
                Track("m1", "audio", clips = listOf(Clip("a1", "music", 0, 2000, 0.3f))),
            ),
        )
        val output = dir.resolve("export.mp4")
        DesktopRenderer().export(project, output)
        val probe = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "default=noprint_wrappers=1:nokey=1", output.toString()).lines()
        assertEquals("1080", probe[0])
        assertEquals("1920", probe[1])
        val duration = probe[2].toDouble()
        assertTrue(kotlin.math.abs(duration - 4.0) <= 0.15, "expected 4 seconds, got $duration")
    }

    private fun makeVideo(path: Path, frequency: String) {
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=2", "-f", "lavfi", "-i", "sine=frequency=$frequency:duration=2", "-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", path.toString())
    }

    private fun run(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        check(code == 0) { "${args.first()} failed ($code): $text" }
        return text.trim()
    }
}
