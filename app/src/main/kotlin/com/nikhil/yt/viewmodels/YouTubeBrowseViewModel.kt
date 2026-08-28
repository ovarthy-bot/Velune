/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.AlbumItem
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.YTItem
import com.nikhil.yt.innertube.pages.BrowseResult
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.constants.HideVideoKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class YouTubeBrowseViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId")!!
    private val params = savedStateHandle.get<String>("params")

    val result = MutableStateFlow<BrowseResult?>(null)

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
            YouTube
                .browse(browseId, params)
                .onSuccess { browseResult ->
                    val blockedIds = withContext(Dispatchers.IO) { database.allBlockedArtistIdsSync().toSet() }
                    val hideVideo = context.dataStore.get(HideVideoKey, false)
                    val filtered = browseResult
                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        .filterVideo(hideVideo)
                    val finalItems = filtered.items.mapNotNull { itemSection ->
                        val allowed = itemSection.items.filter { isYtItemAllowed(it, blockedIds) }
                        if (allowed.isEmpty()) null
                        else itemSection.copy(items = allowed)
                    }
                    result.value = filtered.copy(items = finalItems)
                }.onFailure {
                    reportException(it)
                }
        }
    }
}
