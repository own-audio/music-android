package audio.own.musicplayer.servers

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.musicplayer.R

/** The kind's name, the same in every language. */
fun kindTitle(kind: String): String = when (kind) {
    ServerKinds.OWN_AUDIO -> "own.audio"
    ServerKinds.SUBSONIC -> "Subsonic"
    ServerKinds.JELLYFIN -> "Jellyfin"
    else -> kind
}

/** The server's kind at a glance: own.audio's, Jellyfin's or OpenSubsonic's logo. */
@Composable
fun ServerKindIcon(kind: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val shape = RoundedCornerShape(size * 0.22f)
    when (kind) {
        ServerKinds.OWN_AUDIO -> Image(painterResource(R.drawable.ic_server_own_audio), contentDescription = kindTitle(kind), modifier = modifier.size(size).clip(shape))
        ServerKinds.JELLYFIN -> Image(painterResource(R.drawable.ic_server_jellyfin), contentDescription = kindTitle(kind), modifier = modifier.size(size))
        // Drawn in one colour, so it follows the text in light and dark.
        ServerKinds.SUBSONIC -> Icon(painterResource(R.drawable.ic_server_opensubsonic), contentDescription = kindTitle(kind), tint = LocalPalette.current.foreground, modifier = modifier.size(size))
        else -> Icon(Icons.Filled.Dns, contentDescription = kindTitle(kind), tint = LocalPalette.current.foreground, modifier = modifier.size(size))
    }
}
