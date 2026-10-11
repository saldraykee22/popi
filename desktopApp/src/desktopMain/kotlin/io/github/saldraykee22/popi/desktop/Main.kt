package io.github.saldraykee22.popi.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.saldraykee22.popi.domain.*
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.concurrent.thread

private val ink = Color(0xFF000000)
private val surface = Color(0xFF111113)
private val edge = Color(0xFF2A2A2F)
private val paper = Color(0xFFE7E5E0)
private val muted = Color(0xFF9C9A93)
private val sand = Color(0xFFD2B47A)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "popi") {
        PopiTheme { DesktopScreen() }
    }
}

@Composable
internal fun PopiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = ink, surface = surface, primary = sand, onPrimary = ink, primaryContainer = sand, onPrimaryContainer = ink, secondary = sand, onSecondary = ink, secondaryContainer = sand, onSecondaryContainer = ink, tertiary = sand, onTertiary = ink, tertiaryContainer = sand, onTertiaryContainer = ink, onSurface = paper, onBackground = paper, outline = edge), content = content)
}

@Composable
internal fun DesktopScreen(storeOverride: ProjectStore? = null, platformOverride: DesktopPlatform? = null) {
    val platform = remember(platformOverride) { platformOverride ?: WindowsDesktopPlatform() }
    val store = remember(storeOverride) { storeOverride ?: ProjectStore() }
    val restored = remember { store.loadLast() }
    var engine by remember { mutableStateOf(CommandEngine(restored?.second ?: Project())) }
    var project by remember { mutableStateOf(engine.project) }
    var projectName by remember { mutableStateOf(restored?.first ?: "Adsız Proje") }
    var command by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Hazır") }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var exportRunning by remember { mutableStateOf(false) }
    var exportPercent by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val renderer = remember { DesktopRenderer() }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val previewRequest = remember { AtomicInteger() }
    val videoTrack: Track? = project.tracks.firstOrNull { it.kind == "video" }
    val audioTrack: Track? = project.tracks.firstOrNull { it.kind == "audio" }
    val selectedClip: Clip? = videoTrack?.clips?.firstOrNull { it.id == selectedClipId }
    val duration = videoTrack?.clips?.sumOf { it.outMs - it.inMs } ?: 0L

    fun refresh() { project = engine.project; if (selectedClipId !in videoTrack?.clips?.map { it.id }.orEmpty()) selectedClipId = null }
    fun save() { runCatching { store.save(projectName, project); message = "Kaydedildi" }.onFailure { message = "Kaydetme hatası: ${it.message}" } }
    fun execute(json: String) {
        runCatching { engine.executeJson(json); refresh(); save(); message = "Düzenleme uygulandı" }
            .onFailure { message = it.message ?: "Komut uygulanamadı" }
    }
    fun importFiles(files: List<File>) {
        files.forEach { file ->
            val ext = file.extension.lowercase()
            val kind = when (ext) { "mp4", "mov", "mkv", "webm", "avi" -> "video"; "mp3", "wav", "m4a", "aac", "ogg", "flac" -> "audio"; "png", "jpg", "jpeg", "webp" -> "image"; else -> null }
            if (kind == null) { message = "Desteklenmeyen dosya: ${file.name}"; return@forEach }
            val length = runCatching {
                val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath).start()
                val seconds = p.inputStream.bufferedReader().readText().trim().toDoubleOrNull() ?: 0.0
                if (p.waitFor() == 0) (seconds * 1000).toLong() else 0L
            }.getOrDefault(0L)
            val id = "asset-${UUID.randomUUID()}"
            engine.registerAssets(listOf(Asset(id, kind, file.toURI().toString(), length)))
            if (kind == "video") engine.executeJson("""{"op":"import_clip","assetId":"$id","trackId":"v1"}""")
            if (kind == "audio") {
                val audioCount = engine.project.tracks.firstOrNull { it.kind == "audio" }?.clips?.size ?: 0
                if (audioCount == 0) engine.executeJson("""{"op":"add_audio","assetId":"$id","trackId":"m1","volume":0.3}""")
            }
        }
        refresh(); save(); message = "${files.size} medya içe aktarıldı"
    }

    LaunchedEffect(project, projectName) {
        delay(500)
        runCatching { store.save(projectName, project) }
    }
    LaunchedEffect(project, playing, playheadMs) {
        if (!playing && duration > 0) {
            val request = previewRequest.incrementAndGet()
            val frameTime = playheadMs.coerceAtMost((duration - 34).coerceAtLeast(0))
            thread(name = "popi-preview-frame") {
                val began = System.nanoTime()
                runCatching { renderer.previewFrame(project, frameTime) }.onSuccess { frame ->
                    val elapsed = (System.nanoTime() - began) / 1_000_000
                    if (previewRequest.get() == request) {
                        previewImage = frame.toComposeImageBitmap()
                        message = "Önizleme ${elapsed} ms"
                    }
                    println("Popi preview render latency: ${elapsed} ms at ${frameTime} ms")
                }.onFailure { if (previewRequest.get() == request) message = "Önizleme hatası: ${it.message}" }
            }
        }
    }
    LaunchedEffect(playing, project, playheadMs) {
        if (playing) {
            val began = System.nanoTime()
            while (playing && playheadMs < duration) {
                delay(33)
                val elapsed = (System.nanoTime() - began) / 1_000_000
                playheadMs = (playheadMs + elapsed.coerceAtMost(100)).coerceAtMost(duration)
                if (playheadMs >= duration) playing = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(ink).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        when {
            ctrl && event.key == Key.Z -> { runCatching { engine.undo(); refresh(); save() }; true }
            ctrl && event.key == Key.Y -> { runCatching { engine.redo(); refresh(); save() }; true }
            ctrl && event.key == Key.S -> { save(); true }
            event.key == Key.Spacebar -> { playing = !playing; true }
            event.key == Key.Delete && selectedClipId != null -> { execute("""{"op":"delete_clip","clipId":"$selectedClipId"}"""); selectedClipId = null; true }
            else -> false
        }
    }.focusable()) {
        Row(Modifier.fillMaxWidth().height(58.dp).background(surface).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("popi", color = sand, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(projectName, { projectName = it }, Modifier.width(200.dp).height(54.dp), singleLine = true, textStyle = TextStyle(color = paper, fontSize = 14.sp), label = { Text("Proje adı", color = muted) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { runCatching { engine.undo(); refresh(); save(); message = "Geri alındı" }.onFailure { message = it.message ?: "Geri alınacak işlem yok" } }, enabled = engine.canUndo()) { Text("↶ Geri al") }
            TextButton(onClick = { runCatching { engine.redo(); refresh(); save(); message = "Yinelendi" }.onFailure { message = it.message ?: "Yinelenecek işlem yok" } }, enabled = engine.canRedo()) { Text("↷ Yinele") }
            Button(onClick = {
                val output = platform.chooseExportPath(projectName) ?: return@Button
                exportRunning = true; exportPercent = 0
                thread(name = "popi-export") {
                    runCatching { renderer.export(project, output) { exportPercent = it } }
                        .onSuccess { message = "Dışa aktarıldı: ${it.fileName}" }
                        .onFailure { message = "Dışa aktarma hatası: ${it.message}" }
                    exportRunning = false
                }
            }, enabled = !exportRunning && (videoTrack?.clips?.isNotEmpty() == true)) { Text(if (exportRunning) "Dışa aktarılıyor %$exportPercent" else "Dışa aktar") }
            if (exportRunning) TextButton(onClick = { renderer.cancelExport(); message = "Dışa aktarma iptal ediliyor" }) { Text("İptal") }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.width(250.dp).fillMaxHeight(), color = surface, border = BorderStroke(1.dp, edge), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("MEDYA", color = muted, fontSize = 12.sp, letterSpacing = 1.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = {
                        val files = platform.chooseMediaFiles()
                        importFiles(files)
                    }) { Text("+ İçe aktar", color = sand) } }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(project.assets, key = { it.id }) { asset ->
                            Surface(color = Color(0xFF19191C), border = BorderStroke(1.dp, edge), shape = RoundedCornerShape(7.dp)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(if (asset.kind == "audio") "♫" else "▣", color = sand, fontSize = 20.sp)
                                    Text(Path.of(java.net.URI(asset.uri)).fileName.toString(), color = paper, fontSize = 12.sp, maxLines = 1)
                                    Text("${asset.kind} · ${asset.durationMs / 1000}s", color = muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Dosyaları buraya sürükle", color = muted, fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.weight(1f).fillMaxWidth(), color = surface, border = BorderStroke(1.dp, edge), shape = RoundedCornerShape(10.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.heightIn(max = 410.dp).aspectRatio(9f / 16f).fillMaxHeight().background(Color(0xFF080809), RoundedCornerShape(8.dp)).testTag("preview-frame"), contentAlignment = Alignment.Center) {
                            if (previewImage != null) Image(previewImage!!, contentDescription = "Video önizlemesi", modifier = Modifier.fillMaxSize().testTag("preview-image"), contentScale = ContentScale.Fit)
                            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("9:16", color = muted, fontSize = 18.sp)
                                Text(if (videoTrack?.clips.isNullOrEmpty()) "Medya içe aktar" else "Önizleme hazırlanıyor", color = paper)
                            }
                            TextButton(onClick = { playing = !playing }) { Text(if (playing) "Ⅱ Duraklat" else "▶ Oynat", color = sand) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("${formatTime(playheadMs)} / ${formatTime(duration)}", color = muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Surface(Modifier.fillMaxWidth().height(228.dp), color = surface, border = BorderStroke(1.dp, edge), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("ZAMAN ÇİZELGESİ", color = muted, fontSize = 12.sp, letterSpacing = 1.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = { playheadMs = 0; playing = false }) { Text("Başa", color = sand) } }
                        TimelineTrack("Video", videoTrack?.clips.orEmpty().map { it.id to (project.assets.firstOrNull { a -> a.id == it.assetId }?.let { a -> Path.of(java.net.URI(a.uri)).fileName.toString() } ?: "Klip") }, selectedClipId, { selectedClipId = it }, playheadMs, duration, { playheadMs = it })
                        TimelineTrack("Metin", project.tracks.firstOrNull { it.kind == "text" }?.items.orEmpty().map { it.id to it.text }, null, {}, playheadMs, duration, { playheadMs = it })
                        TimelineTrack("Müzik", audioTrack?.clips.orEmpty().map { it.id to (project.assets.firstOrNull { a -> a.id == it.assetId }?.let { a -> Path.of(java.net.URI(a.uri)).fileName.toString() } ?: "Ses") }, null, {}, playheadMs, duration, { playheadMs = it })
                    }
                }
            }
            Surface(Modifier.width(300.dp).fillMaxHeight(), color = surface, border = BorderStroke(1.dp, edge), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GELİŞMİŞ: KOMUT", color = muted, fontSize = 12.sp, letterSpacing = 1.sp)
                    Text("M1'de yapay zekâ yok. JSON komutunu düzenleyip gönder.", color = muted, fontSize = 12.sp)
                    OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("JSON komutu", color = muted) }, placeholder = { Text("{\"op\":...}", color = muted) }, textStyle = TextStyle(color = paper, fontFamily = FontFamily.Monospace, fontSize = 12.sp), minLines = 8)
                    Button(onClick = { execute(command) }, Modifier.fillMaxWidth(), enabled = command.isNotBlank()) { Text("Komutu gönder") }
                    selectedClip?.let { clip ->
                        Text("KLİP AYRINTILARI", color = muted, fontSize = 12.sp)
                        var inText by remember(clip.id, clip.inMs) { mutableStateOf(clip.inMs.toString()) }
                        var outText by remember(clip.id, clip.outMs) { mutableStateOf(clip.outMs.toString()) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(inText, { inText = it }, Modifier.weight(1f).testTag("in-point"), label = { Text("Giriş ms") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                            OutlinedTextField(outText, { outText = it }, Modifier.weight(1f), label = { Text("Çıkış ms") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        }
                        Button(onClick = { execute("""{"op":"trim_clip","clipId":"${clip.id}","inMs":${inText.toLongOrNull() ?: clip.inMs},"outMs":${outText.toLongOrNull() ?: clip.outMs}}""") }, Modifier.fillMaxWidth()) { Text("Kırpmayı uygula") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            OutlinedButton(onClick = { val at = videoTrack!!.clips.indexOfFirst { it.id == clip.id }; if (at > 0) execute("""{"op":"move_clip","clipId":"${clip.id}","toIndex":${at - 1}}""") }, Modifier.weight(1f)) { Text("Sola") }
                            OutlinedButton(onClick = { val at = videoTrack!!.clips.indexOfFirst { it.id == clip.id }; if (at < videoTrack!!.clips.lastIndex) execute("""{"op":"move_clip","clipId":"${clip.id}","toIndex":${at + 1}}""") }, Modifier.weight(1f)) { Text("Sağa") }
                            OutlinedButton(onClick = { execute("""{"op":"delete_clip","clipId":"${clip.id}"}"""); selectedClipId = null }, Modifier.weight(1f)) { Text("Sil") }
                        }
                    }
                    Text(message, color = muted, fontSize = 11.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun TimelineTrack(name: String, entries: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, playhead: Long, duration: Long, onSeek: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = muted, modifier = Modifier.width(52.dp), fontSize = 12.sp)
        Row(Modifier.weight(1f).fillMaxHeight().background(ink, RoundedCornerShape(5.dp)).clickable { if (duration > 0) onSeek((playhead + 1000).coerceAtMost(duration)) }.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEach { (id, label) ->
                Surface(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(id) }, color = if (selected == id) sand else Color(0xFF303034), shape = RoundedCornerShape(4.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(label, color = if (selected == id) ink else paper, fontSize = 10.sp, maxLines = 1) }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String = "%02d:%02d.%1d".format(ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 100)
