/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.pages.ChartsPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val database: MusicDatabase,
) : ViewModel() {
    private val _chartsPage = MutableStateFlow<ChartsPage?>(null)
    val chartsPage = _chartsPage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private fun isYtItemAllowed(item: YTItem, blockedIds: Set<String>): Boolean {
        if (blockedIds.isEmpty()) return true
        return when (item) {
            is SongItem -> item.artists.none { it.id != null && it.id in blockedIds }
            is ArtistItem -> item.id !in blockedIds
            is AlbumItem -> item.artists.orEmpty().none { it.id != null && it.id in blockedIds }
            else -> true
        }
    }

    fun loadCharts() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            YouTube.getChartsPage()
                .onSuccess { page ->
                    val blockedIds = withContext(Dispatchers.IO) { database.allBlockedArtistIdsSync().toSet() }
                    val filteredSections = page.sections.map { section ->
                        section.copy(items = section.items.filter { isYtItemAllowed(it, blockedIds) })
                    }
                    _chartsPage.value = page.copy(sections = filteredSections)
                }
                .onFailure { e ->
                    _error.value = "Failed to load charts: ${e.message}"
                }
            
            _isLoading.value = false
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            _chartsPage.value?.continuation?.let { continuation ->
                _isLoading.value = true
                YouTube.getChartsPage(continuation)
                    .onSuccess { newPage ->
                        val blockedIds = withContext(Dispatchers.IO) { database.allBlockedArtistIdsSync().toSet() }
                        val filteredNewSections = newPage.sections.map { section ->
                            section.copy(items = section.items.filter { isYtItemAllowed(it, blockedIds) })
                        }
                        _chartsPage.value = _chartsPage.value?.copy(
                            sections = _chartsPage.value?.sections.orEmpty() + filteredNewSections,
                            continuation = newPage.continuation
                        )
                    }
                    .onFailure { e ->
                        _error.value = "Failed to load more: ${e.message}"
                    }
                _isLoading.value = false
            }
        }
    }
}
