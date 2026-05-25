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

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import luci.sixsixsix.powerampache2.plugin.R

val mainFontSize = 16.sp
val smallFontSize = 12.sp
val screenPadding
    @Composable get() = dimensionResource(R.dimen.screen_padding)

val surfaceVariantLight = Color(0xFFDFE5E3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(viewModel: SongListViewModel = hiltViewModel()) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val queue = viewModel.queueStateFlow.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary),
            //.padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding())
            //.padding(bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
        topBar = {
            TopAppBar(
                modifier = Modifier.background(MaterialTheme.colorScheme.primary).statusBarsPadding(),
                title = {
                    Text(
                        text = stringResource(R.string.app_name_topBar),
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = screenPadding),//.basicMarquee(),
                        textAlign = TextAlign.Start
                    )
                },
                actions = {
                    }
            )
        },
        bottomBar = {
            BottomAppBar(
//                backgroundColor = MaterialTheme.colors.surface,
//                contentColor = MaterialTheme.colors.onSurface,
               // cutoutShape = null
            ) {
                uiState.value.currentSong?.let { currentSong ->
                    val trackTitle = currentSong.name.ifBlank { currentSong.title }
                    AsyncImage(
                        modifier = Modifier.aspectRatio(1f).fillMaxHeight(),
                        model = currentSong.imageUrl,
                        contentDescription = trackTitle
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = trackTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentSong.artist.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = {  }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.cd_previous))
                    }
                    IconButton(onClick = {  }) {
                        Icon(
                            if (uiState.value.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.value.isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play)
                        )
                    }
                    IconButton(onClick = {  }) {
                        Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.cd_next))
                    }
                }
            }
        }
    ) { padding ->

        if (queue.value.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.mainScreen_emptyQueue_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center)
                Text(stringResource(R.string.mainScreen_emptyQueue_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center)
            }
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(queue.value) { song ->
                val trackTitle = song.name.ifBlank { song.title }
                Row(
                    modifier = Modifier
                        .padding(vertical = 0.dp)
                        .fillMaxWidth()
                        .background(
                            if (uiState.value.currentIndex == queue.value.indexOf(song)) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else { Color.Transparent }
                        )
                        .clickable {  }
                ) {
                    AsyncImage(
                        song.imageUrl,
                        modifier = Modifier.fillMaxWidth(0.2f).aspectRatio(1f).padding(4.dp),
                        contentDescription = trackTitle
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            modifier = Modifier.basicMarquee(),
                            text = trackTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            modifier = Modifier.basicMarquee(),
                            text = song.artist.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}
