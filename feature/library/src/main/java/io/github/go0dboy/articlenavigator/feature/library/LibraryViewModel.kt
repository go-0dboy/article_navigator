package io.github.go0dboy.articlenavigator.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LibraryScreenState(
    val items: List<LibraryItem> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val subscriptionError: String? = null,
    val selectedDocument: SavedDocument? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
)

class LibraryViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryScreenState())
    val state: StateFlow<LibraryScreenState> = _state.asStateFlow()

    private val pagingMutex = Mutex()
    private var nextKey: LibraryPageKey? = null
    private var observationJob: Job? = null
    private var detailJob: Job? = null
    private var selectedDocumentId: DocumentId? = null

    init {
        reconnectObservations()
    }

    fun retry() {
        reconnectObservations()
        selectedDocumentId?.let { id ->
            if (_state.value.detailError != null) startDetailObservation(id, clearCurrent = false)
        }
    }

    fun retryDetail() {
        selectedDocumentId?.let { startDetailObservation(it, clearCurrent = false) }
    }

    fun loadMore() {
        viewModelScope.launch {
            pagingMutex.withLock {
                if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return@withLock
                val key = nextKey ?: return@withLock
                _state.update { it.copy(loadingMore = true, error = null) }
                try {
                    val raw = repository.loadPage(key, PAGE_SIZE + 1)
                    val page = raw.take(PAGE_SIZE)
                    val newItems = (_state.value.items + page).distinctBy { it.id }
                    val newKey = newItems.lastOrNull()?.let { LibraryPageKey(it.savedAt, it.id) }
                    nextKey = newKey
                    _state.update { current ->
                        current.copy(
                            items = newItems,
                            loadingMore = false,
                            hasMore = raw.size > PAGE_SIZE,
                            error = null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _state.update {
                        it.copy(
                            loadingMore = false,
                            error = error.message ?: "Не удалось загрузить следующую страницу библиотеки",
                        )
                    }
                }
            }
        }
    }

    fun openDocument(id: DocumentId) {
        selectedDocumentId = id
        startDetailObservation(id, clearCurrent = _state.value.selectedDocument?.document?.id != id)
    }

    fun closeDocument() {
        detailJob?.cancel()
        detailJob = null
        selectedDocumentId = null
        _state.update { it.copy(selectedDocument = null, detailLoading = false, detailError = null) }
    }

    private fun reconnectObservations() {
        observationJob?.cancel()
        _state.update { it.copy(subscriptionError = null) }
        observationJob = viewModelScope.launch {
            supervisorScope {
                launch {
                    try {
                        repository.observeSavedCount().collect { count ->
                            _state.update { it.copy(totalCount = count) }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        reportSubscriptionError(error, "Не удалось наблюдать количество сохранённых материалов")
                    }
                }
                launch {
                    try {
                        repository.observeRevision().collect {
                            refreshLoadedRange()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _state.update { it.copy(loading = false, loadingMore = false) }
                        reportSubscriptionError(error, "Автоматическое обновление библиотеки остановлено")
                    }
                }
            }
        }
    }

    private fun reportSubscriptionError(error: Exception, fallback: String) {
        _state.update {
            it.copy(subscriptionError = error.message?.takeIf(String::isNotBlank) ?: fallback)
        }
    }

    private fun startDetailObservation(id: DocumentId, clearCurrent: Boolean) {
        detailJob?.cancel()
        _state.update {
            it.copy(
                selectedDocument = if (clearCurrent) null else it.selectedDocument,
                detailLoading = clearCurrent || it.selectedDocument == null,
                detailError = null,
            )
        }
        detailJob = viewModelScope.launch {
            try {
                repository.observeDocument(id).collect { document ->
                    _state.update {
                        if (document == null) {
                            it.copy(
                                selectedDocument = null,
                                detailLoading = false,
                                detailError = "Сохранённый материал не найден",
                            )
                        } else {
                            it.copy(selectedDocument = document, detailLoading = false, detailError = null)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        detailLoading = false,
                        detailError = error.message?.takeIf(String::isNotBlank)
                            ?: "Не удалось продолжить наблюдение за сохранённым материалом",
                    )
                }
            }
        }
    }

    private suspend fun refreshLoadedRange() {
        pagingMutex.withLock {
            val snapshot = _state.value
            val requestedSize = maxOf(PAGE_SIZE, snapshot.items.size)
            val showSpinner = snapshot.items.isEmpty()
            _state.update { it.copy(loading = showSpinner, error = null) }
            try {
                val raw = repository.loadPage(after = null, limit = requestedSize + 1)
                val refreshed = raw.take(requestedSize)
                val refreshedKey = refreshed.lastOrNull()?.let { LibraryPageKey(it.savedAt, it.id) }
                nextKey = refreshedKey
                _state.update {
                    it.copy(
                        items = refreshed,
                        loading = false,
                        loadingMore = false,
                        hasMore = raw.size > requestedSize,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Keep the last coherent page window and cursor. A failed refresh must not turn the
                // next "load more" into a first-page request or discard already visible material.
                _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = error.message ?: "Не удалось обновить библиотеку",
                    )
                }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
