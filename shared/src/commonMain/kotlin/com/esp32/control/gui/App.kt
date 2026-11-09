package com.esp32.control.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.esp32.control.gui.settings.rememberSettingsStore
import com.esp32.control.gui.theme.Esp32Theme
import com.esp32.control.gui.ui.AppShapes
import com.esp32.control.gui.ui.BottomChrome
import com.esp32.control.gui.ui.BottomTab
import com.esp32.control.gui.ui.DeleteIcon
import com.esp32.control.gui.ui.DeviceCard
import com.esp32.control.gui.ui.DeviceDetailSheet
import com.esp32.control.gui.ui.DeviceEditDialog
import com.esp32.control.gui.ui.DeviceHeader
import com.esp32.control.gui.ui.EmptyDevices
import com.esp32.control.gui.ui.EmptyGroups
import com.esp32.control.gui.ui.EmptySchedules
import com.esp32.control.gui.ui.GroupCard
import com.esp32.control.gui.ui.GroupEditorSheet
import com.esp32.control.gui.ui.GroupHeader
import com.esp32.control.gui.ui.LearningSheet
import com.esp32.control.gui.ui.LoadingDevices
import com.esp32.control.gui.ui.ScheduleCard
import com.esp32.control.gui.ui.ScheduleEditorSheet
import com.esp32.control.gui.ui.ScheduleHeader
import com.esp32.control.gui.ui.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun App() {
    Esp32Theme {
        val settingsStore = rememberSettingsStore()
        val viewModel: AppViewModel = viewModel { AppViewModel(settingsStore) }
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
    val pagerState = rememberPagerState(pageCount = { BottomTab.entries.size })
    val scope = rememberCoroutineScope()
    val currentTab = BottomTab.entries[pagerState.targetPage]
    val snackbarHostState = remember { SnackbarHostState() }

    // BackHandler is the only common back API available to shared UI; the newer
    // NavigationEventHandler needs a dispatcher owner the wasm host does not provide.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        viewModel.dismissError()
    }

    LaunchedEffect(state.groupNotice) {
        val message = state.groupNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        viewModel.dismissGroupNotice()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.learningVisible) {
                BottomChrome(
                    selected = currentTab,
                    onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                    onAdd = {
                        when (currentTab) {
                            BottomTab.Devices -> viewModel.openLearning()
                            BottomTab.Groups -> viewModel.openGroupEditor()
                            BottomTab.Schedules -> viewModel.openScheduleEditor()
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
            ) { page ->
                when (BottomTab.entries[page]) {
                    BottomTab.Devices ->
                        DevicesDestination(state, viewModel, columns, horizontalPadding)
                    BottomTab.Groups ->
                        GroupsDestination(state, viewModel, horizontalPadding)
                    BottomTab.Schedules ->
                        SchedulesDestination(state, viewModel, horizontalPadding)
                    BottomTab.Settings ->
                        SettingsDestination(state, viewModel)
                }
            }
        }
    }

    state.detailDevice?.let { device ->
        DeviceDetailSheet(
            device = device,
            pending = state.pendingDeviceId == device.id,
            gatewayOnline = state.gatewayOnline,
            errorText = state.error,
            onCommand = { action -> viewModel.sendCommand(device.id, action) },
            onEdit = { viewModel.openEditor(device.id) },
            onRemove = { viewModel.requestRemoval(device.id) },
            onClose = viewModel::closeDetails,
        )
    }

    state.editingDevice?.let { device ->
        DeviceEditDialog(
            device = device,
            busy = state.editBusy,
            errorText = state.editError,
            onSave = { name, location -> viewModel.saveDevice(device.id, name, location) },
            onDismiss = viewModel::closeEditor,
        )
    }

    if (state.learningVisible) {
        LearningSheet(
            learning = state.learning,
            busy = state.learningBusy,
            gatewayOnline = state.gatewayOnline,
            // The sheet reports learning failures only; a stale command
            // or refresh error is unrelated to this flow.
            errorText = state.learningError,
            testMessage = state.testMessage,
            completedDeviceName = state.completedDeviceName,
            deviceNameForId = { id -> state.devices.firstOrNull { it.id == id }?.name },
            onStart = viewModel::startLearning,
            onAccept = viewModel::acceptCandidate,
            onRescan = viewModel::rescan,
            onTest = viewModel::test,
            onConfirm = viewModel::confirmLearning,
            onClose = viewModel::closeLearning,
        )
    }

    state.removalTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DeleteIcon(Modifier.size(22.dp))
                    }
                }
            },
            title = { Text("移除遥控器？") },
            text = {
                Text(
                    "将从服务端删除“${target.name}”，并释放开码/关码。" +
                        "此操作不会向网关发送任何 RF 指令，也无法撤销已下发的物理动作。",
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.removeDevice(target.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoval) {
                    Text("取消")
                }
            },
        )
    }

    if (state.groupEditorVisible) {
        GroupEditorSheet(
            group = state.groupEditorTarget,
            devices = state.devices,
            busy = state.groupBusy,
            errorText = state.groupError,
            onSave = { name, deviceIds ->
                viewModel.saveGroup(state.groupEditorTarget?.id, name, deviceIds)
            },
            onRemove = state.groupEditorTarget?.let { group ->
                { viewModel.requestGroupRemoval(group.id) }
            },
            onDismiss = viewModel::closeGroupEditor,
        )
    }

    if (state.scheduleEditorVisible) {
        ScheduleEditorSheet(
            schedule = state.scheduleEditorTarget,
            devices = state.devices,
            groups = state.groups,
            busy = state.scheduleBusy,
            errorText = state.scheduleError,
            onSave = { name, targetType, targetId, action, rrule, timeZone, startsAt, enabled ->
                viewModel.saveSchedule(
                    scheduleId = state.scheduleEditorTarget?.id,
                    name = name,
                    targetType = targetType,
                    targetId = targetId,
                    action = action,
                    rrule = rrule,
                    timeZone = timeZone,
                    startsAtEpochMs = startsAt,
                    enabled = enabled,
                )
            },
            onRemove = state.scheduleEditorTarget?.let { schedule ->
                { viewModel.requestScheduleRemoval(schedule.id) }
            },
            onDismiss = viewModel::closeScheduleEditor,
        )
    }

    state.groupRemovalTarget?.let { group ->
        AlertDialog(
            onDismissRequest = viewModel::dismissGroupRemoval,
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DeleteIcon(Modifier.size(22.dp))
                    }
                }
            },
            title = { Text("删除分组？") },
            text = {
                Text(
                    "将删除“${group.name}”。指向该分组的定时任务会一并删除；设备本身不受影响。",
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.removeGroup(group.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissGroupRemoval) {
                    Text("取消")
                }
            },
        )
    }

    state.scheduleRemovalTarget?.let { schedule ->
        AlertDialog(
            onDismissRequest = viewModel::dismissScheduleRemoval,
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DeleteIcon(Modifier.size(22.dp))
                    }
                }
            },
            title = { Text("删除定时？") },
            text = {
                Text(
                    "将删除“${schedule.name.ifBlank { "未命名定时" }}”。此操作不会向网关发送任何 RF 指令。",
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.removeSchedule(schedule.id) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissScheduleRemoval) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun DevicesDestination(
    state: AppUiState,
    viewModel: AppViewModel,
    columns: Int,
    horizontalPadding: Dp,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DeviceHeader(health = state.health)
            }

            when {
                state.devices.isEmpty() && state.loading ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadingDevices()
                    }

                state.devices.isEmpty() ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyDevices(
                            gatewayOnline = state.gatewayOnline,
                            onLearn = viewModel::openLearning,
                        )
                    }

                else -> items(state.devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        pending = state.pendingDeviceId == device.id,
                        gatewayOnline = state.gatewayOnline,
                        onCommand = { action ->
                            viewModel.sendCommand(device.id, action)
                        },
                        onOpen = { viewModel.openDetails(device.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupsDestination(
    state: AppUiState,
    viewModel: AppViewModel,
    horizontalPadding: Dp,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { GroupHeader(health = state.health) }

            when {
                state.groups.isEmpty() && state.loading -> item {
                    LoadingDevices("正在同步分组…")
                }

                state.groups.isEmpty() -> item {
                    EmptyGroups(
                        onCreate = { viewModel.openGroupEditor() },
                    )
                }

                else -> listItems(state.groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        pending = state.pendingGroupId == group.id,
                        gatewayOnline = state.gatewayOnline,
                        onCommand = { action ->
                            viewModel.sendGroupCommand(group.id, action)
                        },
                        onOpen = { viewModel.openGroupEditor(group.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedulesDestination(
    state: AppUiState,
    viewModel: AppViewModel,
    horizontalPadding: Dp,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ScheduleHeader(health = state.health) }

            when {
                state.schedules.isEmpty() && state.loading -> item {
                    LoadingDevices("正在同步定时…")
                }

                state.schedules.isEmpty() -> item {
                    EmptySchedules(
                        onCreate = { viewModel.openScheduleEditor() },
                    )
                }

                else -> listItems(state.schedules, key = { it.id }) { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        devices = state.devices,
                        groups = state.groups,
                        pending = state.pendingScheduleId == schedule.id,
                        onToggle = { enabled ->
                            viewModel.setScheduleEnabled(schedule.id, enabled)
                        },
                        onOpen = { viewModel.openScheduleEditor(schedule.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDestination(
    state: AppUiState,
    viewModel: AppViewModel,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        SettingsScreen(
            serverUrl = state.serverUrl.orEmpty(),
            apiToken = state.apiToken,
            onSave = viewModel::saveConnection,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
        )
    }
}
