package io.github.saldraykee22.popi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.saldraykee22.popi.domain.CommandEngine
import io.github.saldraykee22.popi.domain.Project
import io.github.saldraykee22.popi.domain.ProjectJson
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Popi Desktop") {
        DesktopScreen()
    }
}

@Composable
private fun DesktopScreen() {
    var engine by remember { mutableStateOf(CommandEngine(Project())) }
    var project by remember { mutableStateOf(engine.project) }
    var command by remember { mutableStateOf("{\"op\":\"add_text\",\"trackId\":\"t1\",\"text\":\"Merhaba Dünya\",\"startMs\":0,\"durationMs\":3000}") }
    var message by remember { mutableStateOf("") }
    var loadedFile by remember { mutableStateOf<File?>(null) }
    fun refresh() { project = engine.project }
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Popi · Desktop", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Proje JSON yükle", FileDialog.LOAD)
                    dialog.isVisible = true
                    val file = dialog.file?.let { File(dialog.directory, it) }
                    if (file != null) runCatching {
                        val loaded = ProjectJson.decode(file.readText())
                        engine = CommandEngine(loaded); loadedFile = file; refresh(); message = "Proje yüklendi"
                    }.onFailure { message = it.message ?: "Proje açılamadı" }
                }) { Text("JSON yükle") }
                OutlinedButton(onClick = {
                    runCatching {
                        val file = loadedFile ?: run {
                            val dialog = FileDialog(null as Frame?, "Projeyi JSON kaydet", FileDialog.SAVE)
                            dialog.file = "popi-project.json"; dialog.isVisible = true
                            dialog.file?.let { File(dialog.directory, it) } ?: error("Kaydetme iptal edildi")
                        }
                        file.writeText(ProjectJson.encode(project)); loadedFile = file; message = "Kaydedildi: ${file.name}"
                    }.onFailure { message = it.message ?: "Kaydedilemedi" }
                }) { Text("JSON kaydet") }
                OutlinedButton(onClick = { runCatching { engine.executeJson("{\"op\":\"undo\"}"); refresh(); message = "Geri alındı" }.onFailure { message = it.message ?: "Undo yok" } }) { Text("Undo") }
                OutlinedButton(onClick = { runCatching { engine.executeJson("{\"op\":\"redo\"}"); refresh(); message = "Yinelendi" }.onFailure { message = it.message ?: "Redo yok" } }) { Text("Redo") }
            }
            Text("Klipler", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f)) {
                items(project.tracks.firstOrNull { it.kind == "video" }?.clips.orEmpty(), key = { it.id }) { clip ->
                    val asset = project.assets.firstOrNull { it.id == clip.assetId }
                    Text("${asset?.id ?: clip.assetId} · ${clip.inMs}–${clip.outMs} ms · ${asset?.uri.orEmpty()}")
                }
            }
            OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("JSON komutu") }, minLines = 2)
            Button(onClick = { runCatching { engine.executeJson(command); refresh(); message = "Komut uygulandı" }.onFailure { message = it.message ?: "Komut geçersiz" } }) { Text("Komutu uygula") }
            if (message.isNotBlank()) Text(message)
        }
    }
}
