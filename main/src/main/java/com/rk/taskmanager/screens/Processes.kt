package com.rk.taskmanager.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.rk.components.SettingsToggle
import com.rk.components.XedDialog
import com.rk.components.compose.preferences.base.DividerColumn
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.ProcessUiModel
import com.rk.taskmanager.ProcessViewModel
import com.rk.taskmanager.R
import com.rk.commons.settings.Settings
import com.rk.taskmanager.settings.SettingsRoutes
import com.rk.taskmanager.settings.pullToRefresh_procs
import com.rk.commons.strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Processes(
    modifier: Modifier = Modifier,
    viewModel: ProcessViewModel,
    navController: NavController
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (Settings.procAutoRefresh){
            while (isActive){
                viewModel.refreshProcessesAuto()
                delay(5000)
            }
        }
    }

    if (showFilter.value) {
        XedDialog(onDismissRequest = { showFilter.value = false }) {

            val showUserApps by viewModel.showUserApps.collectAsState()
            val showSystemApps by viewModel.showSystemApps.collectAsState()
            val showLinuxProcess by viewModel.showLinuxProcess.collectAsState()

            DividerColumn {

                // USER APPS
                SettingsToggle(
                    label = stringResource(strings.show_user_app),
                    showSwitch = true,
                    default = showUserApps,
                    isEnabled = !(showUserApps && !showSystemApps && !showLinuxProcess),
                    sideEffect = { newValue ->
                        if (!newValue && !showSystemApps && !showLinuxProcess) return@SettingsToggle
                        scope.launch {
                            Settings.showUserApps = newValue
                            viewModel.setShowUserApps(newValue)
                        }
                    }
                )

                // SYSTEM APPS
                SettingsToggle(
                    label = stringResource(strings.show_system_app),
                    showSwitch = true,
                    default = showSystemApps,
                    isEnabled = !(showSystemApps && !showUserApps && !showLinuxProcess),
                    sideEffect = { newValue ->
                        if (!newValue && !showUserApps && !showLinuxProcess) return@SettingsToggle
                        scope.launch {
                            Settings.showSystemApps = newValue
                            viewModel.setShowSystemApps(newValue)
                        }
                    }
                )

                // LINUX PROCESS
                SettingsToggle(
                    label = stringResource(strings.show_linux_process),
                    showSwitch = true,
                    default = showLinuxProcess,
                    isEnabled = !(showLinuxProcess && !showUserApps && !showSystemApps),
                    sideEffect = { newValue ->
                        if (!newValue && !showUserApps && !showSystemApps) return@SettingsToggle
                        scope.launch {
                            Settings.showLinuxProcess = newValue
                            viewModel.setShowLinuxProcess(newValue)
                        }
                    }
                )
            }
        }

    }



    if (showSort.value) {
        XedDialog(onDismissRequest = { showSort.value = false }) {

            val sortBy by viewModel.sortBy.collectAsState()

            DividerColumn {
                SettingsToggle(default = false, showSwitch = false, startWidget = {
                    RadioButton(selected = sortBy == ProcessViewModel.Sortby.Ram.id, onClick = {
                        viewModel.setSortBy(ProcessViewModel.Sortby.Ram)
                    })
                }, label = stringResource(strings.sort_by_ram), sideEffect = {
                    viewModel.setSortBy(ProcessViewModel.Sortby.Ram)
                })

                SettingsToggle(default = false, showSwitch = false, startWidget = {
                    RadioButton(selected = sortBy == ProcessViewModel.Sortby.Cpu.id, onClick = {
                        viewModel.setSortBy(ProcessViewModel.Sortby.Cpu)
                    })
                }, label = stringResource(strings.sort_by_cpu), sideEffect = {
                    viewModel.setSortBy(ProcessViewModel.Sortby.Cpu)
                })

                SettingsToggle(default = false, showSwitch = false, startWidget = {
                    RadioButton(selected = sortBy == ProcessViewModel.Sortby.A_z.id, onClick = {
                        viewModel.setSortBy(ProcessViewModel.Sortby.A_z)
                    })
                }, label = stringResource(strings.sort_by_name), sideEffect = {
                    viewModel.setSortBy(ProcessViewModel.Sortby.A_z)
                })



            }
        }

    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val content = @Composable {
            val listState = rememberLazyListState()

            val filteredProcesses by viewModel.filteredProcesses.collectAsState()

            if (filteredProcesses.isNotEmpty()) {

                // Expansion state lives in viewModel.treeExpandedPids (outside
                // composition); ordering is throttled/frozen by the ViewModel so
                // rows don't teleport under the finger while inspecting a tree

                // Top level shows ROOT processes only; children are rendered
                // inside their parent's own item, so expanding/collapsing
                // never changes the item count or shifts other rows' anchors
                // Rebuild the tree only when membership, parents or ORDER change;
                // keeping the same map/list instances across value-only refreshes
                // lets rows whose model didn't change skip recomposition
                val treeSignature =
                    filteredProcesses.joinToString(",") { "${it.proc.pid}:${it.proc.parentPid}" }
                val (roots, childrenMap) =
                    remember(treeSignature) { buildProcessTree(filteredProcesses) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {

                    items(roots, key = { it.proc.pid }) { root ->
                        val pid = root.proc.pid
                        Box(modifier = Modifier) {
                            ProcessItem(
                                modifier = modifier,
                                uiProc = root,
                                navController = navController,
                                viewModel = viewModel,
                                childrenMap = childrenMap,
                                expandedPids = viewModel.treeExpandedPids,
                                isExpanded = pid in viewModel.treeExpandedPids,
                                onToggleExpand = { viewModel.toggleTreeExpanded(pid) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.padding(bottom = 32.dp))
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val messages = listOf(
                        "¯\\_(ツ)_/¯",
                        "(¬_¬ )",
                        "(╯°□°）╯︵ ┻━┻",
                        "(>_<)",
                        "(ಠ_ಠ)",
                        "(•_•) <(no data)",
                        "(o_O)"
                    )

                    val message = rememberSaveable { messages.random() }
                    Text(message)

                    Spacer(modifier = Modifier.padding(vertical = 16.dp))

                    Button(onClick = {
                        viewModel.refreshProcessesManual()
                    }) {
                        Text(stringResource(strings.refresh))
                    }
                }

            }
        }

        if (pullToRefresh_procs){
            PullToRefreshBox(isRefreshing = viewModel.isLoading.value, onRefresh = {
                viewModel.refreshProcessesManual()
            }) {
                content()
            }
        }else{
            content()
        }
    }
}

const val textLimit = 40

// Splits the flat process list into ROOT rows plus a parentPid -> children index.
// A process is a root when its parentPid <= 0, equals its own pid, matches no
// other listed pid, or when its ancestor chain is trapped in a cycle — cycle
// members are promoted to top level so no row ever silently disappears.
// Each pid ends up either a root or in exactly one children group, and the
// children lists are pre-sorted by cpuUsage descending. O(n log n) total.
private fun buildProcessTree(
    processes: List<ProcessUiModel>
): Pair<List<ProcessUiModel>, Map<Int, List<ProcessUiModel>>> {
    val pids = HashSet<Int>(processes.size * 2)
    val parentOf = HashMap<Int, Int>(processes.size * 2)
    processes.forEach {
        pids += it.proc.pid
        parentOf[it.proc.pid] = it.proc.parentPid
    }

    // Resolve each parent chain upward once (memoized): does it terminate at a
    // genuine root, or does it walk into a cycle?
    val resolved = HashSet<Int>(processes.size * 2)
    val cyclic = HashSet<Int>(processes.size * 2)
    for (start in pids) {
        if (start in resolved) continue
        val path = ArrayList<Int>()
        val onPath = HashSet<Int>()
        var reachesRoot = true
        var cursor: Int? = start
        while (cursor != null) {
            val pid = cursor
            if (pid in resolved) {
                reachesRoot = pid !in cyclic
                break
            }
            if (!onPath.add(pid)) {
                reachesRoot = false // walked into a cycle
                break
            }
            path += pid
            val parent = parentOf.getValue(pid)
            cursor = if (parent > 0 && parent != pid && parent in pids) parent else null
        }
        path.forEach { pid ->
            resolved += pid
            if (!reachesRoot) cyclic += pid
        }
    }

    val roots = ArrayList<ProcessUiModel>(processes.size)
    val childrenMap = HashMap<Int, MutableList<ProcessUiModel>>(processes.size)
    processes.forEach { proc ->
        val pid = proc.proc.pid
        val parent = proc.proc.parentPid
        val isRoot = parent <= 0 || parent == pid || parent !in pids || pid in cyclic
        if (isRoot) {
            roots += proc
        } else {
            childrenMap.getOrPut(parent) { ArrayList() }.add(proc)
        }
    }

    childrenMap.values.forEach { children ->
        children.sortByDescending { it.proc.cpuUsage }
    }

    return roots to childrenMap
}

@Composable
fun ProcessItem(
    modifier: Modifier,
    uiProc: ProcessUiModel,
    navController: NavController,
    viewModel: ProcessViewModel,
    childrenMap: Map<Int, List<ProcessUiModel>> = emptyMap(),
    expandedPids: MutableSet<Int>? = null,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null
) {
    var showKillDialog by remember { mutableStateOf<ProcessUiModel?>(null) }

    // Android user id of THIS app (uid / 100000); processes owned by another
    // android user (work profile etc.) get a badge
    val myUser = android.os.Process.myUid() / 100000

    val children = childrenMap[uiProc.proc.pid].orEmpty()

    // Children live INSIDE this item's composition (not as separate LazyColumn
    // rows), so expanding a subtree never shifts any other row's anchor
    Column(modifier = modifier.fillMaxWidth()) {

        PreferenceTemplate(
            modifier = modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    indication = ripple(),
                    enabled = !uiProc.killed.value,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { navController.navigate(SettingsRoutes.ProcessInfo.createRoute(uiProc)) }
                ),
            contentModifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp)
                .padding(start = 16.dp),
            enabled = !uiProc.killed.value,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // weight(fill=false): a long name must ellipsize itself
                        // instead of squeezing the user badge into a vertical sliver
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.Bold,
                        text = if (uiProc.name.length > textLimit) {
                            uiProc.name.take(textLimit) + "..."
                        } else uiProc.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (uiProc.proc.pkgUser >= 0 && uiProc.proc.pkgUser != myUser) {
                        Spacer(modifier = Modifier.width(4.dp))

                        Box(
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "用户 ${uiProc.proc.pkgUser}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if (uiProc.isPinned.value) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            description = {
                //Text(uiProc.proc.cmdLine.removePrefix("/system/bin/").take(textLimit))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    if (uiProc.proc.frozen) {
                        // Frozen by the cgroup freezer; its anon pages are being swapped out
                        Icon(
                            imageVector = Icons.Outlined.AcUnit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // RAM Section
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.memory_alt_24px),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(modifier = Modifier.width(2.dp))

                        Text(
                            text = "${uiProc.proc.memoryUsageKb / 1024} MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // CPU Section
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.cpu_24px),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(modifier = Modifier.width(2.dp))

                        Text(
                            text = "${
                                String.format(Locale.ENGLISH, "%.1f", uiProc.proc.cpuUsage)
                            }%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (uiProc.proc.swapUsageKb > 0) {
                        Spacer(modifier = Modifier.width(6.dp))

                        // Swapped-out portion of this process
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SwapVert,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            Text(
                                text = "${uiProc.proc.swapUsageKb / 1024} MB",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (uiProc.childCount > 0 && onToggleExpand != null) {
                        Spacer(modifier = Modifier.width(6.dp))

                        // Child process count; the CPU figure above already includes them.
                        // Tap to expand/collapse the subtree inline. sizeIn BEFORE the
                        // click modifier grows the touch target without inflating visuals
                        Row(
                            modifier = Modifier
                                .sizeIn(minWidth = 44.dp, minHeight = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    indication = ripple(),
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onToggleExpand() }
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccountTree,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            Text(
                                text = "${uiProc.childCount}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(if (isExpanded) 180f else 0f)
                            )
                        }
                    }
                }

            },
            applyPaddings = false,
            startWidget = {
                if (uiProc.icon != null) {
                    Image(
                        bitmap = uiProc.icon,
                        contentDescription = stringResource(strings.app_name), // Using app_name for generic icon description
                        modifier = Modifier
                            .padding(start = 19.dp)
                            .size(24.dp),
                    )
                } else {
                    val fallbackId = when {
                        uiProc.proc.cmdLine.startsWith("/vendor") || uiProc.proc.cmdLine.isEmpty() ->
                            R.drawable.cpu_24px

                        uiProc.proc.cmdLine.startsWith("/data/local/tmp") || uiProc.proc.uid == 2000 ->
                            R.drawable.usb_24px

                        else ->
                            R.drawable.ic_android_black_24dp
                    }

                    Icon(
                        painter = painterResource(id = fallbackId),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 19.dp)
                            .size(24.dp)
                            .alpha(if (!uiProc.killed.value) 1f else 0.3f),
                    )
                }
            },
            endWidget = {
                if (uiProc.isUserApp) {
                    if (uiProc.killing.value) {
                        CircularProgressIndicator(modifier = Modifier
                            .padding(end = 22.dp)
                            .size(16.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            modifier = Modifier.padding(end = 7.dp),
                            enabled = !uiProc.killed.value,
                            onClick = {
//                            viewModel.viewModelScope.launch {
//                                uiProc.killing.value = true
//                                uiProc.killed.value = killProc(uiProc.proc)
//                                delay(300)
//                                uiProc.killing.value = false
//                            }


                                showKillDialog = uiProc
                            }) {
                            if (uiProc.killed.value) {
                                Icon(imageVector = Icons.Outlined.Check, null)
                            } else {
                                Icon(imageVector = Icons.Outlined.Close, null)
                            }
                        }
                    }

                }

            }

        )

        if (children.isNotEmpty() && expandedPids != null && onToggleExpand != null) {
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    children.forEach { child ->
                        ChildProcessRow(
                            uiProc = child,
                            childrenMap = childrenMap,
                            expandedPids = expandedPids,
                            navController = navController,
                            depth = 1,
                            onToggleExpand = { p -> viewModel.toggleTreeExpanded(p) }
                        )
                    }
                }
            }
        }

    }

    if (showKillDialog != null) {
        if (Settings.confirmkill){
            XedDialog(
                onDismissRequest = { showKillDialog = null }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {

                    Text(
                        text = stringResource(strings.terminate),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = stringResource(strings.terminate_confirm, showKillDialog?.name ?: "")
                    )

                    Spacer(modifier = Modifier.padding(vertical = 16.dp))

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        TextButton(onClick = {
                            showKillDialog = null
                        }) {
                            Text(stringResource(strings.cancel))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        TextButton(onClick = {


                            val dialog = showKillDialog

                            viewModel.viewModelScope.launch {
                                dialog?.killing?.value = true
                                dialog?.killed?.value = killProc(dialog?.proc!!)
                                delay(300)
                                dialog?.killing?.value = false
                            }

                            showKillDialog = null


                        }) {
                            Text(
                                text = stringResource(strings.kill),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }else{
            LaunchedEffect(Unit) {
                val dialog = showKillDialog
                viewModel.viewModelScope.launch {
                    dialog?.killing?.value = true
                    dialog?.killed?.value = killProc(dialog?.proc!!)
                    delay(300)
                    dialog?.killing?.value = false
                }

                showKillDialog = null
            }
        }

    }

}

// Real process trees are shallow; the bound only stops pathological
// recursion if recycled pids transiently form a parent/child cycle
private const val MAX_TREE_DEPTH = 16

// One row inside a parent's expandable area. Tapping the row opens the process
// info screen; a child that itself has children gets the same AccountTree +
// count + rotating chevron chip to toggle ITS subtree inline (recursive).
@Composable
private fun ChildProcessRow(
    uiProc: ProcessUiModel,
    childrenMap: Map<Int, List<ProcessUiModel>>,
    expandedPids: MutableSet<Int>,
    navController: NavController,
    depth: Int,
    onToggleExpand: (Int) -> Unit
) {
    val pid = uiProc.proc.pid

    // Recycled pids can briefly make /proc parenthood look cyclic; bound the
    // recursion instead of tracking visited pids, because a mutable guard set
    // fails on every recomposition and collapses the whole rendered subtree
    if (depth > MAX_TREE_DEPTH) return

    val myUser = android.os.Process.myUid() / 100000

    val childChildren = childrenMap[pid].orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                indication = ripple(),
                interactionSource = remember { MutableInteractionSource() },
                onClick = { navController.navigate(SettingsRoutes.ProcessInfo.createRoute(uiProc)) }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = uiProc.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (uiProc.proc.pkgUser >= 0 && uiProc.proc.pkgUser != myUser) {
                    Spacer(modifier = Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "用户 ${uiProc.proc.pkgUser}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Same marker style as the detail screen's child list:
            // "PID n · x.x% · N MB" (+ swapped-out amount, + snowflake when frozen)
            Text(
                text = buildString {
                    append("PID $pid · ")
                    append(String.format(Locale.ENGLISH, "%.1f", uiProc.proc.cpuUsage))
                    append("% · ${uiProc.proc.memoryUsageKb / 1024} MB")
                    if (uiProc.proc.swapUsageKb > 0) {
                        append(" · ⇄ ${uiProc.proc.swapUsageKb / 1024} MB")
                    }
                    if (uiProc.proc.frozen) {
                        append(" · ❄")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (childChildren.isNotEmpty()) {
            val expanded = pid in expandedPids

            Row(
                modifier = Modifier
                    .sizeIn(minWidth = 40.dp, minHeight = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        indication = ripple(),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onToggleExpand(pid) }
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(2.dp))

                Text(
                    text = "${childChildren.size}",
                    style = MaterialTheme.typography.bodySmall
                )

                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }
        }
    }

    if (childChildren.isNotEmpty()) {
        AnimatedVisibility(visible = pid in expandedPids) {
            // AnimatedVisibility stacks children like a Box; rows need an
            // explicit Column or they draw on top of each other
            Column {
                childChildren.forEach { child ->
                    ChildProcessRow(
                        uiProc = child,
                        childrenMap = childrenMap,
                        expandedPids = expandedPids,
                        navController = navController,
                        depth = depth + 1,
                        onToggleExpand = onToggleExpand
                    )
                }
            }
        }
    }
}
