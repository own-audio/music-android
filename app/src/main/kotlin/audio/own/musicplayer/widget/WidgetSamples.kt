package audio.own.musicplayer.widget

import androidx.glance.ImageProvider
import audio.own.musicplayer.R

/** Made-up albums for the widget picker's previews (Android 15 and later draws them from here). */
object WidgetSamples {
    private val albums = listOf(
        "Blue Hour" to "Mira Lane",
        "Night Drive" to "The Lanterns",
        "Slow Mornings" to "Ada Kovač",
        "Paper Boats" to "Northern Lights Trio",
        "Saturday" to "Family Mix",
        "Coastline" to "Juno Park",
        "Late Trains" to "The Quiet Set",
        "Glow" to "Lumen",
    )

    private val pictures = listOf(
        R.drawable.widget_sample_cover_1, R.drawable.widget_sample_cover_2, R.drawable.widget_sample_cover_3, R.drawable.widget_sample_cover_4,
        R.drawable.widget_sample_cover_5, R.drawable.widget_sample_cover_6, R.drawable.widget_sample_cover_7, R.drawable.widget_sample_cover_8,
    )

    fun state(): WidgetState {
        val shelf = albums.mapIndexed { index, (title, artist) ->
            WidgetSnapshot.Entry("sample|$index", WidgetSnapshot.Kind.ALBUM, title, artist, cover = "sample$index")
        }
        return WidgetState(
            player = WidgetPlayer(title = "Blue Hour", artist = "Mira Lane", cover = "sample0", isPlaying = true, positionMs = 84_000, durationMs = 236_000),
            shelf = shelf,
            covers = pictures.mapIndexed { index, res -> "sample$index" to ImageProvider(res) }.toMap(),
        )
    }
}
