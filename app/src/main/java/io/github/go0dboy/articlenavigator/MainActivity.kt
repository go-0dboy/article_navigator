package io.github.go0dboy.articlenavigator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.feature.inbox.InboxActions
import io.github.go0dboy.articlenavigator.feature.inbox.InboxRoute
import io.github.go0dboy.articlenavigator.feature.library.LibraryRoute
import io.github.go0dboy.articlenavigator.feature.sources.SourcesScreen
import java.time.Instant
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
    LIBRARY,
    DIAGNOSTICS,
}

@Composable
private fun ArticleNavigatorApp(container: AppContainer) {
    var sectionName by rememberSaveable { mutableStateOf(AppSection.INBOX.name) }
    val section = AppSection.valueOf(sectionName)
    var selectedLibraryDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<DeviceStatus?>(null) }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var actionMessage by remember { mutableStateOf("Готов к работе") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val inboxCountFlow = remember(container) { container.inboxPagingRepository.observePendingCount() }
    val libraryCountFlow = remember(container) { container.libraryRepository.observeSavedCount() }
    val inboxCount by inboxCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val libraryCount by libraryCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    val inboxActions = remember(container) {
        object : InboxActions {
            override suspend fun save(id: io.github.go0dboy.articlenavigator.core.model.InboxItemId): DocumentId =
                container.saveInbox(id)

            override suspend fun reject(id: io.github.go0dboy.articlenavigator.core.model.InboxItemId): Boolean =
                container.rejectInbox(id)

            override suspend fun readAndDiscard(id: io.github.go0dboy.articlenavigator.core.model.InboxItemId): Boolean =
                container.readAndDiscardInbox(id)
        }
    }

    suspend fun refreshOperationalData(reportErrors: Boolean = false) {
        runCatching { container.listSources() }
            .onSuccess { sources = it }
            .onFailure { if (reportErrors) actionMessage = "Ошибка чтения источников: ${it.message}" }
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
            refreshOperationalData()
            busy = false
        }
    }

    // Sources/diagnostics are refreshed when entered. Inbox/library are reactive Room flows and do
    // not use periodic polling; their ViewModels receive database invalidations automatically.
    LaunchedEffect(section) {
        if (section == AppSection.SOURCES || section == AppSection.DIAGNOSTICS) {
            refreshOperationalData(reportErrors = true)
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
                text = "Article Navigator",
                style = MaterialTheme.typography.headlineSmall,
            )
            SectionSelector(
                selected = section,
                inboxCount = inboxCount,
                libraryCount = libraryCount,
                onSelected = { sectionName = it.name },
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
                        runAction("Ставлю сбор пользовательских источников в очередь…") {
                            val id = container.enqueueImmediateCollection()
                            "Сбор запущен: $id"
                        }
                    },
                    onRefresh = { scope.launch { refreshOperationalData(reportErrors = true) } },
                )

                AppSection.INBOX -> InboxRoute(
                    modifier = Modifier.weight(1f),
                    repository = container.inboxPagingRepository,
                    actions = inboxActions,
                    onOpenSavedDocument = { documentId ->
                        selectedLibraryDocumentId = documentId.value
                        sectionName = AppSection.LIBRARY.name
                    },
                )

                AppSection.LIBRARY -> LibraryRoute(
                    modifier = Modifier.weight(1f),
                    repository = container.libraryRepository,
                    initialDocumentId = selectedLibraryDocumentId?.let(::DocumentId),
                    onSelectedDocumentChanged = { documentId ->
                        selectedLibraryDocumentId = documentId?.value
                    },
                    onOpenExternal = { url ->
                        val result = runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        if (result.isFailure) {
                            Toast.makeText(context, "Не удалось открыть внешнюю ссылку", Toast.LENGTH_SHORT).show()
                        }
                    },
                )

                AppSection.DIAGNOSTICS -> DiagnosticsScreen(
                    modifier = Modifier.weight(1f),
                    status = status,
                    message = actionMessage,
                    busy = busy,
                    onCollectNow = {
                        runAction("Ставлю сбор пользовательских источников в очередь…") {
                            val id = container.enqueueImmediateCollection()
                            "Сбор запущен: $id"
                        }
                    },
                    onRunSample = {
                        runAction("Подготавливаю контрольный RSS и запускаю полный тест…") {
                            val id = container.enqueueDeviceSampleCollection()
                            "Контрольный сбор запущен: $id"
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
    libraryCount: Int,
    onSelected: (AppSection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            selected = selected == AppSection.LIBRARY,
            text = "Библиотека ($libraryCount)",
            onClick = { onSelected(AppSection.LIBRARY) },
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
    onRunSample: () -> Unit,
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
            Text(if (busy) "Выполняется…" else "Запустить сбор пользовательских источников")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = onRunSample,
        ) {
            Text("Запустить контрольный RSS-тест")
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)
        status?.let { DeviceStatusCard(it) } ?: Text("Инициализация локальной базы…")
        Text(
            "Контрольный RSS добавляется только по явному нажатию кнопки выше. Периодический сбор зарегистрирован через WorkManager; Android выбирает точное время фонового запуска.",
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
