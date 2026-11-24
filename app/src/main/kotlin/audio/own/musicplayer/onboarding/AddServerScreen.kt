package audio.own.musicplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import audio.own.core.designsystem.components.AppleSignInButton
import audio.own.core.designsystem.components.GoogleSignInButton
import audio.own.musicplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.musicplayer.R as AppR
import audio.own.musicplayer.app.text
import audio.own.musicplayer.ui.ButtonRow
import audio.own.musicplayer.ui.ErrorLine
import audio.own.musicplayer.ui.Page
import audio.own.musicplayer.ui.RowDivider

/** Add Server from Settings: the welcome screen's form on a page of its own. */
@Composable
fun AddServerContent(
    state: ServerFormState,
    showGoogle: Boolean,
    onCancel: () -> Unit,
    onAddress: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onName: (String) -> Unit,
    onConnect: () -> Unit,
    onQuickConnect: () -> Unit,
    onGoogle: () -> Unit,
    onApple: () -> Unit = {},
) {
    Page(title = stringResource(R.string.add_server), onBack = onCancel, closeIsCancel = true) {
        Section(header = null, footer = stringResource(AppR.string.add_server_footer)) {
            FormTextField(state.address, onAddress, stringResource(R.string.address), stringResource(AppR.string.add_server_placeholder), KeyboardType.Uri)
            RowDivider()
            FormTextField(state.username, onUsername, stringResource(R.string.username), null, KeyboardType.Email)
            RowDivider()
            FormTextField(
                state.password, onPassword, stringResource(R.string.password), null, KeyboardType.Password, secret = true, ime = ImeAction.Go,
                onGo = { if (state.canConnect) onConnect() },
            )
        }
        Section(header = null) {
            FormTextField(state.name, onName, stringResource(R.string.name_optional), null, KeyboardType.Text, ime = ImeAction.Done)
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        Section(header = null, footer = stringResource(R.string.quick_connect_footer)) {
            ButtonRow(stringResource(R.string.connect), onClick = onConnect, enabled = state.canConnect, working = state.connecting, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.quick_connect), onClick = onQuickConnect, enabled = state.address.isNotBlank() && !state.busy, color = MaterialTheme.colorScheme.primary)
        }
        val google = showGoogle && state.googleClientId != null
        if (google || state.appleWeb) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                if (state.appleWeb) AppleSignInButton(onClick = onApple, enabled = !state.busy)
                if (google) GoogleSignInButton(onClick = onGoogle, enabled = !state.busy)
                Text(stringResource(providersNote(google, state.appleWeb)), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
            }
        }
    }
}

/** The note under the Apple and Google buttons, naming the ones shown. */
fun providersNote(google: Boolean, apple: Boolean): Int = when {
    google && apple -> R.string.apple_or_google_on_this_server
    apple -> R.string.apple_on_this_server
    else -> R.string.google_on_this_server
}
