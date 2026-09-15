package io.github.go0dboy.articlenavigator.feature.sources

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.go0dboy.articlenavigator.core.model.Source

@Composable
fun SourcesScreen(
    sources: List<Source>,
    busy: Boolean,
    message: String?,
    onAddSource: (name: String, url: String) -> Unit,
    onCollectNow: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Источники", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Добавьте RSS/Atom-ленту. Источники хранятся локально и собираются через WorkManager.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Новый RSS / Atom", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL ленты") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && url.isNotBlank(),
                    onClick = {
                        onAddSource(name, url)
                        name = ""
                        url = ""
                    },
                ) {
                    Text("Добавить источник")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onCollectNow,
            ) {
                Text(if (busy) "Сбор…" else "Собрать сейчас")
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = onRefresh,
            ) {
                Text("Обновить")
            }
        }

        message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        if (sources.isEmpty()) {
            Text("Источников пока нет.")
        } else {
            Text("Сохранённые источники: ${sources.size}", style = MaterialTheme.typography.titleMedium)
            sources.forEach { source ->
                SourceCard(source)
            }
        }
    }
}

@Composable
private fun SourceCard(source: Source) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text(source.url, style = MaterialTheme.typography.bodySmall)
            Text("Тип: ${source.type}")
            Text("Состояние: ${if (source.enabled) "включён" else "выключен"}")
            Text("Последний успешный сбор: ${source.lastSuccessfulCheckAt ?: "—"}")
            Text("Следующая проверка: ${source.nextCheckAt ?: "—"}")
        }
    }
}
