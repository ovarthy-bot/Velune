/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.Album
import com.nikhil.yt.db.entities.Artist
import com.nikhil.yt.db.entities.LocalItem
import com.nikhil.yt.db.entities.Playlist
import com.nikhil.yt.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel
@Inject
constructor(
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LocalFilter.ALL)
    private val blockedArtistIds = database.allBlockedArtistIds().map { it.toSet() }

    private fun isLocalItemAllowed(item: LocalItem, blockedIds: Set<String>): Boolean {
        if (blockedIds.isEmpty()) return true
        return when (item) {
            is Song -> item.artists.none { it.id in blockedIds }
            is Artist -> item.id !in blockedIds
            is Album -> item.artists.none { it.id in blockedIds }
            is Playlist -> true
        }
    }

    val result =
        combine(query, filter, blockedArtistIds) { query, filter, blockedIds ->
            Triple(query, filter, blockedIds)
        }.flatMapLatest { (query, filter, blockedIds) ->
            if (query.isEmpty()) {
                flowOf(LocalSearchResult("", filter, emptyMap()))
            } else {
                val baseFlow = when (filter) {
                    LocalFilter.ALL ->
                        combine(
                            database.searchSongs(query, PREVIEW_SIZE * 2),
                            database.searchAlbums(query, PREVIEW_SIZE * 2),
                            database.searchArtists(query, PREVIEW_SIZE * 2),
                            database.searchPlaylists(query, PREVIEW_SIZE * 2),
                        ) { songs, albums, artists, playlists ->
                            songs + albums + artists + playlists
                        }

                    LocalFilter.SONG -> database.searchSongs(query)
                    LocalFilter.ALBUM -> database.searchAlbums(query)
                    LocalFilter.ARTIST -> database.searchArtists(query)
                    LocalFilter.PLAYLIST -> database.searchPlaylists(query)
                }
                baseFlow.map { list ->
                    val filteredList = list.filter { isLocalItemAllowed(it, blockedIds) }
                    val finalList = if (filter == LocalFilter.ALL) {
                        val grouped = filteredList.groupBy {
                            when (it) {
                                is Song -> LocalFilter.SONG
                                is Album -> LocalFilter.ALBUM
                                is Artist -> LocalFilter.ARTIST
                                is Playlist -> LocalFilter.PLAYLIST
                            }
                        }
                        grouped.values.flatMap { it.take(PREVIEW_SIZE) }
                    } else {
                        filteredList
                    }
                    LocalSearchResult(
                        query = query,
                        filter = filter,
                        map =
                        finalList.groupBy {
                            when (it) {
                                is Song -> LocalFilter.SONG
                                is Album -> LocalFilter.ALBUM
                                is Artist -> LocalFilter.ARTIST
                                is Playlist -> LocalFilter.PLAYLIST
                            }
                        },
                    )
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            LocalSearchResult("", filter.value, emptyMap())
        )

    companion object {
        const val PREVIEW_SIZE = 3
    }
}

enum class LocalFilter {
    ALL,
    SONG,
    ALBUM,
    ARTIST,
    PLAYLIST,
}

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
)
