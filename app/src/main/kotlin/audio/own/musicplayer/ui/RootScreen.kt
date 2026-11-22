package audio.own.musicplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.own.musicplayer.R

/** The five tabs of music-ios, in its order. */
enum class Tab(@StringRes val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIBRARY(R.string.tab_library, Icons.Filled.LibraryMusic),
    PLAYLISTS(R.string.tab_playlists, Icons.AutoMirrored.Filled.QueueMusic),
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.DownloadForOffline),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
}

/**
 * The tabs: the navigation suite (a bottom bar on phones, a rail or drawer on tablets), the
 * selected tab's pages, and under them, on every tab, the offline banner and the mini player.
 */
@Composable
fun TabsScreen(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    above: @Composable () -> Unit = {},
    content: @Composable (Tab) -> Unit = { Placeholder() },
) {
    // Back on another tab's first page goes Home, as in the other own.audio apps; it used to
    // leave the app. A tab's own pages come first: their handlers are registered inside it.
    androidx.activity.compose.BackHandler(enabled = selected != Tab.HOME) { onSelect(Tab.HOME) }
    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            Tab.entries.forEach { tab ->
                item(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { TabLabel(stringResource(tab.title)) },
                )
            }
        },
    ) {
        Scaffold(
            bottomBar = { Column { above() } },
            containerColor = MaterialTheme.colorScheme.background,
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) { content(selected) }
        }
    }
}

/**
 * A tab's name on one line: at large font sizes "Downloads" shrinks to fit its tab instead of
 * breaking mid-word ("Downl / oads"), as in the Books app; five tabs need a lower floor than its four.
 */
@Composable
internal fun TabLabel(text: String) {
    val size = androidx.compose.material3.LocalTextStyle.current.fontSize
    Text(
        text,
        maxLines = 1,
        autoSize = if (size.isSp) androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = size, stepSize = 0.5.sp) else null,
        modifier = Modifier.testTag("tab.label"),
    )
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Expanded windows (tablets in landscape, Chromebooks, a large foldable open): the Library tab
 * gets its permanent sidebar. The same size class the navigation suite reads, so the sidebar
 * comes with the rail and never with the bottom bar.
 */
@Composable
fun isWide(): Boolean = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

/** A sidebar beside [content], inside the iPad's 220–320 dp. */
@Composable
fun WithSidebar(sidebar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(260.dp).fillMaxHeight()) { sidebar() }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
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
