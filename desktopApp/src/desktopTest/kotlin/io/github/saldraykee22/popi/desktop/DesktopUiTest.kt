package io.github.saldraykee22.popi.desktop

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DesktopUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun importSelectTrimUndoRestoresClipAndCapturesComposeScreen() = runComposeUiTest {
        val directory = Files.createTempDirectory("popi-ui-test")
        val video = directory.resolve("ui-clip.mp4")
        val process = ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString()).redirectErrorStream(true).start()
        val error = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor() == 0, "test clip generation failed: $error")
        val root = Files.createTempDirectory("popi-ui-store")
        val testPlatform = object : DesktopPlatform {
            override fun chooseMediaFiles() = listOf(video.toFile())
            override fun chooseExportPath(projectName: String): Path? = null
        }
        setContent { PopiTheme { DesktopScreen(ProjectStore(root), testPlatform) } }
        onNodeWithText("+ İçe aktar").fetchSemanticsNode(); onNodeWithText("+ İçe aktar").performClick()
        waitForIdle()
        onAllNodesWithText("ui-clip.mp4")[1].performClick()
        onNodeWithTag("in-point").performTextReplacement("500")
        onNodeWithText("Kırpmayı uygula").performClick()
        waitForIdle()
        assertEquals("500", onNodeWithTag("in-point").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        onNodeWithText("↶ Geri al").performClick()
        waitForIdle()
        assertEquals("0", onNodeWithTag("in-point").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("preview-image").fetchSemanticsNodes().isNotEmpty() }
        val captured = onRoot().captureToImage()
        val pixelMap = captured.toPixelMap()
        val screenshot = BufferedImage(captured.width, captured.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until captured.height) for (x in 0 until captured.width) screenshot.setRGB(x, y, pixelMap[x, y].toArgb())
        val target = System.getenv("POPI_SCREENSHOT_PATH")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: Path.of("build", "reports", "B7-ekran.png").toAbsolutePath()
        Files.createDirectories(target.parent)
        ImageIO.write(screenshot, "png", target.toFile())
        assertTrue(Files.size(target) > 10_000, "Compose screenshot is empty: $target")
    }
}


