package io.github.go0dboy.articlenavigator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.feature.inbox.InboxScreen
import io.github.go0dboy.articlenavigator.feature.sources.SourcesScreen
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

private enum class AppSection {
    SOURCES,
    INBOX,
    DIAGNOSTICS,
}

@Composable
private fun ArticleNavigatorApp(container: AppContainer) {
    var section by remember { mutableStateOf(AppSection.INBOX) }
    var status by remember { mutableStateOf<DeviceStatus?>(null) }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var inbox by remember { mutableStateOf<List<InboxItem>>(emptyList()) }
    var actionMessage by remember { mutableStateOf("Готов к работе") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshData(reportErrors: Boolean = false) {
        runCatching { container.listSources() }
            .onSuccess { sources = it }
            .onFailure { if (reportErrors) actionMessage = "Ошибка чтения источников: ${it.message}" }
        runCatching { container.loadInbox() }
            .onSuccess { inbox = it }
            .onFailure { if (reportErrors) actionMessage = "Ошибка чтения Inbox: ${it.message}" }
        runCatching { container.loadDeviceStatus() }
            .onSuccess { status = it }
            .onFailure { if (reportErrors) actionMessage = "Ошибка чтения статуса: ${it.message}" }
    }

    fun runAction(startMessage: String, action: suspend () -> String) {
        scope.launch {
            busy = true
            actionMessage = startMessage
            runCatching { action() }
                .onSuccess { actionMessage = it }
                .onFailure { actionMessage = "Ошибка: ${it.message ?: it::class.java.simpleName}" }
            refreshData()
            busy = false
        }
    }

    LaunchedEffect(container) {
        refreshData(reportErrors = true)
        while (true) {
            delay(2_000)
            refreshData()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Article Navigator · Phase 4",
                style = MaterialTheme.typography.headlineSmall,
            )
            SectionSelector(
                selected = section,
                inboxCount = inbox.size,
                onSelected = { section = it },
            )

            when (section) {
                AppSection.SOURCES -> SourcesScreen(
                    modifier = Modifier.weight(1f),
                    sources = sources,
                    busy = busy,
                    message = actionMessage,
                    onAddSource = { name, url ->
                        runAction("Добавляю источник…") {
                            val id = container.addRssSource(name, url)
                            "Источник сохранён: ${id.value}"
                        }
                    },
                    onCollectNow = {
                        runAction("Ставлю сбор в очередь…") {
                            val id = container.enqueueImmediateCollection()
                            "Сбор запущен: $id"
                        }
                    },
                    onRefresh = { scope.launch { refreshData(reportErrors = true) } },
                )

                AppSection.INBOX -> InboxScreen(
                    modifier = Modifier.weight(1f),
                    items = inbox,
                    busy = busy,
                    message = actionMessage,
                    onRefresh = { scope.launch { refreshData(reportErrors = true) } },
                    onReject = { id ->
                        runAction("Помечаю материал как неинтересный…") {
                            container.rejectInbox(id)
                            "Материал исключён из будущих повторов"
                        }
                    },
                    onReadAndDiscard = { id ->
                        runAction("Закрываю материал без сохранения…") {
                            container.readAndDiscardInbox(id)
                            "Материал отмечен прочитанным без сохранения"
                        }
                    },
                    onSave = { id ->
                        runAction("Сохраняю материал…") {
                            val documentId = container.saveInbox(id)
                            "Сохранено в базу знаний: ${documentId.value}"
                        }
                    },
                )

                AppSection.DIAGNOSTICS -> DiagnosticsScreen(
                    modifier = Modifier.weight(1f),
                    status = status,
                    message = actionMessage,
                    busy = busy,
                    onCollectNow = {
                        runAction("Ставлю сбор в очередь…") {
                            val id = container.enqueueImmediateCollection()
                            "Сбор запущен: $id"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionSelector(
    selected: AppSection,
    inboxCount: Int,
    onSelected: (AppSection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionButton(
            modifier = Modifier.weight(1f),
            selected = selected == AppSection.SOURCES,
            text = "Источники",
            onClick = { onSelected(AppSection.SOURCES) },
        )
        SectionButton(
            modifier = Modifier.weight(1f),
            selected = selected == AppSection.INBOX,
            text = "Inbox ($inboxCount)",
            onClick = { onSelected(AppSection.INBOX) },
        )
        SectionButton(
            modifier = Modifier.weight(1f),
            selected = selected == AppSection.DIAGNOSTICS,
            text = "Статус",
            onClick = { onSelected(AppSection.DIAGNOSTICS) },
        )
    }
}

@Composable
private fun SectionButton(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(modifier = modifier, onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(modifier = modifier, onClick = onClick) { Text(text) }
    }
}

@Composable
private fun DiagnosticsScreen(
    status: DeviceStatus?,
    message: String,
    busy: Boolean,
    onCollectNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Диагностика сквозной цепочки WorkManager → discovery → fetch → Inbox → Room.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = onCollectNow,
        ) {
            Text(if (busy) "Выполняется…" else "Запустить сбор сейчас")
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)
        status?.let { DeviceStatusCard(it) } ?: Text("Инициализация локальной базы…")
        Text(
            "Периодический сбор зарегистрирован через WorkManager. Android выбирает точное время фонового запуска; ручной запуск нужен для проверки и немедленного обновления.",
            style = MaterialTheme.typography.bodySmall,
        )
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
            Text("Всего discovered-записей: ${status.totalDiscoveredCount}")
            Text("Сейчас в Inbox: ${status.pendingInboxCount}")
            Text("Последовательных ошибок: ${status.consecutiveFailures}")
            status.lastError?.let { Text("Ошибка: $it") }
            if (status.latestTitles.isNotEmpty()) {
                Text("Последние discovered-элементы:", style = MaterialTheme.typography.titleSmall)
                status.latestTitles.forEach { title -> Text("• $title") }
            }
        }
    }
}

private fun formatInstant(value: Instant?): String = value?.toString() ?: "—"
