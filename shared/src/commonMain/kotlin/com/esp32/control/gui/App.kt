package com.esp32.control.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.esp32.control.gui.data.Esp32Repository
import com.esp32.control.gui.data.rememberLocalStore
import com.esp32.control.gui.settings.rememberSettingsStore
import com.esp32.control.gui.theme.Esp32Theme
import com.esp32.control.gui.ui.BottomChrome
import com.esp32.control.gui.ui.BottomTab
import com.esp32.control.gui.ui.DeviceScreen
import com.esp32.control.gui.ui.GroupScreen
import com.esp32.control.gui.ui.ScheduleScreen
import com.esp32.control.gui.ui.SettingsDestination
import com.esp32.control.gui.ui.rememberDeviceScreenState
import com.esp32.control.gui.ui.rememberGroupScreenState
import com.esp32.control.gui.ui.rememberScheduleScreenState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun App() {
    Esp32Theme {
        val settingsStore = rememberSettingsStore()
        val localStore = rememberLocalStore()
        val repository = remember(localStore) { Esp32Repository(localStore) }
        val viewModel: AppViewModel = viewModel { AppViewModel(settingsStore, repository) }
        val state by viewModel.state.collectAsState()

        if (state.serverUrl == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Esp32Theme
        }

        AppContent(state = state, viewModel = viewModel)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
private fun AppContent(
    state: AppUiState,
    viewModel: AppViewModel,
) {
    val devices by viewModel.devices.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val schedules by viewModel.schedules.collectAsState()

    val pagerState = rememberPagerState(pageCount = { BottomTab.entries.size })
    val scope = rememberCoroutineScope()
    val currentTab = BottomTab.entries[pagerState.targetPage]
    val snackbarHostState = remember { SnackbarHostState() }

    // Each screen owns its sheets and dialogs. The shell only creates the
    // state because the bottom bar's add action lives outside the pager.
    val deviceScreen = rememberDeviceScreenState()
    val groupScreen = rememberGroupScreenState()
    val scheduleScreen = rememberScheduleScreenState()

    // Sheets belong to the server they were opened on.
    LaunchedEffect(state.serverUrl) {
        deviceScreen.closeAll()
        groupScreen.closeAll()
        scheduleScreen.closeAll()
    }

    // A sheet belongs to its screen; leaving the tab closes it.
    LaunchedEffect(currentTab) {
        if (currentTab != BottomTab.Devices) deviceScreen.closeAll()
        if (currentTab != BottomTab.Groups) groupScreen.closeAll()
        if (currentTab != BottomTab.Schedules) scheduleScreen.closeAll()
    }

    // BackHandler is the only common back API available to shared UI; the newer
    // NavigationEventHandler needs a dispatcher owner the wasm host does not provide.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.learning.visible) {
                BottomChrome(
                    selected = currentTab,
                    onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                    onAdd = {
                        when (currentTab) {
                            BottomTab.Devices -> viewModel.openLearning()
                            BottomTab.Groups -> groupScreen.openEditor(null)
                            BottomTab.Schedules -> scheduleScreen.openEditor(null)
                            BottomTab.Settings -> Unit
                        }
                    },
                    addDescription = currentTab.addDescription,
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val columns = when {
                maxWidth >= 1040.dp -> 3
                maxWidth >= 660.dp -> 2
                else -> 1
            }
            val horizontalPadding = if (maxWidth >= 1040.dp) 32.dp else 20.dp

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> BottomTab.entries[page].key },
                // The learning sheet lives in the device screen; keep that page
                // composed until the session is dismissed.
                userScrollEnabled = !state.learning.visible,
            ) { page ->
                when (BottomTab.entries[page]) {
                    BottomTab.Devices ->
                        DeviceScreen(
                            state = state,
                            viewModel = viewModel,
                            screen = deviceScreen,
                            devices = devices,
                            columns = columns,
                            horizontalPadding = horizontalPadding,
                        )

                    BottomTab.Groups ->
                        GroupScreen(
                            state = state,
                            viewModel = viewModel,
                            screen = groupScreen,
                            groups = groups,
                            devices = devices,
                            horizontalPadding = horizontalPadding,
                        )

                    BottomTab.Schedules ->
                        ScheduleScreen(
                            state = state,
                            viewModel = viewModel,
                            screen = scheduleScreen,
                            schedules = schedules,
                            devices = devices,
                            groups = groups,
                            horizontalPadding = horizontalPadding,
                        )

                    BottomTab.Settings ->
                        SettingsDestination(state, viewModel)
                }
            }
        }
    }
}
