package com.dpjha.cloneout

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dpjha.cloneout.ads.ConsentManager
import com.dpjha.cloneout.data.Tool
import com.dpjha.cloneout.telemetry.Telemetry
import com.dpjha.cloneout.ui.MainScreen
import com.dpjha.cloneout.ui.ProActions
import com.dpjha.cloneout.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Set when we sent the user to Settings and should scan once they come back with access. */
    private var scanAfterSettings = false

    /** What to open once access is granted: null = the duplicate scan. */
    private var pendingTool: Tool? = null

    private fun runPending() {
        val t = pendingTool
        pendingTool = null
        if (t == null) viewModel.startScan() else viewModel.openTool(t)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Telemetry.init(application)
        // Ads and analytics only start once the consent flow allows it.
        ConsentManager.gather(this) {
            Telemetry.setConsent(true)
            viewModel.startAds()
        }
        setContent {
            val state by viewModel.state.collectAsState()
            AppTheme(state.themeMode, state.dynamicColor) {
              Box(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { grants ->
                        if (grants.isNotEmpty()) {
                            val ok = grants.values.all { it }
                            Telemetry.event("storage_permission_result", "granted" to ok)
                            if (ok) runPending()
                        }
                    }

                    MainScreen(
                        state = state,
                        vm = viewModel,
                        onScan = { tool ->
                            pendingTool = tool
                            when {
                                hasAllFilesAccess() -> runPending()
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                    scanAfterSettings = true
                                    Telemetry.event("storage_permission_request")
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                }
                                else -> {
                                    Telemetry.event("storage_permission_request")
                                    permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                    )
                                }
                            }
                        },
                        pro = ProActions(
                            openPaywall = viewModel::openPaywall,
                            closePaywall = viewModel::closePaywall,
                            buy = { viewModel.buyPro(this@MainActivity) },
                            watchAd = { viewModel.watchAd(this@MainActivity) },
                            cleanFree = viewModel::cleanFreePortion,
                            restore = viewModel::restorePurchase
                        )
                    )
                }
              }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshTrash()
        if (scanAfterSettings) {
            scanAfterSettings = false
            val granted = hasAllFilesAccess()
            Telemetry.event("storage_permission_result", "granted" to granted)
            if (granted) runPending()
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
}
