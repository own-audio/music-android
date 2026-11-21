package audio.own.musicplayer.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.Tokens
import audio.own.music.library.model.Album
import audio.own.music.library.model.Artist
import audio.own.musicplayer.R
import audio.own.musicplayer.app.text
import audio.own.musicplayer.library.AlbumRow
import audio.own.musicplayer.library.ArtistRow
import audio.own.musicplayer.library.Heading
import audio.own.musicplayer.library.ListPage
import audio.own.musicplayer.library.LocalLibraryUi
import audio.own.musicplayer.library.SearchState
import audio.own.musicplayer.library.SongRow
import audio.own.musicplayer.ui.ErrorLine

/**
 * Artists, albums and songs matching what is typed. The field takes the keyboard when the tab
 * opens; a tapped song plays on its own, since a search result isn't an album to continue into.
 */
@Composable
fun SearchContent(
    state: SearchState,
    serverName: String,
    isOffline: Boolean,
    onQuery: (String) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    focusOnOpen: Boolean = true,
) {
    val ui = LocalLibraryUi.current
    val focus = remember { FocusRequester() }
    if (focusOnOpen) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ListPage(stringResource(R.string.tab_search), onBack = null) { inner ->
        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = { Text(stringResource(R.string.search_prompt)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(audio.own.core.strings.R.string.cancel)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm).focusRequester(focus),
                )
            }
            val result = state.result
            when {
                state.error != null -> item { ErrorLine(state.error.text(), Modifier.padding(Tokens.Spacing.screenEdge)) }
                result != null -> {
                    if (result.isEmpty) {
                        item { EmptyState(Icons.Filled.Search, stringResource(R.string.no_results), stringResource(R.string.no_results_for, state.query.trim()), Modifier.padding(top = Tokens.Spacing.xl)) }
                    }
                    if (result.artists.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.artists)) }
                        items(result.artists, key = { "a" + it.id }) { artist -> ArtistRow(artist, onClick = { onArtist(artist) }, size = 40.dp) }
                    }
                    if (result.albums.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.albums)) }
                        items(result.albums, key = { "l" + it.id }) { album -> AlbumRow(album, onClick = { onAlbum(album) }, coverSize = 44.dp) }
                    }
                    if (result.songs.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.songs)) }
                        items(result.songs, key = { "s" + it.id }) { song -> SongRow(song, onClick = { ui.play(listOf(song), null, false, null) }, showsCover = true) }
                    }
                }
                state.searching -> item { Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else -> item {
                    EmptyState(
                        Icons.Filled.Search,
                        stringResource(R.string.search_your_library),
                        if (isOffline) stringResource(R.string.search_offline_text) else stringResource(R.string.search_server_text, serverName),
                        Modifier.padding(top = Tokens.Spacing.xl),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Heading(text, Modifier.padding(start = Tokens.Spacing.screenEdge, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs).semantics { heading() })
}
