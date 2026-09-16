package io.github.go0dboy.articlenavigator.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LibraryRoute(
    repository: LibraryRepository,
    initialDocumentId: DocumentId? = null,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(repository) {
        viewModelFactory {
            initializer { LibraryViewModel(repository) }
        }
    }
    val model: LibraryViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(initialDocumentId) {
        if (initialDocumentId != null) model.openDocument(initialDocumentId)
    }

    LibraryScreen(
        state = state,
        onRetry = model::retry,
        onLoadMore = model::loadMore,
        onOpenDocument = model::openDocument,
        onCloseDocument = model::closeDocument,
        onOpenExternal = onOpenExternal,
        modifier = modifier,
    )
}

@Composable
fun LibraryScreen(
    state: LibraryScreenState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDocument: (DocumentId) -> Unit,
    onCloseDocument: () -> Unit,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.detailLoading -> LoadingState("Открываю сохранённый материал…", modifier)
        state.selectedDocument != null -> DocumentDetail(
            saved = state.selectedDocument,
            onBack = onCloseDocument,
            onOpenExternal = onOpenExternal,
            modifier = modifier,
        )
        state.detailError != null -> ErrorState(state.detailError, onCloseDocument, modifier)
        state.loading -> LoadingState("Загружаю библиотеку…", modifier)
        state.error != null && state.items.isEmpty() -> ErrorState(state.error, onRetry, modifier)
        state.items.isEmpty() -> EmptyLibrary(modifier)
        else -> LibraryList(
            state = state,
            onLoadMore = onLoadMore,
            onOpenDocument = onOpenDocument,
            modifier = modifier,
        )
    }
}

@Composable
private fun LibraryList(
    state: LibraryScreenState,
    onLoadMore: () -> Unit,
    onOpenDocument: (DocumentId) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Библиотека · ${state.totalCount}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        items(state.items, key = { it.id.value }) { item ->
            LibraryCard(item, onOpenDocument)
        }
        if (state.error != null) {
            item { Text(state.error, color = MaterialTheme.colorScheme.error) }
        }
        if (state.hasMore || state.loadingMore) {
            item {
                Button(onClick = onLoadMore, enabled = !state.loadingMore) {
                    Text(if (state.loadingMore) "Загрузка…" else "Загрузить ещё")
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun LibraryCard(item: LibraryItem, onOpenDocument: (DocumentId) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        Text("Сохранено ${formatInstant(item.savedAt)}", style = MaterialTheme.typography.bodySmall)
        Text(item.snippet, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
        val sourceText = when {
            item.sourceCount <= 0 -> "Источник не указан"
            item.sourceCount == 1 -> item.primarySourceName ?: "1 источник"
            else -> "${item.primarySourceName ?: "Источники"} · всего ${item.sourceCount}"
        }
        Text(sourceText, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onOpenDocument(item.id) }, modifier = Modifier.padding(top = 6.dp)) {
            Text("Читать офлайн")
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun DocumentDetail(
    saved: SavedDocument,
    onBack: () -> Unit,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier,
) {
    val document = saved.document
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("← В библиотеку") }
            Text(document.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp))
            document.publishedAt?.let { Text("Опубликовано ${formatInstant(it)}") }
            Text("Сохранено ${formatInstant(document.createdAt)}")
        }
        item {
            Text(document.normalizedText, style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Text("Происхождение", style = MaterialTheme.typography.titleMedium)
        }
        items(saved.provenance, key = { it.originKey }) { origin ->
            ProvenanceCard(origin, onOpenExternal)
        }
        item {
            Text(
                "Офлайн хранится извлечённый нормализованный текст. Это не полная копия исходной страницы: изображения, вложения и оформление могут отсутствовать.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ProvenanceCard(origin: DocumentProvenance, onOpenExternal: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(origin.sourceNameSnapshot, style = MaterialTheme.typography.titleSmall)
        Text("Источник: ${origin.sourceUrlSnapshot}", style = MaterialTheme.typography.bodySmall)
        Text("Тип: ${origin.sourceTypeSnapshot}", style = MaterialTheme.typography.bodySmall)
        Text("Обнаружено: ${formatInstant(origin.discoveredAt)}", style = MaterialTheme.typography.bodySmall)
        Text("Загружено: ${formatInstant(origin.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
        Text("Исходная ссылка: ${origin.discoveredUrl}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { onOpenExternal(origin.discoveredUrl) }) { Text("Открыть исходную") }
            val resolved = origin.resolvedUrl
            if (resolved != null && resolved != origin.discoveredUrl) {
                OutlinedButton(onClick = { onOpenExternal(resolved) }) { Text("После перенаправления") }
            }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun LoadingState(text: String, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Библиотека пока пуста", style = MaterialTheme.typography.headlineSmall)
        Text("Сохраните материал из Inbox — он появится здесь автоматически.")
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

private val timestampFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatInstant(value: Instant): String = timestampFormatter.format(value)
