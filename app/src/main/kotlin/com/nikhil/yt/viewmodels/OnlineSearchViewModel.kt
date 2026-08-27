/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.filterExplicit
import com.nikhil.yt.innertube.models.filterVideo
import com.nikhil.yt.innertube.pages.SearchSummaryPage
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.constants.HideVideoKey
import com.nikhil.yt.models.ItemsPage
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.pages.SearchSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = savedStateHandle.get<String>("query")!!
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    private fun isYtItemAllowed(item: YTItem, blockedIds: Set<String>): Boolean {
        if (blockedIds.isEmpty()) return true
        return when (item) {
            is SongItem -> item.artists.none { it.id != null && it.id in blockedIds }
            is ArtistItem -> item.id !in blockedIds
            is AlbumItem -> item.artists.orEmpty().none { it.id != null && it.id in blockedIds }
            else -> true
        }
    }

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                val blockedIds = withContext(Dispatchers.IO) { database.allBlockedArtistIdsSync().toSet() }
                if (filter == null) {
                    if (summaryPage == null) {
                        YouTube
                            .searchSummary(query)
                            .onSuccess { page ->
                                val filtered = page
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideo(context.dataStore.get(HideVideoKey, false))
                                summaryPage = SearchSummaryPage(
                                    summaries = filtered.summaries.mapNotNull { summary ->
                                        val allowedItems = summary.items.filter { isYtItemAllowed(it, blockedIds) }
                                        if (allowedItems.isEmpty()) null
                                        else SearchSummary(summary.title, allowedItems)
                                    }
                                )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        result.items
                                            .distinctBy { it.id }
                                            .filterExplicit(
                                                context.dataStore.get(
                                                    HideExplicitKey,
                                                    false
                                                )
                                            )
                                            .filterVideo(context.dataStore.get(HideVideoKey, false))
                                            .filter { isYtItemAllowed(it, blockedIds) },
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            if (filter == null) return@launch
            val viewState = viewStateMap[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val blockedIds = withContext(Dispatchers.IO) { database.allBlockedArtistIdsSync().toSet() }
                val searchResult =
                    YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                val newItems = searchResult.items.filter { isYtItemAllowed(it, blockedIds) }
                viewStateMap[filter] = ItemsPage(
                    (viewState.items + newItems).distinctBy { it.id },
                    searchResult.continuation
                )
            }
        }
    }
}
