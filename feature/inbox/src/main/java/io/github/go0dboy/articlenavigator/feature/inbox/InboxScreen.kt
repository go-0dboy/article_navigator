package io.github.go0dboy.articlenavigator.feature.inbox

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
import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InboxRoute(
    repository: InboxPagingRepository,
    actions: InboxActions,
    onOpenSavedDocument: (DocumentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(repository, actions) {
        viewModelFactory {
            initializer { InboxViewModel(repository, actions) }
        }
    }
    val model: InboxViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedDocumentToOpen) {
        state.savedDocumentToOpen?.let { id ->
            onOpenSavedDocument(id)
            model.consumeSavedNavigation()
        }
    }

    InboxScreen(
        state = state,
        onRetry = model::retry,
        onLoadMore = model::loadMore,
        onSave = model::save,
        onReject = model::reject,
        onReadAndDiscard = model::readAndDiscard,
        modifier = modifier,
    )
}

@Composable
fun InboxScreen(
    state: InboxScreenState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSave: (InboxItemId) -> Unit,
    onReject: (InboxItemId) -> Unit,
    onReadAndDiscard: (InboxItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Загружаю Inbox…")
        }
        state.error != null && state.items.isEmpty() -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(state.error, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Повторить") }
        }
        state.items.isEmpty() -> Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Inbox пуст", style = MaterialTheme.typography.headlineSmall)
            Text("Всего материалов: ${state.totalCount}")
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Inbox · ${state.totalCount}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(state.items, key = { it.id.value }) { item ->
                InboxCard(
                    item = item,
                    busy = item.id in state.busyIds,
                    onSave = onSave,
                    onReject = onReject,
                    onReadAndDiscard = onReadAndDiscard,
                )
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
}

@Composable
private fun InboxCard(
    item: InboxItem,
    busy: Boolean,
    onSave: (InboxItemId) -> Unit,
    onReject: (InboxItemId) -> Unit,
    onReadAndDiscard: (InboxItemId) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        item.publishedAt?.let { Text("Опубликовано ${timestampFormatter.format(it)}", style = MaterialTheme.typography.bodySmall) }
        Text(
            item.normalizedText.take(260) + if (item.normalizedText.length > 260) "…" else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(item.id) }, enabled = !busy) { Text("Сохранить") }
            OutlinedButton(onClick = { onReject(item.id) }, enabled = !busy) { Text("Неинтересно") }
            OutlinedButton(onClick = { onReadAndDiscard(item.id) }, enabled = !busy) { Text("Прочитано") }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

private val timestampFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault())
