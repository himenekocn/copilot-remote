package com.copilot.remote

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.copilot.remote.data.ConnectionState
import com.copilot.remote.ui.navigation.AppNavigation
import com.copilot.remote.ui.theme.CopilotRemoteTheme
import com.copilot.remote.viewmodel.CopilotViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)
    private val sharedImage = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptShareIntent(intent)
        enableEdgeToEdge()

        val app = application as CopilotApplication
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            CopilotRemoteTheme {
                val viewModel: CopilotViewModel = viewModel {
                    CopilotViewModel(app.settingsStore, app.webSocketManager, app.notificationHelper)
                }
                val state by viewModel.uiState.collectAsState()
                LaunchedEffect(sharedText.value) {
                    sharedText.value?.let { viewModel.acceptSharedText(it); sharedText.value = null }
                }
                LaunchedEffect(sharedImage.value) {
                    sharedImage.value?.let { viewModel.acceptSharedImage(it.toString()); sharedImage.value = null }
                }
                LaunchedEffect(Unit) {
                    if (FirebaseApp.getApps(this@MainActivity).isNotEmpty()) {
                        FirebaseMessaging.getInstance().token
                            .addOnSuccessListener(viewModel::registerPushToken)
                            .addOnFailureListener { viewModel.updatePushStatus("Firebase token failed: ${it.message}") }
                    } else {
                        viewModel.updatePushStatus("此 APK 未配置 Firebase")
                    }
                }

                // Auto-connect if an active profile is saved or legacy wsUrl/key are present.
                LaunchedEffect(state.serverProfiles, state.activeProfileId, state.wsUrl, state.key) {
                    if (state.connectionState != ConnectionState.DISCONNECTED) return@LaunchedEffect

                    val activeProfile = state.serverProfiles.find { it.id == state.activeProfileId }
                    if (activeProfile != null) {
                        viewModel.connectToProfile(activeProfile)
                    } else if (state.wsUrl.isNotBlank() && state.key.isNotBlank()) {
                        viewModel.connect(state.wsUrl, state.key)
                    }
                }

                AppNavigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptShareIntent(intent)
    }

    private fun acceptShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        sharedImage.value = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The ViewModel handles disconnection in onCleared
    }
}
