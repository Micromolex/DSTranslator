package com.nya.dstranslate.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TextTranslateScreen(vm: MainViewModel) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.recognizeImage(uri) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { vm.recognizeImage(it) } }

    LaunchedEffect(vm.imageUri) {
        preview = vm.imageUri?.let { uri ->
            withContext(Dispatchers.IO) { decodePreview(context, uri) }?.asImageBitmap()
        }
    }

    // Уход с вкладки глушит микрофон: голосовой ввод не должен работать в фоне.
    DisposableEffect(Unit) {
        onDispose { vm.stopVoiceInput() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${vm.fromLang.flag} ${vm.fromLang.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.swapLanguages() }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = s.swapLanguages)
                    }
                    Text(
                        text = "${vm.fromLang.other().flag} ${vm.fromLang.other().label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Text(
                text = s.autoDirection,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = vm.textInput,
                onValueChange = { vm.onTextInputChange(it) },
                label = { Text("${s.inputLabel} · ${vm.fromLang.label}") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                trailingIcon = {
                    if (vm.textInput.isNotEmpty()) {
                        IconButton(onClick = {
                            vm.clearText()
                            vm.clearImage()
                        }) {
                            Icon(Icons.Filled.Clear, contentDescription = s.clear)
                        }
                    }
                }
            )
        }

        // ---------------------------------------------------- картинка и камера
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(s.imagePick)
                }
                OutlinedButton(
                    onClick = {
                        val uri = vm.createCameraUri()
                        cameraUri = uri
                        takePhoto.launch(uri)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(s.imageCamera)
                }
            }
        }

        if (vm.imageBusy) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(s.imageProcessing, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        preview?.let { bitmap ->
            item {
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                }
            }
        }

        if (vm.imageNoText || vm.imageError != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = vm.imageError ?: s.imageNoText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.imageCyrillicWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.translateText() },
                    enabled = !vm.textBusy && vm.textInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (vm.textBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (vm.textBusy) s.translating else s.translate)
                }
                TextButton(
                    onClick = {
                        if (vm.voiceInputActive) vm.stopVoiceInput() else vm.startVoiceInput()
                    }
                ) {
                    Icon(
                        imageVector = if (vm.voiceInputActive) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (vm.voiceInputActive) s.voiceStop else s.voiceInput,
                        tint = if (vm.voiceInputActive) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(
                    onClick = { vm.toggleSpeak(vm.textInput, vm.fromLang.speech) },
                    enabled = vm.textInput.isNotBlank()
                ) {
                    Icon(
                        imageVector = if (vm.isSpeaking) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                        contentDescription = if (vm.isSpeaking) s.stopSpeaking else s.speak
                    )
                }
            }
        }

        // ------------------------------------------------ голосовой ввод текста
        if (vm.voiceInputActive) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        s.voiceListening,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        vm.voiceError?.let { err ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${vm.fromLang.other().flag} ${vm.fromLang.other().label}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.weight(1f))
                        if (vm.textOutput.isNotBlank()) {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(vm.textOutput))
                                toast = s.copied
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = s.copy)
                            }
                            IconButton(onClick = {
                                vm.toggleSpeak(vm.textOutput, vm.fromLang.other().speech)
                            }) {
                                Icon(
                                    imageVector = if (vm.isSpeaking) Icons.Filled.Stop
                                    else Icons.Filled.VolumeUp,
                                    contentDescription = if (vm.isSpeaking) s.stopSpeaking else s.speak,
                                    tint = if (vm.isSpeaking) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        val shown = vm.textOutput
                        Text(
                            text = shown.ifBlank { s.outputPlaceholder },
                            fontSize = if (shown.isBlank()) 17.sp else 28.sp,
                            lineHeight = if (shown.isBlank()) 22.sp else 36.sp,
                            fontWeight = if (shown.isBlank()) FontWeight.Normal else FontWeight.Bold,
                            color = if (shown.isBlank())
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        vm.textError?.let { err ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        toast?.let {
            item { Text(it, style = MaterialTheme.typography.labelMedium) }
        }

        // ------------------------------------------------- история переводов
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.historyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (vm.history.isNotEmpty()) {
                    IconButton(onClick = { vm.clearHistory() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = s.historyClear)
                    }
                }
            }
        }

        if (vm.history.isEmpty()) {
            item {
                Text(
                    text = s.historyEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(vm.history, key = { it.id }) { entry ->
                HistoryCard(
                    entry = entry,
                    onDelete = { vm.removeHistory(entry.id) },
                    onReuse = { vm.reuseHistory(entry) }
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    onDelete: () -> Unit,
    onReuse: () -> Unit
) {
    val s = LocalStrings.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${entry.from.flag} → ${entry.from.other().flag}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onReuse) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = s.copy,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = s.delete,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = entry.source,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.target,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun decodePreview(
    context: android.content.Context,
    uri: android.net.Uri
): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}
