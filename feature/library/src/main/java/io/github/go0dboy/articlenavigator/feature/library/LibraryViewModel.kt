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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LibraryScreenState(
    val items: List<LibraryItem> = emptyList(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
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
    private var detailJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeSavedCount().collect { count ->
                _state.update { it.copy(totalCount = count) }
            }
        }
        viewModelScope.launch {
            repository.observeRevision().collect {
                refresh()
            }
        }
    }

    fun retry() {
        viewModelScope.launch { refresh() }
    }

    fun loadMore() {
        viewModelScope.launch {
            pagingMutex.withLock {
                if (_state.value.loading || _state.value.loadingMore || !_state.value.hasMore) return@withLock
                _state.update { it.copy(loadingMore = true, error = null) }
                try {
                    val raw = repository.loadPage(nextKey, PAGE_SIZE + 1)
                    val page = raw.take(PAGE_SIZE)
                    nextKey = page.lastOrNull()?.let { LibraryPageKey(it.savedAt, it.id) }
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
                    _state.update { it.copy(loadingMore = false, error = error.message ?: "Не удалось загрузить библиотеку") }
                }
            }
        }
    }

    fun openDocument(id: DocumentId) {
        detailJob?.cancel()
        _state.update { it.copy(selectedDocument = null, detailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
            repository.observeDocument(id).collect { document ->
                _state.update {
                    if (document == null) {
                        it.copy(selectedDocument = null, detailLoading = false, detailError = "Сохранённый материал не найден")
                    } else {
                        it.copy(selectedDocument = document, detailLoading = false, detailError = null)
                    }
                }
            }
        }
    }

    fun closeDocument() {
        detailJob?.cancel()
        detailJob = null
        _state.update { it.copy(selectedDocument = null, detailLoading = false, detailError = null) }
    }

    private suspend fun refresh() {
        pagingMutex.withLock {
            val showSpinner = _state.value.items.isEmpty()
            _state.update { it.copy(loading = showSpinner, error = null) }
            try {
                val raw = repository.loadPage(after = null, limit = PAGE_SIZE + 1)
                val page = raw.take(PAGE_SIZE)
                nextKey = page.lastOrNull()?.let { LibraryPageKey(it.savedAt, it.id) }
                _state.update {
                    it.copy(
                        items = page,
                        loading = false,
                        loadingMore = false,
                        hasMore = raw.size > PAGE_SIZE,
                        error = null,
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
                        error = error.message ?: "Не удалось загрузить библиотеку",
                    )
                }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
