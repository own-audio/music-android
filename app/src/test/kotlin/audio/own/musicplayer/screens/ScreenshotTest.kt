package audio.own.musicplayer.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.AddressOrigin
import audio.own.core.servers.AddressScope
import audio.own.core.servers.ServerAddress
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.StoredTokens
import audio.own.musicplayer.onboarding.AccountMode
import audio.own.musicplayer.onboarding.AccountState
import audio.own.musicplayer.onboarding.OnboardingContent
import audio.own.musicplayer.onboarding.OwnAudioAccountContent
import audio.own.musicplayer.onboarding.ServerFormState
import audio.own.musicplayer.servers.ConnectionCheck
import audio.own.musicplayer.servers.ConnectionState
import audio.own.musicplayer.servers.ServerDetailContent
import audio.own.musicplayer.servers.ServerDetailState
import audio.own.musicplayer.servers.ServerKinds
import audio.own.musicplayer.servers.ServerPageUi
import audio.own.musicplayer.settings.AppLanguage
import audio.own.musicplayer.settings.SettingsContent
import audio.own.musicplayer.settings.SettingsUi
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome screen, the account sheet, Settings and a server page in light, dark, Czech and at
 * twice the font size. Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val home = ServerRecord(
        id = "s1",
        kind = ServerKinds.OWN_AUDIO,
        displayName = "own.audio",
        host = "https://music.example.com",
        auth = ServerAuth.OwnAudio("alex@example.com", StoredTokens("a", "r", 0)),
        serverIdentity = "srv-1",
        addresses = listOf(
            ServerAddress("http://192.168.1.10:8080", AddressScope.LAN, AddressOrigin.SERVER),
            ServerAddress("http://100.64.0.7:8080", AddressScope.VPN, AddressOrigin.USER),
        ),
    )
    private val demo = home.copy(id = "s2", displayName = "own.audio demo", host = "https://demo.example.com", auth = ServerAuth.OwnAudio("demo@example.com", StoredTokens("a", "r", 0)), addresses = emptyList())

    @Composable
    private fun Welcome() = OnboardingContent(
        state = ServerFormState(address = "music.example.com", username = "alex"),
        showGoogle = true,
        onCreateAccount = {}, onSignIn = {}, onTryDemo = {}, onAddress = {}, onUsername = {}, onPassword = {}, onName = {},
        onConnect = {}, onQuickConnect = {}, onGoogle = {},
    )

    @Composable
    private fun Account() = OwnAudioAccountContent(
        state = AccountState(AccountMode.SIGN_UP, name = "Alex", email = "alex@example.com", googleClientId = "web", appleWeb = true),
        showGoogle = true,
        onCancel = {}, onName = {}, onEmail = {}, onPassword = {}, onSubmit = {}, onGoogle = {},
    )

    @Composable
    private fun Settings() = SettingsContent(
        ui = SettingsUi(listOf(home, demo), activeId = "s1", accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1.0.0 (123)"),
        onBack = {}, onOpenServer = {}, onRemoveServer = {}, onAddServer = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
    )

    @Composable
    private fun Server() = ServerDetailContent(
        ui = ServerPageUi(
            server = home,
            isActive = false,
            connection = ConnectionState.Connected("http://192.168.1.10:8080"),
            check = ConnectionCheck(ConnectionCheck.Status.OK, Instant.parse("2026-11-21T14:05:00Z")),
            checking = false,
            refused = false,
            detail = ServerDetailState(version = "1.0.5"),
        ),
        onBack = {}, onUse = {}, onCheckNow = {}, onSignInAgain = {}, onDeleteAccount = {}, onFamily = {}, onDevices = {}, onListening = {},
        onRename = {}, onConnection = {}, onAddAddress = {}, onRemoveAddress = {}, onRemove = {}, onDismissError = {},
    )

    @Test fun welcomeLight() = shoot("welcome_light", false) { Welcome() }
    @Test fun welcomeDark() = shoot("welcome_dark", true) { Welcome() }
    @Test @Config(qualifiers = "+cs") fun welcomeCzech() = shoot("welcome_cs", false) { Welcome() }
    @Test @Config(fontScale = 2f) fun welcomeLargestText() = shoot("welcome_font200", false) { Welcome() }

    @Test fun accountLight() = shoot("account_light", false) { Account() }
    @Test fun accountDark() = shoot("account_dark", true) { Account() }
    @Test @Config(qualifiers = "+cs") fun accountCzech() = shoot("account_cs", false) { Account() }
    @Test @Config(fontScale = 2f) fun accountLargestText() = shoot("account_font200", false) { Account() }

    @Test fun settingsLight() = shoot("settings_light", false) { Settings() }
    @Test fun settingsDark() = shoot("settings_dark", true) { Settings() }
    @Test @Config(qualifiers = "+cs") fun settingsCzech() = shoot("settings_cs", false) { Settings() }
    @Test @Config(fontScale = 2f) fun settingsLargestText() = shoot("settings_font200", false) { Settings() }

    @Test fun serverLight() = shoot("server_light", false) { Server() }
    @Test fun serverDark() = shoot("server_dark", true) { Server() }
    @Test @Config(qualifiers = "+cs") fun serverCzech() = shoot("server_cs", false) { Server() }
    @Test @Config(fontScale = 2f) fun serverLargestText() = shoot("server_font200", false) { Server() }
}
