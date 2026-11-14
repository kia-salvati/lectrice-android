package app.lectrice.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryNotice { BookGone }

/** What another screen tells the library as it goes back to it; shown once, as a snackbar. */
class LibraryNotices {
    private val pending = MutableStateFlow<LibraryNotice?>(null)
    val notice: StateFlow<LibraryNotice?> = pending.asStateFlow()

    fun post(notice: LibraryNotice) {
        pending.value = notice
    }

    fun consume(notice: LibraryNotice) {
        pending.compareAndSet(notice, null)
    }
}
