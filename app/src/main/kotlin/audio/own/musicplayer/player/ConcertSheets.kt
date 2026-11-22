package audio.own.musicplayer.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.music.library.ChapterTime
import audio.own.musicplayer.R
import audio.own.player.music.TrackChapter
import audio.own.core.strings.R as SR

// The songs of the concert playing, and their owner's editor (music-ios Player/ConcertSheets.swift).

/** A sheet's top row: something on the left, the title, something on the right. */
@Composable
fun SheetHeader(title: String, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { leading() }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/** Songs: numbered, the one playing marked, its start time; tap to hear it. Edit for the owner. */
@Composable
fun ConcertSongsContent(
    chapters: List<TrackChapter>,
    currentIndex: Int?,
    canEdit: Boolean,
    onJump: (Int) -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            stringResource(R.string.concert_songs),
            leading = { if (canEdit) TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) } },
            trailing = { TextButton(onClick = onDone) { Text(stringResource(SR.string.done)) } },
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(chapters) { index, chapter ->
                val isCurrent = index == currentIndex
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { onJump(index) }
                        .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                ) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (isCurrent) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(SR.string.now_playing), tint = palette.accent, modifier = Modifier.size(18.dp))
                        } else {
                            Text("${index + 1}", style = MaterialTheme.typography.bodyMedium.tabular(), color = palette.muted)
                        }
                    }
                    Text(
                        chapter.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(ChapterTime.format((chapter.startSecs * 1000).toLong()), style = MaterialTheme.typography.bodyMedium.tabular(), color = palette.muted)
                }
            }
        }
    }
}

/**
 * Edit Songs: each song's start (red while it isn't a time) and name, Start Here to set it to
 * where the player is, swipe to delete, Add a Song Here, and Save. The server keeps them from
 * then on, whatever the file says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditorContent(
    state: ChapterEditorState,
    onTitle: (Int, String) -> Unit,
    onTime: (Int, String) -> Unit,
    onStartHere: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            stringResource(R.string.edit_songs),
            leading = { TextButton(onClick = onCancel) { Text(stringResource(SR.string.cancel)) } },
            trailing = {
                if (state.saving) {
                    Box(Modifier.size(Tokens.minTarget), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    TextButton(onClick = onSave) { Text(stringResource(SR.string.save)) }
                }
            },
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(state.rows, key = { _, row -> row.key }) { _, row ->
                val swipe = rememberSwipeToDismissBoxState()
                LaunchedEffect(swipe.currentValue) {
                    if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete(row.key)
                }
                SwipeToDismissBox(
                    state = swipe,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.lg), contentAlignment = Alignment.CenterEnd) {
                            Text(stringResource(SR.string.remove), color = Color.White)
                        }
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = Tokens.Spacing.lg, top = Tokens.Spacing.xs, bottom = Tokens.Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                    ) {
                        OutlinedTextField(
                            value = row.time,
                            onValueChange = { onTime(row.key, it) },
                            label = { Text(stringResource(R.string.chapter_time)) },
                            singleLine = true,
                            isError = !row.isTime,
                            textStyle = MaterialTheme.typography.bodyLarge.tabular(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(112.dp),
                        )
                        OutlinedTextField(
                            value = row.title,
                            onValueChange = { onTitle(row.key, it) },
                            label = { Text(stringResource(R.string.chapter_song)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onStartHere(row.key) }) {
                            Icon(Icons.Filled.NearMe, contentDescription = stringResource(R.string.start_here), tint = palette.accent)
                        }
                    }
                }
                HorizontalDivider(color = palette.border)
            }
            item {
                Text(
                    stringResource(R.string.start_here_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                )
            }
            item {
                TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = Tokens.Spacing.sm).heightIn(min = Tokens.minTarget)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.add_a_song_here), modifier = Modifier.padding(start = Tokens.Spacing.sm))
                }
            }
        }
    }
}
