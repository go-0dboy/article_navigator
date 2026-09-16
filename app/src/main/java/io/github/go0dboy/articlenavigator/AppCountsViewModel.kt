package io.github.go0dboy.articlenavigator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppCountsState(
    val inboxCount: Int? = null,
    val libraryCount: Int? = null,
    val observationError: String? = null,
)

class AppCountsViewModel(
    private val inboxRepository: InboxPagingRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AppCountsState())
    val state: StateFlow<AppCountsState> = _state.asStateFlow()

    private var observationJob: Job? = null

    init {
        reconnect()
    }

    fun retry() = reconnect()

    private fun reconnect() {
        observationJob?.cancel()
        _state.update { it.copy(observationError = null) }
        observationJob = viewModelScope.launch {
            try {
                combine(
                    inboxRepository.observePendingCount(),
                    libraryRepository.observeSavedCount(),
                ) { inboxCount, libraryCount -> inboxCount to libraryCount }
                    .collect { (inboxCount, libraryCount) ->
                        _state.update {
                            it.copy(
                                inboxCount = inboxCount,
                                libraryCount = libraryCount,
                                observationError = null,
                            )
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        observationError = error.message?.takeIf(String::isNotBlank)
                            ?: "Не удалось наблюдать счётчики Inbox и библиотеки",
                    )
                }
            }
        }
    }
}
