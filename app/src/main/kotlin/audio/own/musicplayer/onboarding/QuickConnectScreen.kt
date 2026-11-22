package audio.own.musicplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.musicplayer.R as AppR
import audio.own.musicplayer.app.text
import audio.own.musicplayer.ui.Page

/**
 * Quick Connect (`Servers/QuickConnectView.swift`): the code to approve in a Jellyfin app that is
 * already signed in, waiting for the approval, or what went wrong with Try Again.
 */
@Composable
fun QuickConnectContent(state: QuickConnectState, onCancel: () -> Unit, onTryAgain: () -> Unit) {
    val palette = LocalPalette.current
    Page(title = stringResource(AppR.string.quick_connect_title), onBack = onCancel, closeIsCancel = true, scrolls = false) {
        val error = state.error
        val code = state.code
        Box(Modifier.fillMaxSize().padding(Tokens.Spacing.xl), contentAlignment = Alignment.Center) {
            when {
                error != null -> EmptyState(
                    icon = Icons.Filled.Warning,
                    title = stringResource(AppR.string.quick_connect_didnt_work),
                    text = error.text(),
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onTryAgain,
                )
                code != null -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl),
                ) {
                    Text(stringResource(AppR.string.quick_connect_explainer), color = palette.muted, textAlign = TextAlign.Center)
                    SelectionContainer {
                        Text(
                            code,
                            style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 48.sp),
                            // Read out digit by digit, as it is typed elsewhere.
                            modifier = Modifier.semantics { contentDescription = code.toCharArray().joinToString(" ") },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(AppR.string.quick_connect_waiting), color = palette.muted)
                    }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}
