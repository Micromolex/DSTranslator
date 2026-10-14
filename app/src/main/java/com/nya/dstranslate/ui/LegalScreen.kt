package com.nya.dstranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Правовая информация. Текст показывается одним TextView (в Compose — один Text)
 * с переносами строк, чтобы на экране и в диалоге первого запуска было ровно
 * одно и то же содержимое — см. [Legal.text].
 */
object Legal {

    /** Копирайт, оговорка об ИИ-сервисах, отказ от ответственности и контакт. */
    fun text(): String = listOf(
        "© Aleksandr Zakandykin, 2026. Все права защищены.",
        "Приложение использует сторонние ИИ-сервисы. Перевод предоставляется 'как есть' (as is).",
        "Автор не несёт ответственности за любые убытки, возникшие в результате использования приложения.",
        "Контакт для связи: t.me/micromolex"
    ).joinToString("\n")
}

/** Отдельный экран настроек: «О приложении / Правовая информация». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(onBack: () -> Unit) {
    val s = LocalStrings.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.settingsAboutRow, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                s.legalTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    // Один TextView с переносами строк — как в диалоге первого запуска.
                    Text(
                        Legal.text(),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 24.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(1.dp))
        }
    }
}
