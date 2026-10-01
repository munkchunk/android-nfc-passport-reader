package io.github.munkchunk.passportreader.sample

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import io.github.munkchunk.passportreader.sample.ui.MrzEntryScreen
import io.github.munkchunk.passportreader.sample.ui.NfcReadScreen
import io.github.munkchunk.passportreader.sample.ui.ResultScreen
import io.github.munkchunk.passportreader.sample.ui.ScanScreen
import io.github.munkchunk.passportreader.sample.ui.theme.PassportReaderTheme
import io.github.munkchunk.passportreader.sample.ui.theme.brand
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: PassportReaderViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PassportReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val screen = viewModel.screen
                    SystemBarsFor(screen)

                    // Back undoes the last step rather than leaving the app.
                    // Without this the Activity simply finished, so backing out
                    // of a result minimised everything instead of returning to
                    // the form. Disabled on the entry screen so back there still
                    // exits, which is what a user expects at the root.
                    BackHandler(enabled = screen != Screen.MrzEntry) {
                        viewModel.backToEntry()
                    }

                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            // The camera runs full bleed; a bar over it would only
                            // steal room from the viewfinder.
                            if (screen != Screen.Scan) {
                                Column {
                                    // Medium weight: on a white header the title
                                    // needs its own presence, or the page's bold
                                    // instruction reads as the header instead.
                                    TopAppBar(
                                        title = {
                                            Text(
                                                stringResource(screen.barTitleRes()),
                                                fontWeight = FontWeight.Medium
                                            )
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(
                                            containerColor = MaterialTheme.brand.header,
                                            titleContentColor = MaterialTheme.brand.onHeader
                                        )
                                    )
                                    MaterialTheme.brand.headerRule?.let { HorizontalDivider(color = it) }
                                }
                            }
                        }
                    ) { padding ->
                        val readState by viewModel.readState.collectAsState()

                        Box(modifier = Modifier.padding(padding)) {
                            when (screen) {
                                Screen.MrzEntry -> MrzEntryScreen(
                                    nfcAvailable = viewModel.isNfcAvailable(),
                                    nfcEnabled = viewModel.nfcEnabled,
                                    onOpenNfcSettings = ::openNfcSettings,
                                    previousMrz = viewModel.previousMrz,
                                    draftMrz = viewModel.draftMrz,
                                    onScan = viewModel::startScan,
                                    onStartRead = viewModel::startRead
                                )

                                Screen.Scan -> ScanScreen(
                                    onMrzFound = viewModel::startRead,
                                    onCancel = viewModel::cancelScan
                                )

                                Screen.Reading -> NfcReadScreen(
                                    state = readState,
                                    onRetry = viewModel::retry,
                                    onBack = viewModel::backToEntry
                                )

                                Screen.Result -> {
                                    val passport = viewModel.passport
                                    if (passport == null) {
                                        // Should not happen; recover rather than crash.
                                        viewModel.backToEntry()
                                    } else {
                                        ResultScreen(
                                            passport = passport,
                                            onDone = viewModel::backToEntry
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ReaderMode is bound to a resumed Activity, so the manager needs to learn
    // about this one here and forget it again before the Activity goes away.
    override fun onResume() {
        super.onResume()
        viewModel.attachActivity(this)
    }

    override fun onPause() {
        super.onPause()
        viewModel.detachActivity()
    }

    // Straight to the NFC switch, or the wireless page that holds it on some
    // builds; if neither exists the button does nothing rather than crash. Not
    // Settings.Panel.ACTION_NFC: on the Samsung test phone (Android 13) that
    // panel opens with no switch in it, only a link on to Settings.
    private fun openNfcSettings() {
        val pages = listOf(
            Settings.ACTION_NFC_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS
        )
        for (action in pages) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: ActivityNotFoundException) {
                Timber.i(e, "No %s; trying the next settings page", action)
            }
        }
    }
}

/**
 * The status bar takes the header's colour so it reads as part of it. The
 * scanner has no header and runs the camera full bleed, where a white or green
 * band looked like a mistake, so there it is black. The theme XML sets the same
 * colours for the first frame, before this runs.
 */
@Composable
private fun ComponentActivity.SystemBarsFor(screen: Screen) {
    val scanning = screen == Screen.Scan
    val bar = if (scanning) Color.Black else MaterialTheme.brand.header
    val lightIcons = scanning || isSystemInDarkTheme()
    SideEffect {
        @Suppress("DEPRECATION") // Ignored under enforced edge-to-edge (API 35+); fine below.
        window.statusBarColor = bar.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !lightIcons
    }
}

@StringRes
private fun Screen.barTitleRes(): Int = when (this) {
    Screen.MrzEntry -> R.string.bar_mrz_entry
    // Never asked for: the scan screen runs full bleed and shows no bar.
    Screen.Scan -> R.string.bar_mrz_entry
    Screen.Reading -> R.string.bar_reading
    Screen.Result -> R.string.bar_result
}
