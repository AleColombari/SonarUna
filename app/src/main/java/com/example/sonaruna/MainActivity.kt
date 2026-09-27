package com.example.sonaruna

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    // Only permission-request history persists. Conversation and coordinates never enter preferences.
    private val permissionHistory by lazy { getSharedPreferences("permission_history", MODE_PRIVATE) }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsResult(permissionStatus())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.BLACK),
        )
        setContent {
            val uiState = viewModel.state.collectAsStateWithLifecycle()
            MainScreen(uiState.value) { viewModel.onMicrophoneClick(permissionStatus()) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.actions.collect { action ->
                    when (action) {
                        PlatformAction.RequestPermissions -> requestMissingPermissions()
                        PlatformAction.OpenAppSettings -> {
                            try {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                            } catch (_: android.content.ActivityNotFoundException) {
                                viewModel.onSettingsUnavailable()
                            }
                        }
                        is PlatformAction.AnnounceVoiceFailure -> {
                            @Suppress("DEPRECATION")
                            window.decorView.announceForAccessibility(action.message)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground(permissionStatus())
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun permissionStatus(): PermissionStatus {
        val location = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val microphone = granted(Manifest.permission.RECORD_AUDIO)
        val locationBlocked = !location && permissionHistory.getBoolean("location_requested", false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val microphoneBlocked = !microphone && permissionHistory.getBoolean("microphone_requested", false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        return PermissionStatus(location, microphone, locationBlocked || microphoneBlocked)
    }

    private fun requestMissingPermissions() {
        val status = permissionStatus()
        val missing = buildList {
            if (!status.location) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!status.microphone) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isEmpty()) {
            viewModel.onPermissionsResult(status)
            return
        }
        permissionHistory.edit {
            if (!status.location) putBoolean("location_requested", true)
            if (!status.microphone) putBoolean("microphone_requested", true)
        }
        permissionLauncher.launch(missing.toTypedArray())
    }
}
