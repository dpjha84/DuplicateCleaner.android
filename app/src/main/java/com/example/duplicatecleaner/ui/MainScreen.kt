package com.example.duplicatecleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.duplicatecleaner.MainViewModel
import com.example.duplicatecleaner.Phase
import com.example.duplicatecleaner.SnackAction
import com.example.duplicatecleaner.Tab
import com.example.duplicatecleaner.UiState

@Composable
fun MainScreen(state: UiState, vm: MainViewModel, onScan: () -> Unit, pro: ProActions) {
    val snackbar = remember { SnackbarHostState() }
    val imageLoader = rememberImageLoader()
    val inFlow = state.tab == Tab.HOME && state.phase != Phase.IDLE

    BackHandler(enabled = state.tab != Tab.HOME || state.phase == Phase.RESULTS) {
        if (state.tab != Tab.HOME) vm.setTab(Tab.HOME) else vm.leaveResults()
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        val action = state.messageAction
        val result = snackbar.showSnackbar(
            text,
            actionLabel = if (action == SnackAction.VIEW_TRASH) "View" else null,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed && action == SnackAction.VIEW_TRASH) {
            vm.leaveResults()
            vm.setTab(Tab.TRASH)
        }
        vm.messageShown()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!inFlow) {
                NavigationBar {
                    NavigationBarItem(
                        selected = state.tab == Tab.HOME, onClick = { vm.setTab(Tab.HOME) },
                        icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = state.tab == Tab.TRASH, onClick = { vm.setTab(Tab.TRASH) },
                        icon = {
                            BadgedBox(badge = { if (state.trashCount > 0) Badge { Text("${state.trashCount}") } }) {
                                Icon(Icons.Rounded.RestoreFromTrash, null)
                            }
                        },
                        label = { Text("Trash") }
                    )
                    NavigationBarItem(
                        selected = state.tab == Tab.SETTINGS, onClick = { vm.setTab(Tab.SETTINGS) },
                        icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.tab) {
                Tab.HOME -> when (state.phase) {
                    Phase.IDLE -> HomeScreen(state, vm, onScan)
                    Phase.SCANNING -> ScanningScreen(state, vm)
                    Phase.RESULTS -> ResultsScreen(state, vm, imageLoader, pro)
                }
                Tab.TRASH -> TrashScreen(state, vm, imageLoader)
                Tab.SETTINGS -> SettingsScreen(state, vm, pro)
            }
        }
    }

    if (state.showPaywall) {
        PaywallDialog(state, state.selectionSummary().bytes, pro)
    }
}
