package io.github.go0dboy.articlenavigator.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId

@Composable
fun InboxScreen(
    items: List<InboxItem>,
    busy: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onReject: (InboxItemId) -> Unit,
    onReadAndDiscard: (InboxItemId) -> Unit,
    onSave: (InboxItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedId by remember { mutableStateOf<InboxItemId?>(null) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Inbox", style = MaterialTheme.typography.headlineMedium)
                Text("Материалов на разбор: ${items.size}")
            }
            OutlinedButton(enabled = !busy, onClick = onRefresh) {
                Text("Обновить")
            }
        }

        message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        if (items.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Inbox пуст", style = MaterialTheme.typography.titleMedium)
                    Text("Запустите сбор источников. Новые материалы после загрузки и очистки появятся здесь.")
                }
            }
        }

        items.forEach { item ->
            val expanded = expandedId == item.id
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.canonicalUrl, style = MaterialTheme.typography.bodySmall)
                    item.publishedAt?.let { Text("Опубликовано: $it") }

                    if (expanded) {
                        Text(item.normalizedText, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(preview(item.normalizedText), style = MaterialTheme.typography.bodyMedium)
                    }

                    OutlinedButton(
                        enabled = !busy,
                        onClick = { expandedId = if (expanded) null else item.id },
                    ) {
                        Text(if (expanded) "Свернуть" else "Открыть")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = { onReject(item.id) },
                        ) {
                            Text("Не интересно")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = { onReadAndDiscard(item.id) },
                        ) {
                            Text("Прочитано")
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = { onSave(item.id) },
                    ) {
                        Text("Сохранить в базу знаний")
                    }
                }
            }
        }
    }
}

private fun preview(text: String): String {
    val compact = text.trim()
    if (compact.length <= 420) return compact
    return compact.take(420).trimEnd() + "…"
}
