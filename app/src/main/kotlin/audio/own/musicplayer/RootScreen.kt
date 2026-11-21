package audio.own.musicplayer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The five tabs of music-ios, in its order. */
enum class Tab(@StringRes val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIBRARY(R.string.tab_library, Icons.Filled.LibraryMusic),
    PLAYLISTS(R.string.tab_playlists, Icons.AutoMirrored.Filled.QueueMusic),
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.DownloadForOffline),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
}

@Composable
fun RootScreen() {
    MaterialTheme {
        var selected by rememberSaveable { mutableStateOf(Tab.HOME) }
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                Tab.entries.forEach { tab ->
                    item(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.title)) },
                    )
                }
            },
        ) {
            Scaffold { inner ->
                Box(Modifier.fillMaxSize().padding(inner).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
