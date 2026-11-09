package dev.koft.ev1527.gui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.koft.ev1527.contract.CommandAction
import dev.koft.ev1527.contract.Device
import dev.koft.ev1527.contract.Group
import dev.koft.ev1527.contract.HealthResponse
import dev.koft.ev1527.gui.AppUiState
import dev.koft.ev1527.gui.AppViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

@Composable
internal fun GroupHeader(health: HealthResponse?) {
    val gateway = health?.gateway
    val gatewayTone = when (gateway?.state) {
        dev.koft.ev1527.contract.GatewayState.ONLINE -> StatusTone.Active
        dev.koft.ev1527.contract.GatewayState.OFFLINE -> StatusTone.Warning
        else -> StatusTone.Neutral
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "分组",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        StatusChip(text = gatewayChipLabel(gateway?.state), tone = gatewayTone)
    }
}

@Composable
internal fun GroupCard(
    group: Group,
    pending: Boolean,
    gatewayOnline: Boolean,
    onCommand: (CommandAction) -> Unit,
    onOpen: () -> Unit,
) {
    val enabled = gatewayOnline && !pending && group.deviceIds.isNotEmpty()

    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.deviceIds.size} 台设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = { onCommand(CommandAction.TURN_ON) },
                enabled = enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp),
            ) {
                Text("全开", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onCommand(CommandAction.TURN_OFF) },
                enabled = enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp),
            ) {
                Text("全关", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
internal fun EmptyGroups(onCreate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GroupsIcon(Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(text = "还没有分组", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "一个设备可以同时属于多个分组。向分组下发指令时，服务端会为每台设备单独发布 RF 帧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onCreate,
                shape = CircleShape,
                modifier = Modifier.height(48.dp),
            ) {
                Text("创建分组")
            }
        }
    }
}

private val stringSetSaver: Saver<Set<String>, ArrayList<String>> = Saver(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditorSheet(
    group: Group?,
    devices: List<Device>,
    onSave: (String, List<String>) -> Deferred<String?>,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var selected by rememberSaveable(group?.id, stateSaver = stringSetSaver) {
        mutableStateOf(group?.deviceIds?.toSet() ?: emptySet())
    }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val canSave = name.isNotBlank() && !busy

    // Buttons must wait for the hide animation before the caller removes the
    // sheet from composition; gesture dismissal arrives through onDismissRequest.
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    fun submit() {
        if (busy) return
        busy = true
        errorText = null
        scope.launch {
            val message = try {
                onSave(name.trim(), selected.toList()).await()
            } finally {
                busy = false
            }
            if (message == null) {
                dismiss()
            } else {
                errorText = message
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        shape = AppShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (group == null) "创建分组" else "编辑分组",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = ::dismiss,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "关闭" },
                ) {
                    CloseIcon(Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                shape = AppShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "设备",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            if (devices.isEmpty()) {
                Text(
                    text = "还没有设备，请先在“设备”页学习一个遥控器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (device.id in selected) {
                                    selected - device.id
                                } else {
                                    selected + device.id
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = device.id in selected,
                            onCheckedChange = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = device.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            errorText?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { submit() },
                enabled = canSave,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (busy) "保存中…" else "保存")
            }

            if (onRemove != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRemove,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DeleteIcon(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除分组")
                }
            }
        }
    }
}

/** Group-domain sheet and dialog state, owned by the app shell for the add action. */
@Stable
internal class GroupScreenState(
    editorOpen: Boolean = false,
    editorId: String? = null,
    removalId: String? = null,
) {
    var editorOpen by mutableStateOf(editorOpen)
        private set
    var editorId by mutableStateOf(editorId)
        private set
    var removalId by mutableStateOf(removalId)
        private set

    fun openEditor(groupId: String?) {
        editorOpen = true
        editorId = groupId
    }

    fun closeEditor() {
        editorOpen = false
        editorId = null
    }

    fun requestRemoval(groupId: String) {
        removalId = groupId
    }

    fun dismissRemoval() {
        removalId = null
    }

    fun closeAll() {
        closeEditor()
        dismissRemoval()
    }
}

private val GroupScreenStateSaver: Saver<GroupScreenState, Any> = listSaver(
    save = { listOf(if (it.editorOpen) "1" else "0", it.editorId.orEmpty(), it.removalId.orEmpty()) },
    restore = {
        GroupScreenState(
            editorOpen = it.getOrNull(0) == "1",
            editorId = it.getOrNull(1).orNullIfEmpty(),
            removalId = it.getOrNull(2).orNullIfEmpty(),
        )
    },
)

@Composable
internal fun rememberGroupScreenState(): GroupScreenState =
    rememberSaveable(saver = GroupScreenStateSaver) { GroupScreenState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    screen: GroupScreenState,
    groups: List<Group>,
    devices: List<Device>,
    horizontalPadding: Dp,
) {
    PullToRefreshBox(
        isRefreshing = state.refresh.running,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
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
                groups.isEmpty() && state.loading -> item {
                    LoadingDevices("正在同步分组…")
                }

                !state.refresh.hasSnapshot && state.refresh.error != null -> item {
                    CacheErrorState(
                        message = state.refresh.error.orEmpty(),
                        onRetry = viewModel::refresh,
                    )
                }

                groups.isEmpty() -> item {
                    EmptyGroups(onCreate = { screen.openEditor(null) })
                }

                else -> listItems(groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        pending = state.pendingGroupId == group.id,
                        gatewayOnline = state.gatewayOnline,
                        onCommand = { action ->
                            viewModel.sendGroupCommand(group.id, action)
                        },
                        onOpen = { screen.openEditor(group.id) },
                    )
                }
            }
        }
    }

    val editorTarget = screen.editorId?.let { id -> groups.firstOrNull { it.id == id } }
    if (screen.editorOpen && (screen.editorId == null || editorTarget != null)) {
        GroupEditorSheet(
            group = editorTarget,
            devices = devices,
            onSave = { name, deviceIds ->
                viewModel.saveGroup(editorTarget?.id, name, deviceIds)
            },
            onRemove = editorTarget?.let { group ->
                { screen.requestRemoval(group.id) }
            },
            onDismiss = screen::closeEditor,
        )
    }

    screen.removalId?.let { id -> groups.firstOrNull { it.id == id } }?.let { group ->
        AlertDialog(
            onDismissRequest = screen::dismissRemoval,
            shape = AppShapes.Dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { DeleteDialogIcon() },
            title = { Text("删除分组？") },
            text = {
                Text(
                    "将删除“${group.name}”。指向该分组的定时任务会一并删除；设备本身不受影响。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        screen.dismissRemoval()
                        viewModel.removeGroup(group.id)
                    },
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
                TextButton(onClick = screen::dismissRemoval) {
                    Text("取消")
                }
            },
        )
    }
}

private fun String?.orNullIfEmpty(): String? = this?.ifEmpty { null }
