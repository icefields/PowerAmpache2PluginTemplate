/**
 * Copyright (C) 2025  Antonio Tari
 *
 * This file is a part of Power Ampache 2
 * Ampache Android client application
 * @author Antonio Tari
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package luci.sixsixsix.powerampache2.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import luci.sixsixsix.powerampache2.plugin.domain.model.Song
import luci.sixsixsix.powerampache2.plugin.domain.usecase.QueueStateFlow
import javax.inject.Inject

@HiltViewModel
class SongListViewModel @Inject constructor(
    queueStateFlowUseCase: QueueStateFlow,
) : ViewModel() {

    val queueStateFlow: StateFlow<List<Song>> = queueStateFlowUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Now playing + selection derived from host queue (first item = current track if host orders that way). */
    val uiState: StateFlow<UiState> = queueStateFlow
        .map { queue ->
            UiState(
                isConnected = queue.isNotEmpty(),
                isPlaying = false,
                currentIndex = 0,
                currentSong = queue.firstOrNull(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Placeholder until the host UI wires album drill-down. */
    val singleAlbumStateFlow: StateFlow<List<Song>> =
        flowOf<List<Song>>(emptyList())
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
