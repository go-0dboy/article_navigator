package io.github.go0dboy.articlenavigator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ArticleNavigatorApplication).container
        setContent {
            MaterialTheme {
                ArticleNavigatorApp(container)
            }
        }
    }
}

@Composable
private fun ArticleNavigatorApp(container: AppContainer) {
    var status by remember { mutableStateOf<DeviceStatus?>(null) }
    var actionMessage by remember { mutableStateOf("Готов к проверке") }
    var launching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(container) {
        while (true) {
            runCatching { container.loadDeviceStatus() }
                .onSuccess { status = it }
                .onFailure { actionMessage = "Ошибка чтения статуса: ${it.message}" }
            delay(2_000)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Article Navigator · Phase 3",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Проверка цепочки WorkManager → scheduler → HTTP/RSS → Room на реальном устройстве.",
                style = MaterialTheme.typography.bodyLarge,
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !launching,
                onClick = {
                    scope.launch {
                        launching = true
                        actionMessage = "Ставлю сбор в очередь…"
                        runCatching { container.enqueueImmediateCollection() }
                            .onSuccess { id -> actionMessage = "Задание запущено: $id" }
                            .onFailure { error -> actionMessage = "Не удалось запустить: ${error.message}" }
                        launching = false
                    }
                },
            ) {
                Text(if (launching) "Запуск…" else "Запустить сбор сейчас")
            }

            Text(actionMessage, style = MaterialTheme.typography.bodyMedium)

            status?.let { DeviceStatusCard(it) }
                ?: Text("Инициализация локальной базы…")

            Text(
                text = "Периодический сбор также зарегистрирован через WorkManager. Android сам выбирает точное время фонового запуска; кнопка выше нужна для немедленной проверки.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeviceStatusCard(status: DeviceStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(status.sourceName, style = MaterialTheme.typography.titleMedium)
            Text(status.sourceUrl, style = MaterialTheme.typography.bodySmall)
            Text("Последняя попытка: ${formatInstant(status.lastAttemptAt)}")
            Text("Последний успешный сбор: ${formatInstant(status.lastSuccessfulCheckAt)}")
            Text("Следующая проверка: ${formatInstant(status.nextCheckAt)}")
            Text("Найдено в последнем запуске: ${status.lastDiscoveredCount}")
            Text("Всего найдено в БД: ${status.totalDiscoveredCount}")
            Text("Последовательных ошибок: ${status.consecutiveFailures}")
            if (status.lastError != null) {
                Text("Ошибка: ${status.lastError}")
            }
            if (status.latestTitles.isNotEmpty()) {
                Text("Последние элементы:", style = MaterialTheme.typography.titleSmall)
                status.latestTitles.forEach { title -> Text("• $title") }
            }
        }
    }
}

private fun formatInstant(value: Instant?): String = value?.toString() ?: "—"
