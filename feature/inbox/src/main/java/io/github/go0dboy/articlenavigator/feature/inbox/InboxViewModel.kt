package io.github.go0dboy.articlenavigator.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface InboxActions {
    suspend fun save(id: InboxItemId): DocumentId
    suspend fun reject(id: InboxItemId): Boolean
    suspend fun readAndDiscard(id: InboxItemId): Boolean
}

data class InboxScreenState(
    val items: List<InboxItem> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val busyIds: Set<InboxItemId> = emptySet(),
    val error: String? = null,
    val message: String? = null,
    val savedDocumentToOpen: DocumentId? = null,
)

class InboxViewModel(
    private val repository: InboxPagingRepository,
    private val actions: InboxActions,
) : ViewModel() {
    private val _state = MutableStateFlow(InboxScreenState())
    val state: StateFlow<InboxScreenState> = _state.asStateFlow()

    private val pagingMutex = Mutex()
    private var nextKey: InboxPageKey? = null

    init {
        viewModelScope.launch {
            repository.observePendingCount().collect { count ->
                _state.update { it.copy(totalCount = count) }
            }
        }
        viewModelScope.launch {
            repository.observeRevision().collect { refresh() }
        }
    }

    fun retry() = viewModelScope.launch { refresh() }

    fun loadMore() = viewModelScope.launch {
        pagingMutex.withLock {
            if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return@withLock
            _state.update { it.copy(loadingMore = true, error = null) }
            try {
                val raw = repository.loadPage(nextKey, PAGE_SIZE + 1)
                val page = raw.take(PAGE_SIZE)
                nextKey = page.lastOrNull()?.let { InboxPageKey(it.createdAt, it.id) }
                _state.update { current ->
                    current.copy(
                        items = (current.items + page).distinctBy { it.id },
                        loadingMore = false,
                        hasMore = raw.size > PAGE_SIZE,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(loadingMore = false, error = error.message ?: "Не удалось загрузить Inbox") }
            }
        }
    }

    fun save(id: InboxItemId) = runAction(id) {
        val documentId = actions.save(id)
        _state.update {
            it.copy(
                message = "Материал сохранён в библиотеку",
                savedDocumentToOpen = documentId,
            )
        }
    }

    fun reject(id: InboxItemId) = runAction(id) {
        val applied = actions.reject(id)
        _state.update {
            it.copy(message = if (applied) "Материал исключён из будущих повторов" else "Материал уже обработан другим действием")
        }
    }

    fun readAndDiscard(id: InboxItemId) = runAction(id) {
        val applied = actions.readAndDiscard(id)
        _state.update {
            it.copy(message = if (applied) "Материал отмечен прочитанным без сохранения" else "Материал уже обработан другим действием")
        }
    }

    fun consumeSavedNavigation() {
        _state.update { it.copy(savedDocumentToOpen = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun runAction(id: InboxItemId, action: suspend () -> Unit) {
        if (id in _state.value.busyIds) return
        viewModelScope.launch {
            _state.update { it.copy(busyIds = it.busyIds + id, error = null) }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Операция не выполнена") }
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    private suspend fun refresh() {
        pagingMutex.withLock {
            val showSpinner = _state.value.items.isEmpty()
            _state.update { it.copy(loading = showSpinner, error = null) }
            try {
                val raw = repository.loadPage(null, PAGE_SIZE + 1)
                val page = raw.take(PAGE_SIZE)
                nextKey = page.lastOrNull()?.let { InboxPageKey(it.createdAt, it.id) }
                _state.update {
                    it.copy(
                        items = page,
                        loading = false,
                        loadingMore = false,
                        hasMore = raw.size > PAGE_SIZE,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                nextKey = null
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = error.message ?: "Не удалось загрузить Inbox",
                    )
                }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
