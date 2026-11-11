package com.homeprojector.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.homeprojector.launcher.download.DownloadManager
import com.homeprojector.launcher.ui.AppsScreen
import com.homeprojector.launcher.ui.BooksScreen
import com.homeprojector.launcher.ui.ChatScreen
import com.homeprojector.launcher.ui.DownloadsScreen
import com.homeprojector.launcher.ui.FilesScreen
import com.homeprojector.launcher.ui.HomeScreen
import com.homeprojector.launcher.ui.LocalScreen
import com.homeprojector.launcher.ui.MusicScreen
import com.homeprojector.launcher.ui.NasScreen
import com.homeprojector.launcher.ui.PhotosScreen
import com.homeprojector.launcher.ui.PlayerScreen
import com.homeprojector.launcher.ui.ProjectorTheme
import com.homeprojector.launcher.ui.ReaderScreen
import com.homeprojector.launcher.ui.SchoolScreen
import com.homeprojector.launcher.ui.SettingsScreen
import com.homeprojector.launcher.ui.VoiceScreen

sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
    data object Nas : Screen
    data object Local : Screen
    data object Chat : Screen
    data object Voice : Screen
    data object Settings : Screen
    data object Music : Screen
    data object Photos : Screen
    data object Books : Screen
    data object Files : Screen
    data object Downloads : Screen
    data object School : Screen
    data class Player(val uri: String, val title: String, val from: Screen = Home) : Screen
    data class Reader(val key: String, val path: String, val title: String, val from: Screen = Books) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DownloadManager.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val prefs = Prefs.get(this)

        setContent {
            ProjectorTheme {
                LauncherApp(prefs)
            }
        }
    }
}

@Composable
fun LauncherApp(prefs: Prefs) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen !is Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Player -> s.from
            is Screen.Reader -> s.from
            else -> Screen.Home
        }
    }

    val openBook: (String, String, String, Screen) -> Unit = { key, path, title, from ->
        screen = Screen.Reader(key, path, title, from)
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(prefs = prefs, onNavigate = { screen = it })
        Screen.Apps -> AppsScreen(onBack = { screen = Screen.Home })
        Screen.Nas -> NasScreen(
            prefs = prefs,
            onBack = { screen = Screen.Home },
            onPlay = { uri, title -> screen = Screen.Player(uri, title, Screen.Nas) },
            onSettings = { screen = Screen.Settings }
        )
        Screen.Local -> LocalScreen(
            onBack = { screen = Screen.Home },
            onPlay = { uri, title -> screen = Screen.Player(uri, title, Screen.Local) }
        )
        Screen.Chat -> ChatScreen(prefs = prefs, onBack = { screen = Screen.Home })
        Screen.Voice -> VoiceScreen(
            prefs = prefs,
            onBack = { screen = Screen.Home },
            onChat = { screen = Screen.Chat }
        )
        Screen.Settings -> SettingsScreen(prefs = prefs, onBack = { screen = Screen.Home })
        Screen.Music -> MusicScreen(prefs = prefs, onBack = { screen = Screen.Home })
        Screen.Photos -> PhotosScreen(prefs = prefs, onBack = { screen = Screen.Home })
        Screen.Books -> BooksScreen(
            prefs = prefs,
            onBack = { screen = Screen.Home },
            onOpenBook = { key, path, title -> openBook(key, path, title, Screen.Books) }
        )
        Screen.Files -> FilesScreen(
            prefs = prefs,
            onBack = { screen = Screen.Home },
            onPlay = { uri, title -> screen = Screen.Player(uri, title, Screen.Files) },
            onOpenBook = { key, path, title -> openBook(key, path, title, Screen.Files) },
            onLocal = { screen = Screen.Local }
        )
        Screen.Downloads -> DownloadsScreen(onBack = { screen = Screen.Home })
        Screen.School -> SchoolScreen(
            prefs = prefs,
            onBack = { screen = Screen.Home },
            onPlay = { uri, title -> screen = Screen.Player(uri, title, Screen.School) },
            onOpenBook = { key, path, title -> openBook(key, path, title, Screen.School) }
        )
        is Screen.Player -> PlayerScreen(
            uri = s.uri,
            title = s.title,
            onBack = { screen = s.from }
        )
        is Screen.Reader -> ReaderScreen(
            bookKey = s.key,
            filePath = s.path,
            title = s.title,
            onBack = { screen = s.from }
        )
    }
}