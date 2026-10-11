package io.github.saldraykee22.popi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.saldraykee22.popi.desktop.ui.MediaEntry
import io.github.saldraykee22.popi.desktop.ui.MediaKind
import io.github.saldraykee22.popi.desktop.ui.MediaPanel
import io.github.saldraykee22.popi.desktop.ui.MediaThumbnailSource
import io.github.saldraykee22.popi.desktop.ui.PopiColors
import io.github.saldraykee22.popi.desktop.ui.PopiFonts
import io.github.saldraykee22.popi.desktop.ui.PopiPanel
import io.github.saldraykee22.popi.desktop.ui.PopiTheme
import io.github.saldraykee22.popi.desktop.ui.PreviewArea
import io.github.saldraykee22.popi.desktop.ui.PreviewFrame
import io.github.saldraykee22.popi.desktop.ui.StatusLine
import io.github.saldraykee22.popi.desktop.ui.StatusMessage
import io.github.saldraykee22.popi.desktop.ui.TimelineBlock
import io.github.saldraykee22.popi.desktop.ui.TimelineLane
import io.github.saldraykee22.popi.desktop.ui.formatTimecode
import io.github.saldraykee22.popi.desktop.ui.planMediaImport
import io.github.saldraykee22.popi.domain.Asset
import io.github.saldraykee22.popi.domain.Clip
import io.github.saldraykee22.popi.domain.CommandEngine
import io.github.saldraykee22.popi.domain.Project
import io.github.saldraykee22.popi.domain.Track
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun main() = application {
    val dropInbox = remember { FileDropInbox() }
    Window(onCloseRequest = ::exitApplication, title = "popi") {
        DisposableEffect(window) {
            installFileDrop(window) { files -> dropInbox.offer(files) }
            onDispose { window.dropTarget = null }
        }
        PopiTheme { DesktopScreen(dropInbox = dropInbox) }
    }
}

/** Pencereye bırakılan dosya gruplarını arayüze iletir. Gönderim iş parçacığı güvenlidir. */
class FileDropInbox {
    private val channel = Channel<List<File>>(Channel.UNLIMITED)

    internal val batches: ReceiveChannel<List<File>> get() = channel

    fun offer(files: List<File>) {
        channel.trySend(files)
    }
}

/** Windows Gezgini'nden bırakılan dosyaları yakalar. Bırakma geçersizse reddeder. */
private fun installFileDrop(component: Component, onFiles: (List<File>) -> Unit): DropTarget =
    DropTarget(component, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.rejectDrop()
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull()
            event.dropComplete(files != null)
            if (files != null) onFiles(files)
        }
    }, true)

@Composable
internal fun DesktopScreen(
    storeOverride: ProjectStore? = null,
    platformOverride: DesktopPlatform? = null,
    thumbnailOverride: MediaThumbnailSource? = null,
    dropInbox: FileDropInbox? = null,
    previewContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val platform = remember(platformOverride) { platformOverride ?: WindowsDesktopPlatform() }
    val store = remember(storeOverride) { storeOverride ?: ProjectStore() }
    val thumbnails = remember(thumbnailOverride) {
        thumbnailOverride ?: DesktopMediaThumbnails(Thumbnails(defaultThumbnailCacheDir()))
    }
    val restored = remember { store.loadLast() }
    var engine by remember { mutableStateOf(CommandEngine(restored?.second ?: Project())) }
    var project by remember { mutableStateOf(engine.project) }
    var projectName by remember { mutableStateOf(restored?.first ?: "Adsız Proje") }
    var command by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(StatusMessage("Hazır")) }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var exportRunning by remember { mutableStateOf(false) }
    var exportPercent by remember { mutableIntStateOf(0) }
    val renderer = remember { DesktopRenderer() }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val previewRequest = remember { AtomicInteger() }
    val videoTrack: Track? = project.tracks.firstOrNull { it.kind == "video" }
    val audioTrack: Track? = project.tracks.firstOrNull { it.kind == "audio" }
    val textTrack: Track? = project.tracks.firstOrNull { it.kind == "text" }
    val selectedClip: Clip? = videoTrack?.clips?.firstOrNull { it.id == selectedClipId }
    val duration = videoTrack?.clips?.sumOf { it.outMs - it.inMs } ?: 0L

    fun assetFor(assetId: String): Asset? = project.assets.firstOrNull { it.id == assetId }
    fun refresh() {
        project = engine.project
        if (selectedClipId !in videoTrack?.clips?.map { it.id }.orEmpty()) selectedClipId = null
    }
    fun save() {
        runCatching { store.save(projectName, project); status = StatusMessage("Kaydedildi") }
            .onFailure { status = StatusMessage("Kaydetme hatası: ${it.message}", true) }
    }
    fun execute(json: String) {
        runCatching { engine.executeJson(json); refresh(); save(); status = StatusMessage("Düzenleme uygulandı") }
            .onFailure { status = StatusMessage(it.message ?: "Komut uygulanamadı", true) }
    }
    fun importFiles(files: List<File>) {
        val plan = planMediaImport(files) { it.name }
        val result = runCatching {
            plan.accepted.forEach { (file, kind) ->
                val id = "asset-${UUID.randomUUID()}"
                engine.registerAssets(listOf(Asset(id, kind.wire, file.toURI().toString(), probeDurationMs(file))))
                when (kind) {
                    MediaKind.VIDEO -> engine.executeJson("""{"op":"import_clip","assetId":"$id","trackId":"v1"}""")
                    MediaKind.AUDIO -> {
                        val audioCount = engine.project.tracks.firstOrNull { it.kind == "audio" }?.clips?.size ?: 0
                        if (audioCount == 0) engine.executeJson("""{"op":"add_audio","assetId":"$id","trackId":"m1","volume":0.3}""")
                    }
                }
            }
            refresh()
            save()
        }
        val notice = plan.notice
        status = when {
            result.isFailure -> StatusMessage(result.exceptionOrNull()?.message ?: "İçe aktarma hatası", true)
            notice != null && plan.accepted.isEmpty() -> StatusMessage(notice, true)
            notice != null -> StatusMessage("${plan.accepted.size} medya içe aktarıldı. $notice", true)
            else -> StatusMessage("${plan.accepted.size} medya içe aktarıldı")
        }
    }

    LaunchedEffect(project, projectName) {
        delay(500)
        runCatching { store.save(projectName, project) }
    }
    LaunchedEffect(dropInbox) {
        dropInbox?.let { inbox -> for (batch in inbox.batches) importFiles(batch) }
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
                        status = StatusMessage("Önizleme ${elapsed} ms")
                    }
                    println("Popi preview render latency: ${elapsed} ms at ${frameTime} ms")
                }.onFailure { if (previewRequest.get() == request) status = StatusMessage("Önizleme hatası: ${it.message}", true) }
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

    // Zaman çizelgesi blokları: video klipleri art arda, ses ve metin kendi başlangıçlarında.
    val videoBlocks: List<TimelineBlock> = run {
        var cursor = 0L
        videoTrack?.clips.orEmpty().map { clip ->
            val length = clip.outMs - clip.inMs
            TimelineBlock(clip.id, assetFor(clip.assetId)?.let(::displayName) ?: "Klip", cursor, length, assetFor(clip.assetId))
                .also { cursor += length }
        }
    }
    val audioBlocks: List<TimelineBlock> = audioTrack?.clips.orEmpty().map { clip ->
        TimelineBlock(clip.id, assetFor(clip.assetId)?.let(::displayName) ?: "Ses", clip.startMs ?: 0L, clip.outMs - clip.inMs, assetFor(clip.assetId))
    }
    val textBlocks: List<TimelineBlock> = textTrack?.items.orEmpty().map { item ->
        TimelineBlock(item.id, item.text, item.startMs, item.durationMs)
    }
    val timelineTotal = (videoBlocks + audioBlocks + textBlocks)
        .maxOfOrNull { it.startMs + it.durationMs }
        ?.coerceAtLeast(duration)
        ?.coerceAtLeast(1L)
        ?: duration.coerceAtLeast(1L)
    val mediaEntries = project.assets.map { MediaEntry(it, displayName(it)) }
    val hint = if (videoTrack?.clips.isNullOrEmpty()) "Medya içe aktar" else "Önizleme hazırlanıyor"
    val defaultPreview: @Composable (Modifier) -> Unit = { modifier -> PreviewFrame(modifier, previewImage, hint) }

    Column(Modifier.fillMaxSize().background(PopiColors.ground).onPreviewKeyEvent { event ->
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
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(PopiColors.surface).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("popi", color = PopiColors.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                modifier = Modifier.width(220.dp),
                singleLine = true,
                textStyle = TextStyle(color = PopiColors.text, fontSize = 14.sp),
                label = { Text("Proje adı", color = PopiColors.textMuted) },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    runCatching { engine.undo(); refresh(); save(); status = StatusMessage("Geri alındı") }
                        .onFailure { status = StatusMessage(it.message ?: "Geri alınacak işlem yok", true) }
                },
                enabled = engine.canUndo(),
            ) { Text("↶ Geri al") }
            TextButton(
                onClick = {
                    runCatching { engine.redo(); refresh(); save(); status = StatusMessage("Yinelendi") }
                        .onFailure { status = StatusMessage(it.message ?: "Yinelenecek işlem yok", true) }
                },
                enabled = engine.canRedo(),
            ) { Text("↷ Yinele") }
            Button(
                onClick = {
                    val output = platform.chooseExportPath(projectName) ?: return@Button
                    exportRunning = true
                    exportPercent = 0
                    thread(name = "popi-export") {
                        runCatching { renderer.export(project, output) { exportPercent = it } }
                            .onSuccess { status = StatusMessage("Dışa aktarıldı: ${it.fileName}") }
                            .onFailure { status = StatusMessage("Dışa aktarma hatası: ${it.message}", true) }
                        exportRunning = false
                    }
                },
                enabled = !exportRunning && (videoTrack?.clips?.isNotEmpty() == true),
            ) { Text(if (exportRunning) "Dışa aktarılıyor %$exportPercent" else "Dışa aktar") }
            if (exportRunning) {
                TextButton(onClick = { renderer.cancelExport(); status = StatusMessage("Dışa aktarma iptal ediliyor") }) { Text("İptal") }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaPanel(
                entries = mediaEntries,
                thumbnails = thumbnails,
                onImport = { importFiles(platform.chooseMediaFiles()) },
                modifier = Modifier.width(250.dp).fillMaxHeight(),
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewArea(
                    preview = previewContent ?: defaultPreview,
                    playing = playing,
                    playheadLabel = "${formatTimecode(playheadMs)} / ${formatTimecode(duration)}",
                    onTogglePlay = { playing = !playing },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                PopiPanel(Modifier.fillMaxWidth().height(228.dp), padding = 12.dp, spacing = 6.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZAMAN ÇİZELGESİ", color = PopiColors.textMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { playheadMs = 0; playing = false }) { Text("Başa", color = PopiColors.accent) }
                    }
                    TimelineLane(
                        name = "Video",
                        blocks = videoBlocks,
                        totalMs = timelineTotal,
                        selectedId = selectedClipId,
                        thumbnails = thumbnails,
                        onSelect = { selectedClipId = it },
                        onSeek = { playheadMs = it },
                    )
                    TimelineLane(
                        name = "Metin",
                        blocks = textBlocks,
                        totalMs = timelineTotal,
                        selectedId = null,
                        thumbnails = thumbnails,
                        onSelect = {},
                        onSeek = { playheadMs = it },
                    )
                    TimelineLane(
                        name = "Müzik",
                        blocks = audioBlocks,
                        totalMs = timelineTotal,
                        selectedId = null,
                        thumbnails = thumbnails,
                        onSelect = {},
                        onSeek = { playheadMs = it },
                    )
                }
            }
            PopiPanel(Modifier.width(300.dp).fillMaxHeight(), padding = 14.dp, spacing = 10.dp) {
                Text("GELİŞMİŞ: KOMUT", color = PopiColors.textMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                Text("M1'de yapay zekâ yok. JSON komutunu düzenleyip gönder.", color = PopiColors.textMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("command-input"),
                    label = { Text("JSON komutu", color = PopiColors.textMuted) },
                    placeholder = { Text("{\"op\":...}", color = PopiColors.textMuted) },
                    textStyle = TextStyle(color = PopiColors.text, fontFamily = PopiFonts.mono, fontSize = 12.sp),
                    minLines = 8,
                )
                Button(onClick = { execute(command) }, Modifier.fillMaxWidth(), enabled = command.isNotBlank()) { Text("Komutu gönder") }
                selectedClip?.let { clip ->
                    val clips = videoTrack?.clips.orEmpty()
                    Text("KLİP AYRINTILARI", color = PopiColors.textMuted, fontSize = 12.sp)
                    var inText by remember(clip.id, clip.inMs) { mutableStateOf(clip.inMs.toString()) }
                    var outText by remember(clip.id, clip.outMs) { mutableStateOf(clip.outMs.toString()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = inText,
                            onValueChange = { inText = it },
                            modifier = Modifier.weight(1f).testTag("in-point"),
                            label = { Text("Giriş ms") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = outText,
                            onValueChange = { outText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Çıkış ms") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                    Button(
                        onClick = {
                            execute("""{"op":"trim_clip","clipId":"${clip.id}","inMs":${inText.toLongOrNull() ?: clip.inMs},"outMs":${outText.toLongOrNull() ?: clip.outMs}}""")
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text("Kırpmayı uygula") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton(
                            onClick = {
                                val at = clips.indexOfFirst { it.id == clip.id }
                                if (at > 0) execute("""{"op":"move_clip","clipId":"${clip.id}","toIndex":${at - 1}}""")
                            },
                            Modifier.weight(1f),
                        ) { Text("Sola") }
                        OutlinedButton(
                            onClick = {
                                val at = clips.indexOfFirst { it.id == clip.id }
                                if (at < clips.lastIndex) execute("""{"op":"move_clip","clipId":"${clip.id}","toIndex":${at + 1}}""")
                            },
                            Modifier.weight(1f),
                        ) { Text("Sağa") }
                        OutlinedButton(
                            onClick = { execute("""{"op":"delete_clip","clipId":"${clip.id}"}"""); selectedClipId = null },
                            Modifier.weight(1f),
                        ) { Text("Sil") }
                    }
                }
                StatusLine(status)
            }
        }
    }
}

/** Dosya süresi (ms). FFprobe yoksa ya da okunamazsa 0 döner. */
private fun probeDurationMs(file: File): Long = runCatching {
    val process = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath).start()
    val seconds = process.inputStream.bufferedReader().readText().trim().toDoubleOrNull() ?: 0.0
    if (process.waitFor() == 0) (seconds * 1000).toLong() else 0L
}.getOrDefault(0L)

/** Varlık için ekranda görünecek dosya adı. */
private fun displayName(asset: Asset): String =
    runCatching { Path.of(URI(asset.uri)).fileName.toString() }.getOrDefault(asset.uri)
