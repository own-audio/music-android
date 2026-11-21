package audio.own.musicplayer.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import audio.own.core.designsystem.components.AccentColorPicker
import audio.own.musicplayer.ui.Section
import audio.own.core.designsystem.components.SwitchRow
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.servers.MobileData
import audio.own.musicplayer.player.CrossfadeSection
import audio.own.musicplayer.player.LevelingSection
import audio.own.musicplayer.player.title
import audio.own.player.music.EqualizerSettings
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.servers.ServerRecord
import audio.own.core.strings.R
import audio.own.musicplayer.R as AppR
import audio.own.musicplayer.onboarding.SOURCE_URL
import audio.own.musicplayer.servers.ServerKindIcon
import audio.own.musicplayer.servers.kindTitle
import audio.own.musicplayer.ui.ButtonRow
import audio.own.musicplayer.ui.Page
import audio.own.musicplayer.ui.RowDivider
import audio.own.musicplayer.ui.TwoLines

/** "No Limit", "5 GB". */
@Composable
private fun keepAtMostTitle(gb: Int?): String = gb?.let { stringResource(AppR.string.gigabytes, it) } ?: stringResource(AppR.string.no_limit)

/** The in-app language, on top of the system's per-app language setting. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    CZECH("cs"),
    ;

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales()[0]?.language
            return entries.firstOrNull { it.tag != null && it.tag == tag } ?: SYSTEM
        }

        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList(),
            )
        }
    }
}

/** Each language's own name: not translated. */
private fun AppLanguage.endonym(system: String) = when (this) {
    AppLanguage.SYSTEM -> system
    AppLanguage.ENGLISH -> "English"
    AppLanguage.CZECH -> "Čeština"
}

data class SettingsUi(
    val servers: List<ServerRecord>,
    val activeId: String?,
    val accent: AccentChoice,
    val language: AppLanguage,
    /** "1.0.0 (123)". */
    val version: String,
    val playback: PlaybackSettings = PlaybackSettings(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    /** What the downloads take on this device. */
    val downloadsBytes: Long = 0,
)

@Composable
fun SettingsContent(
    ui: SettingsUi,
    onBack: () -> Unit,
    onOpenServer: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onAccent: (AccentChoice) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onLicences: () -> Unit,
    onLogs: () -> Unit,
    onPlayback: ((PlaybackSettings) -> PlaybackSettings) -> Unit = {},
    onEqualizer: () -> Unit = {},
    onDownloads: (() -> Unit)? = null,
) {
    var removing by remember { mutableStateOf<ServerRecord?>(null) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var choosingMobile by remember { mutableStateOf(false) }
    var choosingSaver by remember { mutableStateOf(false) }
    var choosingCap by remember { mutableStateOf(false) }
    val playback = ui.playback
    val context = androidx.compose.ui.platform.LocalContext.current
    val links = LocalUriHandler.current
    Page(title = stringResource(R.string.settings), onBack = onBack) {
        Section(header = stringResource(R.string.servers), footer = stringResource(AppR.string.servers_footer)) {
            ui.servers.forEach { server ->
                ServerRow(server, selected = server.id == ui.activeId, onOpen = { onOpenServer(server.id) }, onRemove = { removing = server })
                RowDivider(start = 64.dp)
            }
            ButtonRow(stringResource(R.string.add_a_server), onClick = onAddServer, icon = Icons.Filled.Add, color = MaterialTheme.colorScheme.primary)
        }

        Section(header = null, footer = stringResource(AppR.string.downloads_offline_footer)) {
            ValueRow(stringResource(AppR.string.downloads), android.text.format.Formatter.formatFileSize(context, ui.downloadsBytes), onClick = onDownloads)
            RowDivider()
            SwitchRow(stringResource(AppR.string.offline_mode), playback.offlineMode, { on -> onPlayback { it.copy(offlineMode = on) } })
            RowDivider()
            SwitchRow(stringResource(AppR.string.download_on_wifi_only), playback.downloadsWifiOnly, { on -> onPlayback { it.copy(downloadsWifiOnly = on) } })
            RowDivider()
            ValueRow(stringResource(AppR.string.keep_at_most), keepAtMostTitle(playback.keepAtMostGb), onClick = { choosingCap = true })
            RowDivider()
            SwitchRow(stringResource(AppR.string.whole_library_offline), playback.offlineWholeLibrary, { on -> onPlayback { it.copy(offlineWholeLibrary = on) } })
        }

        Section(header = stringResource(AppR.string.streaming), footer = stringResource(AppR.string.streaming_footer)) {
            ValueRow(stringResource(AppR.string.on_mobile_data), stringResource(playback.mobileData.title), onClick = { choosingMobile = true })
            if (playback.mobileData == MobileData.SAVER) {
                RowDivider()
                ValueRow(stringResource(AppR.string.saver_quality), stringResource(AppR.string.kbps, playback.saverKbps), onClick = { choosingSaver = true })
            }
        }

        CrossfadeSection(playback, onCrossfade = { on -> onPlayback { it.copy(crossfadeEnabled = on) } }, onFade = { secs -> onPlayback { it.copy(fadeSecs = secs) } })

        LevelingSection(playback.replayGain) { mode -> onPlayback { it.copy(replayGain = mode) } }

        Section(header = null) {
            val summary = if (!ui.equalizer.enabled) stringResource(AppR.string.eq_off) else ui.equalizer.preset?.let { stringResource(it.title) } ?: stringResource(AppR.string.custom)
            ValueRow(stringResource(AppR.string.equalizer), summary, onClick = onEqualizer)
        }

        AccentColorPicker(selected = ui.accent, onSelect = onAccent)

        Section(header = null, footer = stringResource(AppR.string.report_plays_footer)) {
            SwitchRow(stringResource(AppR.string.report_plays), playback.reportPlays, { on -> onPlayback { it.copy(reportPlays = on) } })
        }

        Section(header = null) {
            val system = stringResource(R.string.language_system)
            ValueRow(stringResource(R.string.language), ui.language.endonym(system), onClick = { choosingLanguage = true })
        }

        Section(header = stringResource(R.string.about), footer = stringResource(AppR.string.about_footer)) {
            ValueRow(stringResource(R.string.version), ui.version, onClick = null)
            RowDivider()
            ButtonRow(stringResource(R.string.source_code_and_licence), onClick = { links.openUri(SOURCE_URL) }, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.privacy), onClick = { links.openUri("$SOURCE_URL/blob/main/PRIVACY.md") }, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.licences), onClick = onLicences, color = MaterialTheme.colorScheme.primary)
            RowDivider()
            ButtonRow(stringResource(R.string.logs), onClick = onLogs, color = MaterialTheme.colorScheme.primary)
        }
    }

    removing?.let { server ->
        RemoveServerDialog(server.displayName, onConfirm = { onRemoveServer(server.id); removing = null }, onDismiss = { removing = null })
    }
    if (choosingMobile) {
        ChoiceDialog(
            title = stringResource(AppR.string.on_mobile_data),
            choices = MobileData.entries.map { it to stringResource(it.title) },
            selected = playback.mobileData,
            onChoose = { choice -> onPlayback { it.copy(mobileData = choice) }; choosingMobile = false },
            onDismiss = { choosingMobile = false },
        )
    }
    if (choosingCap) {
        ChoiceDialog(
            title = stringResource(AppR.string.keep_at_most),
            choices = PlaybackSettings.KEEP_AT_MOST_CHOICES.map { it to keepAtMostTitle(it) },
            selected = playback.keepAtMostGb,
            onChoose = { choice -> onPlayback { it.copy(keepAtMostGb = choice) }; choosingCap = false },
            onDismiss = { choosingCap = false },
        )
    }
    if (choosingSaver) {
        ChoiceDialog(
            title = stringResource(AppR.string.saver_quality),
            choices = PlaybackSettings.SAVER_CHOICES.map { it to stringResource(AppR.string.kbps, it) },
            selected = playback.saverKbps,
            onChoose = { choice -> onPlayback { it.copy(saverKbps = choice) }; choosingSaver = false },
            onDismiss = { choosingSaver = false },
        )
    }
    if (choosingLanguage) {
        LanguageDialog(ui.language, onChoose = { onLanguage(it); choosingLanguage = false }, onDismiss = { choosingLanguage = false })
    }
}

/** A server in the list: a tap opens its page, a swipe or its menu removes it, after asking. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(server: ServerRecord, selected: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove()
            swipe.reset()
        }
    }
    val palette = LocalPalette.current
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.lg), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(R.string.remove), color = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.backgroundAlt)
                .heightIn(min = 60.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(start = Tokens.Spacing.lg, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            ServerKindIcon(server.kind, size = 32.dp)
            TwoLines(server.displayName, "${kindTitle(server.kind)} · ${server.auth.userName}", Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.selected), tint = palette.muted)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = palette.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove), color = palette.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = palette.error) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

@Composable
fun RemoveServerDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_server_question, name)) },
        text = { Text(stringResource(R.string.remove_server_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove), color = LocalPalette.current.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val MobileData.title: Int
    get() = when (this) {
        MobileData.ORIGINAL -> AppR.string.mobile_original
        MobileData.SAVER -> AppR.string.mobile_saver
        MobileData.DOWNLOADED_ONLY -> AppR.string.mobile_downloaded_only
    }

/** A picker row's choices: one tap chooses and closes, as an iOS menu picker does. */
@Composable
fun <T> ChoiceDialog(title: String, choices: List<Pair<T, String>>, selected: T, onChoose: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                choices.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.minTarget)
                            .selectable(selected = value == selected, role = Role.RadioButton) { onChoose(value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = Tokens.Spacing.md))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LanguageDialog(current: AppLanguage, onChoose: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    val system = stringResource(R.string.language_system)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.minTarget)
                            .selectable(selected = language == current, role = Role.RadioButton) { onChoose(language) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == current, onClick = null)
                        Text(language.endonym(system), modifier = Modifier.padding(start = Tokens.Spacing.md))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
