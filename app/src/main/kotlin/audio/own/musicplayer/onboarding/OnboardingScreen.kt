package audio.own.musicplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.brand.HeroBackground
import audio.own.core.designsystem.brand.OwnAudioMarkTribe
import audio.own.core.designsystem.brand.RotatingWordmark
import audio.own.core.designsystem.components.GoogleSignInButton
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.musicplayer.R as AppR
import audio.own.musicplayer.app.text
import audio.own.musicplayer.ui.ErrorLine
import audio.own.musicplayer.ui.RowDivider

const val SOURCE_URL = "https://github.com/own-audio/music-android"

/**
 * The first screen, over the own.audio hero: the mark, the wordmark, an own.audio account for
 * someone without a server, the demo, and the form for one's own server.
 */
@Composable
fun OnboardingContent(
    state: ServerFormState,
    showGoogle: Boolean,
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onTryDemo: () -> Unit,
    onAddress: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onName: (String) -> Unit,
    onConnect: () -> Unit,
    onQuickConnect: () -> Unit,
    onGoogle: () -> Unit,
) {
    val palette = LocalPalette.current
    val links = LocalUriHandler.current
    Box(Modifier.fillMaxSize()) {
        HeroBackground()
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                    OwnAudioMarkTribe(size = 120.dp)
                    RotatingWordmark()
                    Text(stringResource(AppR.string.onboarding_tagline), style = MaterialTheme.typography.bodyMedium, color = palette.muted, textAlign = TextAlign.Center)
                    Text(SERVER_LINE, style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
                }

                Card {
                    Column(
                        Modifier.padding(Tokens.Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                    ) {
                        Text(stringResource(R.string.onboarding_no_server), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(stringResource(AppR.string.onboarding_no_server_text), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
                        Button(onClick = onCreateAccount, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.create_own_audio_account), fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.sign_in)) }
                        TextButton(onClick = onTryDemo, enabled = !state.busy) {
                            if (state.tryingDemo) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.try_the_demo), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(stringResource(AppR.string.onboarding_demo_footnote), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                    Text(
                        stringResource(R.string.onboarding_or_connect),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.muted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ServerFields(state, onAddress, onUsername, onPassword, onName, onConnect, placeholder = stringResource(AppR.string.address_placeholder))
                }

                state.error?.let { ErrorLine(it.text()) }

                Button(onClick = onConnect, enabled = state.canConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.connecting) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.connect), fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onQuickConnect, enabled = state.address.isNotBlank() && !state.busy) {
                    Text(stringResource(R.string.quick_connect))
                }
                if (showGoogle && state.googleClientId != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                        GoogleSignInButton(onClick = onGoogle, enabled = !state.busy)
                        Text(stringResource(R.string.google_on_this_server), style = MaterialTheme.typography.bodySmall, color = palette.muted, textAlign = TextAlign.Center)
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                    Note(Icons.Outlined.Shield, stringResource(AppR.string.onboarding_note_sign_in))
                    Note(Icons.Filled.Info, stringResource(R.string.onboarding_note_own_audio))
                    Note(Icons.Filled.Lan, stringResource(R.string.onboarding_note_https))
                }
                TextButton(onClick = { links.openUri(SOURCE_URL) }) {
                    Text(stringResource(R.string.free_and_open_source), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The server's name list under the wordmark; product names, the same in every language. */
const val SERVER_LINE = "own.audio · Navidrome · Jellyfin"

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppBrand.radii.miniPlayer),
        color = LocalPalette.current.backgroundAlt.copy(alpha = 0.92f),
        content = content,
    )
}

/** Address, username, password and name on one card, each with its symbol. */
@Composable
fun ServerFields(
    state: ServerFormState,
    onAddress: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onName: (String) -> Unit,
    onConnect: () -> Unit,
    placeholder: String,
    addressLabel: String = stringResource(R.string.server_address),
) {
    Card {
        Column {
            Field(Icons.Filled.Language, state.address, onAddress, addressLabel, placeholder, KeyboardType.Uri)
            RowDivider(start = 52.dp)
            Field(Icons.Filled.Person, state.username, onUsername, stringResource(R.string.username), null, KeyboardType.Email)
            RowDivider(start = 52.dp)
            Field(Icons.Filled.Key, state.password, onPassword, stringResource(R.string.password), null, KeyboardType.Password, secret = true, onGo = { if (state.canConnect) onConnect() })
            RowDivider(start = 52.dp)
            Field(Icons.Outlined.Label, state.name, onName, stringResource(R.string.name_optional), null, KeyboardType.Text, last = true)
        }
    }
}

@Composable
private fun Field(
    icon: ImageVector,
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    keyboard: KeyboardType,
    secret: Boolean = false,
    last: Boolean = false,
    onGo: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(start = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = LocalPalette.current.muted, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(Tokens.Spacing.xs))
        FormTextField(value, onChange, label, placeholder, keyboard, Modifier.weight(1f), secret, if (onGo != null) ImeAction.Go else if (last) ImeAction.Done else ImeAction.Next, onGo)
    }
}

/** A text field without a box of its own, for a field on a card. */
@Composable
fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    ime: ImeAction = ImeAction.Next,
    onGo: (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = ime,
            autoCorrectEnabled = keyboard == KeyboardType.Text,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Note(icon: ImageVector, text: String) {
    val muted = LocalPalette.current.muted
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Icon(icon, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}
