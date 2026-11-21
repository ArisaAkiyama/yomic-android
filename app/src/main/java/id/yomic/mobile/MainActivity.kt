package id.yomic.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.yomic.mobile.data.service.SecurityController
import id.yomic.mobile.model.AppLanguage
import id.yomic.mobile.ui.i18n.LocalStrings
import id.yomic.mobile.ui.i18n.stringsFor
import id.yomic.mobile.ui.navigation.YomicAppShell
import id.yomic.mobile.ui.theme.YomicTheme
import id.yomic.mobile.ui.theme.YomicThemeTokens
import id.yomic.mobile.viewmodel.YomicViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : FragmentActivity() {

    private val viewModel: YomicViewModel by viewModels {
        val app = application as YomicApplication
        YomicViewModel.factory(app.container)
    }

    @Volatile
    private var isReaderActive: Boolean = false

    private var isAppUnlocked by mutableStateOf(false)
    private var hasResolvedInitialLock by mutableStateOf(false)
    private var lastBackgroundEpochMs: Long = 0L

    private val _volumeKeyEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val volumeKeyEvents = _volumeKeyEvents.asSharedFlow()

    private val _tabNavigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val tabNavigationEvents = _tabNavigationEvents.asSharedFlow()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* User decision respected by Android NotificationManager */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.isSettingsLoaded) {
                if (uiState.isSettingsLoaded && !hasResolvedInitialLock) {
                    hasResolvedInitialLock = true
                    if (!uiState.settings.appLockEnabled) {
                        isAppUnlocked = true
                    }
                }
            }

            val waitingForSettings = !uiState.isSettingsLoaded
            val lockRequired = uiState.isSettingsLoaded && uiState.settings.appLockEnabled && !isAppUnlocked

            LaunchedEffect(uiState.settings.secureScreenEnabled) {
                if (uiState.settings.secureScreenEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(lockRequired) {
                if (lockRequired) {
                    promptBiometricUnlock(uiState.settings.appLanguage == AppLanguage.EN)
                }
            }

            LaunchedEffect(uiState.isSettingsLoaded, lockRequired) {
                if (uiState.isSettingsLoaded && !lockRequired) {
                    requestNotificationPermissionOnceIfNeeded()
                }
            }

            CompositionLocalProvider(LocalStrings provides stringsFor(uiState.settings.appLanguage)) {
                YomicTheme(
                    darkTheme = uiState.settings.isDarkMode,
                    pureBlack = uiState.settings.pureBlackDarkMode
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        YomicAppShell(
                            state = uiState,
                            viewModel = viewModel,
                            volumeKeyEvents = volumeKeyEvents,
                            tabNavigationEvents = tabNavigationEvents,
                            onReaderActiveChanged = { active -> isReaderActive = active }
                        )

                        if (waitingForSettings) {
                            // Neutral canvas prevents a 1-frame library flash before DataStore emits appLockEnabled
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(YomicThemeTokens.colors.canvas)
                            )
                        } else if (lockRequired) {
                            val tokens = YomicThemeTokens.colors
                            val isEn = uiState.settings.appLanguage == AppLanguage.EN
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(tokens.canvas)
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.yomic_logo),
                                        contentDescription = "Yomic",
                                        modifier = Modifier.size(68.dp)
                                    )
                                    Text(
                                        text = if (isEn) "Yomic is Locked" else "Yomic Terkunci",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.inkPrimary
                                    )
                                    Text(
                                        text = if (isEn) "Authenticate with fingerprint or device PIN to continue."
                                        else "Verifikasi sidik jari atau PIN perangkat untuk melanjutkan.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = tokens.inkSecondary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = { promptBiometricUnlock(isEn) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.accent,
                                            contentColor = tokens.onAccent
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = if (isEn) "Unlock" else "Buka Kunci",
                                            fontWeight = FontWeight.SemiBold
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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            lastBackgroundEpochMs = System.currentTimeMillis()
        }
    }

    override fun onStart() {
        super.onStart()
        val elapsed = System.currentTimeMillis() - lastBackgroundEpochMs
        val currentState = viewModel.uiState.value
        if (lastBackgroundEpochMs > 0L &&
            elapsed > 30_000L &&
            currentState.settings.appLockEnabled &&
            !currentState.isConnectingMal
        ) {
            isAppUnlocked = false
        }
    }

    private fun requestNotificationPermissionOnceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        val prefs = getSharedPreferences("yomic_runtime_prompts", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_post_notifications", false)) return
        prefs.edit().putBoolean("asked_post_notifications", true).apply()
        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun promptBiometricUnlock(isEn: Boolean) {
        SecurityController.authenticate(
            activity = this,
            title = if (isEn) "Unlock Yomic" else "Buka Kunci Yomic",
            subtitle = if (isEn) "Verify your identity to access your library" else "Verifikasi identitas untuk membuka pustaka Anda",
            onSuccess = { isAppUnlocked = true },
            onFailure = { /* User stays on lock screen and can tap Unlock button */ }
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isReaderActive && viewModel.uiState.value.settings.volumeKeysNavigation) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                    _volumeKeyEvents.tryEmit(delta)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val openTab = intent?.getStringExtra(EXTRA_OPEN_TAB).orEmpty()
        if (openTab.isNotBlank()) {
            _tabNavigationEvents.tryEmit(openTab)
        }
        val data = intent?.data ?: return
        if (!data.scheme.equals("yomic", ignoreCase = true)) return
        when (data.host?.lowercase()) {
            "add-repo" -> {
                val repoUrl = data.getQueryParameter("url").orEmpty().trim()
                if (repoUrl.isNotBlank()) {
                    viewModel.addExtensionRepoUrl(repoUrl)
                }
            }
            "mal-done" -> {
                // Returning from Chrome Custom Tabs after loopback OAuth2 callback;
                // MalTrackerService finishes token exchange automatically on 127.0.0.1:49152.
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}
