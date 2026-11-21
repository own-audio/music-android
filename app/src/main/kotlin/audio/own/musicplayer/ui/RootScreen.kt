package audio.own.musicplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import audio.own.core.servers.ServerRecord
import audio.own.musicplayer.R
import audio.own.musicplayer.servers.ServerKindIcon
import audio.own.core.strings.R as SR

/** The five tabs of music-ios, in its order. */
enum class Tab(@StringRes val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIBRARY(R.string.tab_library, Icons.Filled.LibraryMusic),
    PLAYLISTS(R.string.tab_playlists, Icons.AutoMirrored.Filled.QueueMusic),
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.DownloadForOffline),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
}

/**
 * The tabs. Home's bar has the server menu (with more than one server; the library shows one at
 * a time) and the gear that opens Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(
    servers: List<ServerRecord> = emptyList(),
    activeId: String? = null,
    onSelectServer: (String) -> Unit = {},
    onSettings: () -> Unit = {},
) {
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
        Scaffold(
            topBar = {
                if (selected == Tab.HOME) {
                    TopAppBar(
                        title = { Text(stringResource(Tab.HOME.title)) },
                        actions = {
                            if (servers.size > 1) ServerMenu(servers, activeId, onSelectServer)
                            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(SR.string.settings)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ServerMenu(servers: List<ServerRecord>, activeId: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Dns, contentDescription = stringResource(SR.string.servers)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.displayName) },
                    leadingIcon = { ServerKindIcon(server.kind, size = 22.dp) },
                    trailingIcon = { if (server.id == activeId) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = { open = false; onSelect(server.id) },
                )
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** The activity behind a (possibly wrapped) context: Credential Manager shows its sheet over it. */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
