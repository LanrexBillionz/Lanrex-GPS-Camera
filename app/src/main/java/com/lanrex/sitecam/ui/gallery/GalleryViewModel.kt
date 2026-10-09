package com.lanrex.sitecam.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrex.sitecam.AppContainer
import com.lanrex.sitecam.data.StampRepository
import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.media.GalleryFilter
import com.lanrex.sitecam.media.MediaEntry
import com.lanrex.sitecam.ui.permissions.AppPermissions
import com.lanrex.sitecam.ui.permissions.PermissionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryViewModel(private val container: AppContainer) : ViewModel() {

    private val _filter = MutableStateFlow(GalleryFilter.CAMERA)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    private val _items = MutableStateFlow<List<MediaEntry>>(emptyList())
    val items: StateFlow<List<MediaEntry>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _permissions = MutableStateFlow(AppPermissions.snapshot(container.app))
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private var limit = PAGE

    val stampedIds: StateFlow<Set<Long>> = container.stampRepository.stampedMediaIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setFilter(filter: GalleryFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        limit = PAGE
        reload()
    }

    fun loadMore() {
        limit += PAGE
        reload()
    }

    fun reload() {
        _permissions.value = AppPermissions.snapshot(container.app)
        viewModelScope.launch {
            _loading.value = true
            val album = container.settingsRepository.stampSettingsNow().albumName
            _items.value = withContext(Dispatchers.IO) {
                container.mediaStoreRepository.gallery(_filter.value, limit, excludeAlbum = album)
            }
            _loading.value = false
        }
    }

    val canLoadMore: Boolean get() = _items.value.size >= limit

    fun toggle(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Adds the selected items to the stamping queue. */
    fun stampSelected(onDone: (String) -> Unit) {
        val chosen = _items.value.filter { it.id in _selected.value }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var already = 0
            var skipped = 0
            for (entry in chosen) {
                when (container.stampRepository.add(entry, ItemOrigin.GALLERY, startWork = false)) {
                    StampRepository.AddResult.ADDED, StampRepository.AddResult.RETRYING -> added++
                    StampRepository.AddResult.ALREADY_STAMPED, StampRepository.AddResult.ALREADY_QUEUED -> already++
                    StampRepository.AddResult.UNSUPPORTED -> skipped++
                }
            }
            if (added > 0) container.workScheduler.startStamping()
            _selected.value = emptySet()
            onDone(
                buildList {
                    if (added > 0) add("Stamping $added item${if (added == 1) "" else "s"}")
                    if (already > 0) add("$already already stamped or queued")
                    if (skipped > 0) add("$skipped RAW skipped")
                }.joinToString(" · ").ifEmpty { "Nothing new to stamp" },
            )
        }
    }

    companion object {
        private const val PAGE = 600
    }
}
