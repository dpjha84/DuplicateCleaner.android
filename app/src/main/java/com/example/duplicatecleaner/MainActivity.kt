package com.example.duplicatecleaner

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.duplicatecleaner.ui.MainScreen
import com.example.duplicatecleaner.ui.ProActions
import com.example.duplicatecleaner.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Set when we sent the user to Settings and should scan once they come back with access. */
    private var scanAfterSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            AppTheme(state.themeMode, state.dynamicColor) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { grants ->
                        if (grants.isNotEmpty() && grants.values.all { it }) viewModel.startScan()
                    }

                    MainScreen(
                        state = state,
                        vm = viewModel,
                        onScan = {
                            when {
                                hasAllFilesAccess() -> viewModel.startScan()
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                    scanAfterSettings = true
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                }
                                else -> permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
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

    override fun onResume() {
        super.onResume()
        viewModel.refreshTrash()
        if (scanAfterSettings) {
            scanAfterSettings = false
            if (hasAllFilesAccess()) viewModel.startScan()
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
