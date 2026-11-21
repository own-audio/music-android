package audio.own.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// What differs between the three apps. Tier 2 in tools/sibling-drift.sh: every other file in
// :core:designsystem is the same in music-android, book-android and podcast-android.

data class Radii(
    val cover: Dp,
    val miniPlayer: Dp,
    val card: Dp,
    val miniCover: Dp,
    val rowCover: Dp,
    val sheetTop: Dp,
)

object AppBrand {
    val accentLight = Color(0xFFD63A6A)
    val accentDark = Color(0xFFFF6482)
    val radii = Radii(cover = 8.dp, miniPlayer = 14.dp, card = 14.dp, miniCover = 6.dp, rowCover = 4.dp, sheetTop = 28.dp)
    const val COVER_CROSSFADE_MS = 300
    val wordmarkWords = listOf("audio", "music", "family", "playlist", "song", "beat", "dance", "party")

    /** Settings → Accent Color, in the iOS app's order. */
    val accentSwatches = listOf(
        AccentChoice.APP, AccentChoice.BLUE, AccentChoice.INDIGO, AccentChoice.PURPLE, AccentChoice.RED,
        AccentChoice.ORANGE, AccentChoice.YELLOW, AccentChoice.GREEN, AccentChoice.TEAL, AccentChoice.BROWN,
        AccentChoice.GRAPHITE,
    )
}
