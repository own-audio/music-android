@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package audio.own.musicplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.musicplayer.AppContainer
import audio.own.musicplayer.account.DevicesContent
import audio.own.musicplayer.account.DevicesViewModel
import audio.own.musicplayer.account.HouseholdContent
import audio.own.musicplayer.account.HouseholdViewModel
import audio.own.musicplayer.account.ListeningContent
import audio.own.musicplayer.account.ListeningViewModel
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import audio.own.musicplayer.apple.AppleSignIn
import audio.own.musicplayer.apple.AppleStatus
import audio.own.musicplayer.google.GoogleResult
import audio.own.musicplayer.google.GoogleSignIn
import audio.own.musicplayer.onboarding.AccountMode
import audio.own.musicplayer.onboarding.AddServerContent
import audio.own.musicplayer.onboarding.OnboardingContent
import audio.own.musicplayer.onboarding.OwnAudioAccountContent
import audio.own.musicplayer.onboarding.OwnAudioAccountViewModel
import audio.own.musicplayer.onboarding.QuickConnectContent
import audio.own.musicplayer.onboarding.QuickConnectViewModel
import audio.own.musicplayer.onboarding.ServerFormViewModel
import audio.own.musicplayer.onboarding.TwoFactorContent
import audio.own.musicplayer.onboarding.TwoFactorViewModel
import audio.own.musicplayer.servers.CatalogueUi
import audio.own.musicplayer.servers.ServerDetailContent
import audio.own.musicplayer.servers.ServerDetailViewModel
import audio.own.musicplayer.servers.ServerDetector
import audio.own.musicplayer.servers.ServerPageUi
import audio.own.musicplayer.servers.SignInAgainContent
import audio.own.musicplayer.servers.SignInAgainViewModel
import audio.own.musicplayer.settings.AppLanguage
import audio.own.musicplayer.settings.LicencesContent
import audio.own.musicplayer.settings.LogsContent
import audio.own.musicplayer.settings.SettingsContent
import audio.own.musicplayer.settings.SettingsUi
import audio.own.musicplayer.settings.shareLog
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.musicplayer.R as AppR
import audio.own.musicplayer.app.UiText
import audio.own.musicplayer.app.text
import audio.own.musicplayer.app.userMessage
import audio.own.musicplayer.player.EqualizerBody
import audio.own.musicplayer.player.PlayerRoute
import kotlinx.coroutines.flow.MutableStateFlow

private object Routes {
    const val MAIN = "main"
    const val ACCOUNT = "account/{mode}"
    const val SETTINGS = "settings"
    const val ADD_SERVER = "add-server"
    const val SERVER = "server/{id}"
    const val SIGN_IN_AGAIN = "sign-in-again/{id}"
    const val FAMILY = "family/{id}"
    const val DEVICES = "devices/{id}"
    const val LISTENING = "listening/{id}"
    const val LOGS = "logs"
    const val LICENCES = "licences"
    const val PLAYER = "player"
    const val EQUALIZER = "equalizer"
    const val IDENTIFY = "identify/{id}"
    const val IDENTIFY_RUN = "identify-run/{id}/{run}"
}

/**
 * The whole app: the theme with the chosen accent, the pages, the two-factor step and the
 * shared "That Didn't Work" dialog over them. [openPlayer] is set by the notification's tap.
 */
@UnstableApi
@Composable
fun AppRoot(container: AppContainer, openPlayer: MutableStateFlow<Boolean> = MutableStateFlow(false)) {
    val accent by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    OwnAudioTheme(accent = accent) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val nav = rememberNavController()
            var actionError by remember { mutableStateOf<UiText?>(null) }
            val open by openPlayer.collectAsState()
            LaunchedEffect(open) {
                if (open) {
                    openPlayer.value = false
                    if (container.playbackModel.state.value.currentTrack != null && nav.currentDestination?.route != Routes.PLAYER) nav.navigate(Routes.PLAYER)
                }
            }
            AppNavHost(container, nav, onError = { actionError = it.userMessage() })
            // A widget link, the shortcut or the assistant asked for something that can't play.
            val outside by container.playRequests.message.collectAsState()
            LaunchedEffect(outside) {
                outside?.let {
                    actionError = it
                    container.playRequests.clearMessage()
                }
            }
            audio.own.musicplayer.identify.OwnAudioOverlays(container.ownAudio, onError = { actionError = it.userMessage() })
            TwoFactorOverlay(container)
            AppleBrowser(container.appleSignIn)
            actionError?.let { ErrorAlert(it.text(), onDismiss = { actionError = null }) }
        }
    }
}

@UnstableApi
@Composable
private fun AppNavHost(container: AppContainer, nav: NavHostController, onError: (Throwable) -> Unit) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val back: () -> Unit = { nav.popBackStack() }
    fun arg(entry: androidx.navigation.NavBackStackEntry) = entry.arguments?.getString("id").orEmpty()

    NavHost(nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            when {
                !servers.loaded -> LoadingScreen()
                servers.servers.isEmpty() -> WelcomeRoute(container, onAccount = { nav.navigate("account/${it.name}") })
                else -> MainTabs(
                    container,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    onError = onError,
                )
            }
        }
        composable(
            Routes.PLAYER,
            enterTransition = { slideInVertically(tween(300)) { it } },
            exitTransition = { slideOutVertically(tween(300)) { it } },
            popEnterTransition = { slideInVertically(tween(300)) { it } },
            popExitTransition = { slideOutVertically(tween(300)) { it } },
        ) {
            PlayerRoute(container, onClose = { if (nav.currentDestination?.route == Routes.PLAYER) nav.popBackStack() }, onError = onError)
        }
        composable(Routes.EQUALIZER) {
            val eq by container.playbackSettings.equalizer.settings.collectAsStateWithLifecycle()
            Page(
                title = stringResource(AppR.string.equalizer),
                onBack = back,
                scrolls = false,
                actions = { androidx.compose.material3.TextButton(onClick = container.playbackSettings.equalizer::reset, enabled = eq.enabled) { androidx.compose.material3.Text(stringResource(AppR.string.reset)) } },
            ) {
                EqualizerBody(container)
            }
        }
        composable(Routes.ACCOUNT) { entry ->
            val mode = AccountMode.valueOf(entry.arguments?.getString("mode") ?: AccountMode.SIGN_IN.name)
            val vm = viewModel { OwnAudioAccountViewModel(mode, container.servers, container.log, GoogleSignIn.isAvailable, container.appleSignIn) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack(Routes.MAIN, inclusive = false) }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            OwnAudioAccountContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onName = vm::setName,
                onEmail = vm::setEmail,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onGoogle = { state.googleClientId?.let(google) },
                onApple = vm::signInWithApple,
            )
        }
        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val playback by container.playbackSettings.settings.collectAsStateWithLifecycle()
            val eq by container.playbackSettings.equalizer.settings.collectAsStateWithLifecycle()
            val downloads by container.downloads.state.collectAsStateWithLifecycle()
            SettingsContent(
                ui = SettingsUi(
                    servers = servers.servers,
                    activeId = servers.activeId,
                    accent = accent(container),
                    language = AppLanguage.current(),
                    version = "${container.versionName} (${container.versionCode})",
                    playback = playback,
                    equalizer = eq,
                    downloadsBytes = downloads.totalBytes,
                ),
                onPlayback = { change -> container.playbackSettings.update(change) },
                onEqualizer = { nav.navigate(Routes.EQUALIZER) },
                onBack = back,
                onOpenServer = { nav.navigate("server/$it") },
                onRemoveServer = { id -> container.servers.scopeLaunch { container.servers.remove(id) } },
                onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                onAccent = { choice -> scope.launch { container.preferences.setAccent(choice) } },
                onLanguage = { AppLanguage.apply(it); context.findActivity()?.recreate() },
                onLicences = { nav.navigate(Routes.LICENCES) },
                onLogs = { nav.navigate(Routes.LOGS) },
            )
        }
        composable(Routes.ADD_SERVER) {
            val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable, apple = container.appleSignIn) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack() }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            AddServerContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onAddress = vm::setAddress,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onName = vm::setName,
                onConnect = vm::connect,
                onQuickConnect = vm::quickConnect,
                onGoogle = { state.googleClientId?.let(google) },
                onApple = vm::signInWithApple,
            )
            if (state.quickConnecting) QuickConnectOverlay(container, state.address, state.name, state.quickConnectAttempt, onClose = vm::closeQuickConnect)
        }
        composable(Routes.SERVER) { entry ->
            val id = arg(entry)
            val vm = viewModel { ServerDetailViewModel(id, container.servers, ServerDetector(container.http, container.userAgent, container.log), container.log, container.library) }
            val detail by vm.state.collectAsStateWithLifecycle()
            val sync by container.library.sync.collectAsStateWithLifecycle()
            LaunchedEffect(detail.gone) { if (detail.gone) nav.popBackStack() }
            val record = servers.server(id)
            if (record == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            ServerDetailContent(
                ui = ServerPageUi(
                    server = record,
                    isActive = record.id == servers.activeId,
                    connection = servers.connections[id],
                    check = servers.checks[id],
                    checking = id in servers.checking,
                    refused = servers.isRefused(id),
                    detail = detail,
                    catalogue = CatalogueUi(
                        songs = detail.catalogue?.songs,
                        albums = detail.catalogue?.albums,
                        syncedAtMs = detail.catalogue?.syncedAtMs,
                        syncing = id in sync.syncing,
                        reading = sync.reading[id],
                        error = sync.failures[id]?.text(),
                        offline = container.offline.isOffline,
                    ),
                    identify = detail.identify && !container.offline.isOffline,
                    libraryName = record.libraryId?.let { chosen -> record.libraries?.firstOrNull { it.id == chosen }?.name }
                        ?: stringResource(AppR.string.all_music_libraries),
                ),
                onBack = back,
                onUse = { vm.use() },
                onCheckNow = { vm.checkNow() },
                onSignInAgain = { nav.navigate("sign-in-again/$id") },
                onDeleteAccount = vm::deleteAccount,
                onFamily = { nav.navigate("family/$id") },
                onDevices = { nav.navigate("devices/$id") },
                onListening = { nav.navigate("listening/$id") },
                onIdentify = { nav.navigate("identify/$id") },
                onRename = { vm.rename(it) },
                onConnection = { vm.setConnection(it) },
                onAddAddress = { vm.addAddress(it) },
                onRemoveAddress = { vm.removeAddress(it) },
                onRemove = vm::remove,
                onDismissError = vm::dismissErrors,
                onMusicLibrary = vm::openLibraries,
                onUpdateNow = vm::updateNow,
                onChooseLibrary = vm::chooseLibrary,
                onCloseLibraries = vm::closeLibraries,
            )
        }
        composable(Routes.IDENTIFY) { entry ->
            val id = arg(entry)
            val source = container.sources.source(id) as? audio.own.music.library.IdentifySource
            val availability by androidx.compose.runtime.produceState<audio.own.music.library.IdentifyAvailability?>(null, source) {
                value = source?.let { runCatching { it.identifyAvailability() }.getOrNull() }
            }
            val known = availability
            if (source == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else if (known != null) {
                audio.own.musicplayer.identify.IdentifyRunsRoute(source, known, back) { run -> nav.navigate("identify-run/$id/${android.net.Uri.encode(run)}") }
            }
        }
        composable(Routes.IDENTIFY_RUN) { entry ->
            val id = arg(entry)
            val run = entry.arguments?.getString("run")?.let(android.net.Uri::decode).orEmpty()
            val source = container.sources.source(id) as? audio.own.music.library.IdentifySource
            if (source == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                audio.own.musicplayer.identify.IdentifyRunRoute(source, run, back, onChanged = { container.library.syncServer(id, force = true) })
            }
        }
        composable(Routes.SIGN_IN_AGAIN) { entry ->
            val id = arg(entry)
            val vm = viewModel { SignInAgainViewModel(id, container.servers, container.log, GoogleSignIn.isAvailable, container.appleSignIn) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.done) { if (state.done) nav.popBackStack() }
            val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
            SignInAgainContent(
                state = state,
                showGoogle = GoogleSignIn.isAvailable,
                onCancel = back,
                onUsername = vm::setUsername,
                onPassword = vm::setPassword,
                onSubmit = vm::submit,
                onGoogle = { state.googleClientId?.let(google) },
                onApple = vm::signInWithApple,
            )
        }
        composable(Routes.FAMILY) { entry ->
            val id = arg(entry)
            val vm = viewModel { HouseholdViewModel({ container.servers.client(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            HouseholdContent(
                state = state,
                offline = container.offline.isOffline,
                onBack = back,
                onRetry = vm::load,
                onInvite = vm::createInvite,
                onShowInvite = vm::show,
                onWithdraw = vm::withdraw,
                onJoin = vm::join,
                onDismissError = vm::dismissError,
            )
        }
        composable(Routes.DEVICES) { entry ->
            val id = arg(entry)
            val vm = viewModel { DevicesViewModel({ container.servers.client(id) }, { container.servers.signOutEverywhere(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.signedOutEverywhere) { if (state.signedOutEverywhere) nav.popBackStack() }
            DevicesContent(
                state = state,
                onBack = back,
                onRetry = vm::load,
                onSignOut = vm::signOut,
                onSignOutEverywhere = vm::signOutEverywhere,
                onDismissError = vm::dismissError,
            )
        }
        composable(Routes.LISTENING) { entry ->
            val id = arg(entry)
            val vm = viewModel { ListeningViewModel({ container.servers.client(id) }) }
            val state by vm.state.collectAsStateWithLifecycle()
            ListeningContent(state, onBack = back, onRange = vm::setRange, onRetry = vm::load, onShared = vm::setShared, onDismissError = vm::dismissError)
        }
        composable(Routes.LOGS) {
            val context = LocalContext.current
            val lines by container.log.lines.collectAsStateWithLifecycle()
            LogsContent(lines, onBack = back, onShare = { shareLog(context, container.log, container.logHeader) })
        }
        composable(Routes.LICENCES) { LicencesContent(onBack = back) }
    }
}

@Composable
private fun accent(container: AppContainer): AccentChoice {
    val value by container.preferences.accent.collectAsState(initial = AccentChoice.APP)
    return value
}

@Composable
private fun WelcomeRoute(container: AppContainer, onAccount: (AccountMode) -> Unit) {
    val vm = viewModel { ServerFormViewModel(container.servers, container.log, GoogleSignIn.isAvailable, apple = container.appleSignIn) }
    val state by vm.state.collectAsStateWithLifecycle()
    val google = rememberGoogle(onToken = vm::signInWithGoogle, onFailed = vm::googleFailed)
    OnboardingContent(
        state = state,
        showGoogle = GoogleSignIn.isAvailable,
        onCreateAccount = { onAccount(AccountMode.SIGN_UP) },
        onSignIn = { onAccount(AccountMode.SIGN_IN) },
        onTryDemo = vm::tryDemo,
        onAddress = vm::setAddress,
        onUsername = vm::setUsername,
        onPassword = vm::setPassword,
        onName = vm::setName,
        onConnect = vm::connect,
        onQuickConnect = vm::quickConnect,
        onGoogle = { state.googleClientId?.let(google) },
        onApple = vm::signInWithApple,
    )
    if (state.quickConnecting) QuickConnectOverlay(container, state.address, state.name, state.quickConnectAttempt, onClose = vm::closeQuickConnect)
}

/** Quick Connect over the form that opened it; a sign-in closes both. */
@Composable
private fun QuickConnectOverlay(container: AppContainer, address: String, name: String, attempt: Int, onClose: () -> Unit) {
    val vm = viewModel(key = "quick-connect-$attempt") { QuickConnectViewModel(container.servers, address, name, container.log) }
    val close = {
        vm.cancel()
        onClose()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier, color = MaterialTheme.colorScheme.background) {
            val state by vm.state.collectAsStateWithLifecycle()
            QuickConnectContent(state, onCancel = close, onTryAgain = vm::run)
        }
    }
}

/** Asks Google for an id token made for [String] (the server's web client id). */
@Composable
private fun rememberGoogle(onToken: (String) -> Unit, onFailed: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { clientId ->
        scope.launch {
            when (val result = GoogleSignIn.idToken(context.findActivity() ?: context, clientId)) {
                is GoogleResult.Token -> onToken(result.idToken)
                is GoogleResult.Failed -> onFailed(result.reason)
                GoogleResult.Cancelled -> Unit
            }
        }
    }
}

/** The second step of a sign-in, over whichever page started it. */
@Composable
private fun TwoFactorOverlay(container: AppContainer) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val pending = servers.pendingTwoFactor ?: return
    Dialog(onDismissRequest = { container.servers.cancelTwoFactor() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier, color = MaterialTheme.colorScheme.background) {
            val vm = viewModel(key = pending.mfaToken) { TwoFactorViewModel(container.servers) }
            val state by vm.state.collectAsStateWithLifecycle()
            TwoFactorContent(state, onCode = vm::setCode, onCheck = vm::check, onCancel = vm::cancel)
        }
    }
}

/** Opens Sign in with Apple's page in a Custom Tab once the server named it; the redirect brings the answer back. */
@Composable
private fun AppleBrowser(apple: AppleSignIn) {
    val status by apple.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = status as? AppleStatus.Browser
    LaunchedEffect(open) {
        if (open == null || open.opened || open.url.isEmpty()) return@LaunchedEffect
        // Marked first: the app stops as the tab opens, and coming back unanswered is a cancel.
        apple.opened()
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context.findActivity() ?: context, open.url.toUri())
        } catch (e: ActivityNotFoundException) {
            apple.noBrowser()
        }
    }
}
