package id.yomic.mobile.viewmodel.controllers

import id.yomic.mobile.model.CategoryItem
import id.yomic.mobile.model.ChapterFilterMode
import id.yomic.mobile.model.LibraryFilterMode
import id.yomic.mobile.model.LibrarySortBy
import id.yomic.mobile.model.MangaItem
import id.yomic.mobile.model.SyncProgressState
import id.yomic.mobile.model.YomicUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns Library filters/sorting, Category CRUD, Library Sync, Updates clearing,
 * Manga Detail loading, and Chapter / Batch actions.
 */
internal class LibraryController(
    private val ctx: ControllerContext
) {
    private var detailLoadJob: Job? = null

    fun setLibrarySearch(query: String) {
        ctx.state.update { it.copy(librarySearchQuery = query) }
    }

    fun setLibraryCategoryFilter(categoryId: Int?) {
        ctx.state.update { it.copy(libraryCategoryFilterId = categoryId) }
    }

    fun setLibraryFilterMode(mode: LibraryFilterMode) {
        ctx.state.update { it.copy(libraryFilterMode = mode) }
    }

    fun setLibrarySourceFilter(sourceId: String?) {
        ctx.state.update { it.copy(librarySourceFilterId = sourceId) }
    }

    fun setLibrarySort(sort: LibrarySortBy) {
        ctx.state.update { it.copy(librarySortBy = sort) }
    }

    fun toggleLibraryViewMode() {
        ctx.state.update { it.copy(libraryIsGridMode = !it.libraryIsGridMode) }
    }

    fun resetLibraryFilters() {
        ctx.state.update {
            it.copy(
                librarySearchQuery = "",
                libraryCategoryFilterId = null,
                libraryFilterMode = LibraryFilterMode.ALL,
                librarySourceFilterId = null,
                librarySortBy = LibrarySortBy.LAST_READ
            )
        }
    }

    fun addCategory(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                val (added, label) = c.libraryRepository.addCategory(name)
                ctx.showMessage(
                    if (added) ctx.l("Kategori \"$label\" ditambahkan.", "Category \"$label\" added.")
                    else ctx.l("Kategori \"$label\" sudah ada.", "Category \"$label\" already exists.")
                )
            }
            return
        }
        val existing = ctx.state.value.categories
        if (existing.any { it.name.equals(name, ignoreCase = true) }) {
            ctx.showMessage(ctx.l("Kategori \"$name\" sudah ada.", "Category \"$name\" already exists."))
            return
        }
        val nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
        ctx.state.update {
            it.copy(
                categories = it.categories + CategoryItem(id = nextId, name = name, sortOrder = nextId),
                snackbarMessage = ctx.l("Kategori \"$name\" ditambahkan.", "Category \"$name\" added.")
            )
        }
    }

    fun deleteCategory(categoryId: Int) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                val removedName = c.libraryRepository.deleteCategory(categoryId) ?: return@launch
                ctx.state.update { state ->
                    state.copy(
                        libraryCategoryFilterId = if (state.libraryCategoryFilterId == categoryId) null else state.libraryCategoryFilterId,
                        snackbarMessage = ctx.l("Kategori \"$removedName\" dihapus.", "Category \"$removedName\" removed.")
                    )
                }
            }
            return
        }
        val target = ctx.state.value.categories.find { it.id == categoryId } ?: return
        ctx.state.update { state ->
            state.copy(
                categories = state.categories.filterNot { it.id == categoryId },
                libraryCategoryFilterId = if (state.libraryCategoryFilterId == categoryId) null else state.libraryCategoryFilterId,
                mangaList = state.mangaList.map { m ->
                    m.copy(categoryIds = m.categoryIds.filterNot { it == categoryId })
                },
                snackbarMessage = ctx.l("Kategori \"${target.name}\" dihapus.", "Category \"${target.name}\" removed.")
            )
        }
    }

    fun toggleMangaCategory(mangaId: String, categoryId: Int) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch { c.libraryRepository.toggleMangaCategory(mangaId, categoryId) }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else {
                        val next = if (categoryId in m.categoryIds) {
                            m.categoryIds - categoryId
                        } else {
                            m.categoryIds + categoryId
                        }
                        m.copy(categoryIds = next)
                    }
                }
            )
        }
    }

    fun triggerLibrarySync() {
        val c = ctx.container
        if (c != null) {
            if (ctx.state.value.syncProgress.active) return
            ctx.scope.launch {
                val summary = c.libraryUpdateService.syncLibrary()
                val msg = when {
                    summary.skippedOffline -> ctx.l("Mode Offline aktif. Nonaktifkan untuk sinkronisasi.", "Offline mode is active.")
                    summary.alreadyRunning -> return@launch
                    summary.totalNewChapters > 0 -> ctx.l(
                        "${summary.totalNewChapters} bab baru ditemukan pada ${summary.updatedMangaTitles.distinct().size} komik.",
                        "${summary.totalNewChapters} new chapter(s) found across ${summary.updatedMangaTitles.distinct().size} title(s)."
                    )
                    summary.checkedCount == 0 && summary.skippedCount > 0 -> ctx.l(
                        "Semua komik dilewati oleh Pembaruan Cerdas (${summary.skippedCount}).",
                        "All titles skipped by Smart Update (${summary.skippedCount})."
                    )
                    summary.checkedCount == 0 -> ctx.l(
                        "Belum ada komik di Pustaka untuk diperbarui.",
                        "No library titles to check for updates."
                    )
                    else -> ctx.l("Pustaka sudah paling baru.", "Library is up to date.")
                }
                ctx.showMessage(msg)
            }
            return
        }
        if (ctx.state.value.syncProgress.active) return
        val libraryCount = ctx.state.value.mangaList.count { it.inLibrary }.coerceAtLeast(1)
        ctx.scope.launch {
            ctx.state.update { it.copy(syncProgress = SyncProgressState(active = true, current = 0, total = libraryCount)) }
            for (step in 1..libraryCount) {
                delay(240L)
                ctx.state.update { it.copy(syncProgress = SyncProgressState(active = true, current = step, total = libraryCount)) }
            }
            delay(180L)
            ctx.state.update {
                it.copy(
                    syncProgress = SyncProgressState(active = false, current = 0, total = 0),
                    snackbarMessage = ctx.l("Pustaka selesai diperbarui.", "Library sync completed.")
                )
            }
        }
    }

    fun clearAllUpdates() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.libraryUpdateService.clearAllUpdates()
                ctx.showMessage(ctx.l("Daftar pembaruan dibersihkan.", "Updates list cleared."))
            }
            return
        }
        ctx.state.update {
            it.copy(
                activityUpdates = emptyList(),
                snackbarMessage = ctx.l("Daftar pembaruan dibersihkan.", "Updates list cleared.")
            )
        }
    }

    fun registerExternalMangaForDetail(manga: MangaItem) {
        ctx.state.update { state ->
            val exists = state.mangaList.any { it.id == manga.id }
            if (exists) state else state.copy(mangaList = state.mangaList + manga)
        }
        val c = ctx.container ?: return
        ctx.scope.launch {
            c.libraryRepository.ensureMangaCached(manga)
            loadMangaDetail(manga.id, forceRefresh = false)
        }
    }

    fun loadMangaDetail(mangaId: String, forceRefresh: Boolean = false) {
        val c = ctx.container ?: return
        if (ctx.state.value.settings.isOfflineMode && forceRefresh) {
            ctx.showMessage(ctx.l("Mode Offline aktif.", "Offline mode is active."))
            return
        }
        detailLoadJob?.cancel()
        detailLoadJob = ctx.scope.launch {
            ctx.state.update { it.copy(isLoadingDetail = true, detailError = null) }
            val res = c.libraryRepository.refreshMangaDetailAndChapters(mangaId, forceRefresh = forceRefresh)
            ctx.state.update {
                it.copy(
                    isLoadingDetail = false,
                    detailError = if (res.success) null else res.error,
                    snackbarMessage = when {
                        forceRefresh && res.success && res.newChaptersCount > 0 ->
                            ctx.l("${res.newChaptersCount} bab baru ditemukan.", "${res.newChaptersCount} new chapters found.")
                        forceRefresh && res.success ->
                            ctx.l("Metadata & bab diperbarui.", "Metadata & chapters refreshed.")
                        !res.success && !res.error.isNullOrBlank() -> res.error
                        else -> it.snackbarMessage
                    }
                )
            }
        }
    }

    fun toggleInLibrary(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                val (nowInLib, title) = c.libraryRepository.toggleInLibrary(mangaId)
                if (title.isNotBlank()) {
                    ctx.showMessage(
                        if (nowInLib) ctx.l("$title ditambahkan ke Pustaka.", "$title added to Library.")
                        else ctx.l("$title dihapus dari Pustaka.", "$title removed from Library.")
                    )
                }
            }
            return
        }
        val target = ctx.state.value.mangaList.find { it.id == mangaId } ?: return
        val nextInLib = !target.inLibrary
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        inLibrary = nextInLib,
                        categoryIds = if (nextInLib && m.categoryIds.isEmpty()) listOf(1) else m.categoryIds
                    )
                },
                snackbarMessage = if (nextInLib) {
                    ctx.l("${target.title} ditambahkan ke Pustaka.", "${target.title} added to Library.")
                } else {
                    ctx.l("${target.title} dihapus dari Pustaka.", "${target.title} removed from Library.")
                }
            )
        }
    }

    fun setDetailChapterFilter(filter: ChapterFilterMode) {
        ctx.state.update { it.copy(detailChapterFilter = filter) }
    }

    fun toggleDetailChapterOrder() {
        ctx.state.update { it.copy(detailChapterAsc = !it.detailChapterAsc) }
    }

    fun toggleChapterBookmark(mangaId: String, chapterNumber: Double) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch { c.libraryRepository.toggleChapterBookmark(mangaId, chapterNumber) }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        chapters = m.chapters.map { ch ->
                            if (abs(ch.number - chapterNumber) < 0.001) ch.copy(isBookmarked = !ch.isBookmarked) else ch
                        }
                    )
                }
            )
        }
    }

    fun toggleChapterRead(mangaId: String, chapterNumber: Double) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch { c.libraryRepository.toggleChapterRead(mangaId, chapterNumber) }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        chapters = m.chapters.map { ch ->
                            if (abs(ch.number - chapterNumber) < 0.001) ch.copy(isRead = !ch.isRead) else ch
                        }
                    )
                }
            )
        }
    }

    fun markPreviousChaptersRead(mangaId: String, chapterNumber: Double) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.libraryRepository.markPreviousChaptersRead(mangaId, chapterNumber)
                ctx.showMessage(ctx.l("Bab sebelumnya ditandai sudah dibaca.", "Previous chapters marked as read."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        chapters = m.chapters.map { ch ->
                            if (ch.number <= chapterNumber) ch.copy(isRead = true) else ch
                        }
                    )
                },
                snackbarMessage = ctx.l("Bab sebelumnya ditandai sudah dibaca.", "Previous chapters marked as read.")
            )
        }
    }

    fun markAllChaptersRead(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.libraryRepository.markAllChaptersRead(mangaId)
                ctx.showMessage(ctx.l("Semua bab ditandai sudah dibaca.", "All chapters marked as read."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        chapters = m.chapters.map { it.copy(isRead = true) }
                    )
                },
                snackbarMessage = ctx.l("Semua bab ditandai sudah dibaca.", "All chapters marked as read.")
            )
        }
    }

    fun markMultipleRead(mangaIds: Set<String>) {
        if (mangaIds.isEmpty()) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.libraryRepository.markMultipleRead(mangaIds)
                ctx.showMessage(ctx.l("${mangaIds.size} komik ditandai sudah dibaca.", "${mangaIds.size} titles marked as read."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id !in mangaIds) m else m.copy(
                        chapters = m.chapters.map { it.copy(isRead = true) }
                    )
                },
                snackbarMessage = ctx.l(
                    "${mangaIds.size} komik ditandai sudah dibaca.",
                    "${mangaIds.size} titles marked as read."
                )
            )
        }
    }

    fun removeMultipleFromLibrary(mangaIds: Set<String>) {
        if (mangaIds.isEmpty()) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.libraryRepository.removeMultipleFromLibrary(mangaIds)
                ctx.showMessage(ctx.l("${mangaIds.size} komik dihapus dari Pustaka.", "${mangaIds.size} titles removed from Library."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id !in mangaIds) m else m.copy(inLibrary = false)
                },
                snackbarMessage = ctx.l(
                    "${mangaIds.size} komik dihapus dari Pustaka.",
                    "${mangaIds.size} titles removed from Library."
                )
            )
        }
    }

    fun assignCategoryToMultiple(mangaIds: Set<String>, categoryId: Int) {
        if (mangaIds.isEmpty()) return
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                val catName = c.libraryRepository.assignCategoryToMultiple(mangaIds, categoryId)
                ctx.showMessage(ctx.l("${mangaIds.size} komik ditambahkan ke kategori $catName.", "${mangaIds.size} titles added to $catName."))
            }
            return
        }
        val catName = ctx.state.value.categories.find { it.id == categoryId }?.name.orEmpty()
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id !in mangaIds) m else {
                        val next = if (categoryId in m.categoryIds) m.categoryIds else m.categoryIds + categoryId
                        m.copy(categoryIds = next)
                    }
                },
                snackbarMessage = ctx.l(
                    "${mangaIds.size} komik ditambahkan ke kategori $catName.",
                    "${mangaIds.size} titles added to $catName."
                )
            )
        }
    }

    fun getFilteredLibraryManga(state: YomicUiState): List<MangaItem> {
        val q = state.librarySearchQuery.trim().lowercase()
        return state.mangaList
            .asSequence()
            .filter { it.inLibrary }
            .filter { !state.settings.isOfflineMode || it.downloadedCount > 0 }
            .filter { state.settings.showNsfw || !it.isExplicit }
            .filter { m ->
                q.isEmpty() ||
                    m.title.lowercase().contains(q) ||
                    m.author.lowercase().contains(q) ||
                    m.sourceName.lowercase().contains(q) ||
                    m.genres.any { it.lowercase().contains(q) }
            }
            .filter { m ->
                state.libraryCategoryFilterId == null || state.libraryCategoryFilterId in m.categoryIds
            }
            .filter { m ->
                when (state.libraryFilterMode) {
                    LibraryFilterMode.ALL -> true
                    LibraryFilterMode.UNREAD -> m.unreadCount > 0 && m.readCount == 0
                    LibraryFilterMode.READING -> m.unreadCount > 0 && m.readCount > 0
                    LibraryFilterMode.COMPLETED -> m.totalChapters > 0 && m.unreadCount == 0
                    LibraryFilterMode.DOWNLOADED -> m.downloadedCount > 0
                }
            }
            .filter { m ->
                state.librarySourceFilterId == null || m.sourceId == state.librarySourceFilterId
            }
            .sortedWith(
                when (state.librarySortBy) {
                    LibrarySortBy.LAST_READ -> compareByDescending { it.lastViewedEpoch }
                    LibrarySortBy.UNREAD -> compareByDescending { it.unreadCount }
                    LibrarySortBy.UPDATED -> compareByDescending { it.lastUpdateEpoch }
                    LibrarySortBy.TITLE_ASC -> compareBy { it.title.lowercase() }
                    LibrarySortBy.TITLE_DESC -> compareByDescending { it.title.lowercase() }
                }
            )
            .toList()
    }
}
