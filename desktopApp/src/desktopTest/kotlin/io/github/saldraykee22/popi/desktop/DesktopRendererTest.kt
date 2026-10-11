package io.github.saldraykee22.popi.desktop

import io.github.saldraykee22.popi.domain.Asset
import io.github.saldraykee22.popi.domain.Clip
import io.github.saldraykee22.popi.domain.Project
import io.github.saldraykee22.popi.domain.TextItem
import io.github.saldraykee22.popi.domain.Track
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRendererTest {
    @Test fun ffmpegExportHasPortraitResolutionDurationAndDistinctAudioBands() {
        val dir = Files.createTempDirectory("popi-ffmpeg-test")
        val first = dir.resolve("first.mp4")
        val second = dir.resolve("second.mp4")
        val music = dir.resolve("music.m4a")
        makeVideo(first, 440)
        makeVideo(second, 660)
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=220:duration=6", "-c:a", "aac", music.toString())
        val project = testProject(first, second, music)
        val output = dir.resolve("export.mp4")
        DesktopRenderer().export(project, output)
        val probe = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "default=noprint_wrappers=1:nokey=1", output.toString()).lines()
        assertEquals("1080", probe[0])
        assertEquals("1920", probe[1])
        val duration = probe[2].toDouble()
        assertTrue(abs(duration - 6.0) <= 0.1, "expected 6 seconds ±100ms, got $duration")
        val level440 = bandDb(output, 1.0, 440); val level660 = bandDb(output, 4.0, 660); val level220 = bandDb(output, 1.0, 220)
        println("audio bands: 440Hz=${level440}dBFS 660Hz=${level660}dBFS 220Hz=${level220}dBFS")
        assertTrue(level440 >= -50.0, "clip 1 440 Hz band below -50 dBFS")
        assertTrue(level660 >= -50.0, "clip 2 660 Hz band below -50 dBFS")
        assertTrue(level220 >= -50.0, "music 220 Hz band below -50 dBFS")
    }

    @Test fun previewFramesAtOneAndFiveSecondsMatchExportAt540By960() {
        val dir = Files.createTempDirectory("popi-preview-test")
        val first = dir.resolve("first.mp4"); val second = dir.resolve("second.mp4"); val music = dir.resolve("music.m4a")
        makeVideo(first, 440); makeVideo(second, 660)
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=220:duration=6", "-c:a", "aac", music.toString())
        val project = testProject(first, second, music)
        val output = dir.resolve("export.mp4")
        DesktopRenderer().export(project, output)
        listOf(1_000L, 5_000L).forEach { timeMs ->
            val exported = dir.resolve("export-${timeMs}.png")
            run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-ss", "${timeMs / 1000.0}", "-i", output.toString(), "-frames:v", "1", "-vf", "scale=540:960", exported.toString())
            val expected = javax.imageio.ImageIO.read(exported.toFile())
            val actual = DesktopRenderer().previewFrame(project, timeMs)
            val psnr = psnr(expected, actual)
            println("preview/export PSNR at ${timeMs}ms: $psnr dB")
            assertTrue(psnr >= 30.0, "preview/export PSNR at ${timeMs}ms was $psnr dB")
        }
    }

    @Test fun cancelledExportStopsFfmpegAndDeletesPartialFile() {
        val dir = Files.createTempDirectory("popi-cancel-test")
        val source = dir.resolve("long.mp4")
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:size=320x180:rate=30:duration=60", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", source.toString())
        val project = Project(assets = listOf(Asset("v", "video", source.toUri().toString(), 60_000)), tracks = listOf(Track("v1", "video", listOf(Clip("c", "v", 0, 60_000))), Track("t1", "text"), Track("m1", "audio")))
        val output = dir.resolve("cancelled.mp4")
        val renderer = DesktopRenderer()
        val worker = Thread { runCatching { renderer.export(project, output) } }
        worker.start()
        val limit = System.nanoTime() + 10_000_000_000L
        while (!Files.exists(output) && worker.isAlive && System.nanoTime() < limit) Thread.sleep(20)
        assertTrue(Files.exists(output), "export process did not create its partial output")
        renderer.cancelExport()
        worker.join(15_000)
        assertTrue(!worker.isAlive, "ffmpeg export process did not terminate after cancellation")
        assertTrue(!Files.exists(output), "cancelled export left a partial output file")
    }
    @Test fun projectStoreAtomicallySavesAndRestoresLatestProject() {
        val root = Files.createTempDirectory("popi-store-test")
        val store = ProjectStore(root)
        val project = Project(id = "persisted")
        val path = store.save("Deneme", project)
        assertTrue(path.endsWith("projeler/Deneme.popi.json"))
        assertEquals(project, store.loadLast()?.second)
        assertTrue(Files.readString(path).contains("persisted"))
    }

    private fun testProject(first: Path, second: Path, music: Path) = Project(
        assets = listOf(Asset("v1", "video", first.toUri().toString(), 3000), Asset("v2", "video", second.toUri().toString(), 3000), Asset("music", "audio", music.toUri().toString(), 6000)),
        tracks = listOf(
            Track("v1", "video", listOf(Clip("c1", "v1", 0, 3000), Clip("c2", "v2", 0, 3000))),
            Track("t1", "text", items = listOf(TextItem("text1", "Merhaba Dünya", 0, 1500))),
            Track("m1", "audio", clips = listOf(Clip("a1", "music", 0, 6000, 0.3f))),
        ),
    )

    private fun makeVideo(path: Path, frequency: Int) {
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=3", "-f", "lavfi", "-i", "sine=frequency=$frequency:duration=3", "-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", path.toString())
    }

    private fun bandDb(file: Path, second: Double, frequency: Int): Double {
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-ss", second.toString(), "-t", "1", "-i", file.toString(), "-vn", "-ac", "1", "-ar", "48000", "-f", "s16le", "pipe:1").start()
        val bytes = process.inputStream.readBytes()
        val exit = process.waitFor()
        check(exit == 0) { "Could not decode audio band (exit=$exit)" }
        val samples = ShortArray(bytes.size / 2) { i -> (((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 255))).toShort() }
        val omega = 2.0 * Math.PI * frequency / 48000.0
        val coeff = 2.0 * kotlin.math.cos(omega)
        var q1 = 0.0; var q2 = 0.0
        samples.forEach { sample -> val q0 = sample.toDouble() + coeff * q1 - q2; q2 = q1; q1 = q0 }
        val power = q1 * q1 + q2 * q2 - coeff * q1 * q2
        val amplitude = kotlin.math.sqrt(2.0 * power) / samples.size / 32768.0
        return 20.0 * log10(amplitude.coerceAtLeast(1e-12))
    }

    private fun psnr(expected: java.awt.image.BufferedImage, actual: java.awt.image.BufferedImage): Double {
        assertEquals(540, actual.width); assertEquals(960, actual.height)
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        var squared = 0.0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = expected.getRGB(x, y); val b = actual.getRGB(x, y)
            for (shift in listOf(0, 8, 16)) { val d = ((a shr shift) and 255) - ((b shr shift) and 255); squared += d.toDouble() * d }
        }
        val mse = squared / (actual.width.toLong() * actual.height * 3)
        return if (mse == 0.0) Double.POSITIVE_INFINITY else 10.0 * log10(255.0 * 255.0 / mse)
    }

    private fun run(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        check(code == 0) { "${args.first()} failed ($code): $text" }
        return text.trim()
    }
}
