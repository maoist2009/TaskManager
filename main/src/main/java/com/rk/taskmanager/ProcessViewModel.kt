package com.rk.taskmanager

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rk.taskmanager.daemon.daemon_messages
import com.rk.taskmanager.daemon.send_daemon_messages
import com.rk.taskmanager.screens.drawableTobitMap
import com.rk.taskmanager.screens.getAppIconBitmap
import com.rk.taskmanager.screens.resolveAppInfo
import com.rk.commons.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

// List thumbnails never need full-size launcher bitmaps; cap icon rendering at 128px
private const val MAX_ICON_DIM_PX = 128

// Bound first-load icon resolution: one async per uncached process used to
// launch dozens of parallel PackageManager/LauncherApps calls at once; a
// 4-way permit keeps cores busy without the thread storm
private val iconResolvePermits = Semaphore(4)

// Re-sort at most this often while no subtree is expanded; live RAM/CPU values
// change every refresh and unthrottled reordering teleports rows under the
// user's finger, making tree toggle taps land on the wrong row
private const val SORT_THROTTLE_MS = 3000L

private data class AppInfoCache(
    val name: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
    val isApp: Boolean
)

// Process-wide so reopening the app (Activity recreation rebuilds the
// ViewModel) doesn't re-pay PackageManager/LauncherApps resolution for every
// process; bounded to keep the bitmap memory footprint capped
private val appInfoCache = android.util.LruCache<String, AppInfoCache>(512)

data class ProcessUiModel(
    val proc: ProcessViewModel.Process,
    val name: String,
    val icon: ImageBitmap?,
    val isSystemApp: Boolean,
    val isUserApp: Boolean,
    val isApp: Boolean,
    val childCount: Int = 0,
    val killing: MutableState<Boolean> = mutableStateOf(false),
    val killed: MutableState<Boolean> = mutableStateOf(false),
    val isPinned: MutableState<Boolean> = mutableStateOf(false)
)

@OptIn(FlowPreview::class)
class ProcessViewModel : ViewModel() {
    private val _uiProcesses = MutableStateFlow<List<ProcessUiModel>>(emptyList())

    // Expanded-subtree pids live OUTSIDE composition: the list screen is
    // disposed wholesale whenever MainScreen's isConnected branch flaps (daemon
    // reconnect), and composition-scoped state proved unreliable there.
    val treeExpandedPids = androidx.compose.runtime.mutableStateSetOf<Int>()

    fun toggleTreeExpanded(pid: Int) {
        val had = pid in treeExpandedPids
        if (had) treeExpandedPids.remove(pid) else treeExpandedPids.add(pid)
    }

    // Ordering cache: see SORT_THROTTLE_MS; while any subtree is expanded the
    // order is fully frozen so rows never move during inspection
    private var orderKey: List<Any> = emptyList()
    private var orderPids: Set<Int> = emptySet()
    private var lastSortElapsedMs = 0L
    private var orderedModels: List<ProcessUiModel> = emptyList()

    private val _showUserApps = MutableStateFlow(Settings.showUserApps)
    private val _showSystemApps = MutableStateFlow(Settings.showSystemApps)
    private val _showLinuxProcess = MutableStateFlow(Settings.showLinuxProcess)


    enum class Sortby(val id: Int){
        //edit default value in settings.kt of 0
        Ram(0),Cpu(1),A_z(2)
    }
    private val _sortBy = MutableStateFlow(Settings.sortby)

    val showUserApps = _showUserApps.asStateFlow()
    val showSystemApps = _showSystemApps.asStateFlow()
    val showLinuxProcess = _showLinuxProcess.asStateFlow()
    val sortBy = _sortBy.asStateFlow()
    private val _threadCount = MutableStateFlow(0)
    val threadCount = _threadCount.asStateFlow()

    private val _procCount = MutableStateFlow(0)
    val procCount = _procCount.asStateFlow()

    val filteredProcesses: StateFlow<List<ProcessUiModel>> = combine(
        _uiProcesses,
        _showUserApps,
        _showSystemApps,
        _showLinuxProcess,
        _sortBy
    ) { processes, showUser, showSystem, showLinux, sortBy ->

        val filtered = processes.filter { process ->
            when {
                process.isApp && process.isUserApp && showUser -> true
                process.isApp && process.isSystemApp && showSystem -> true
                !process.isApp && showLinux -> true
                else -> false
            }
        }

        // Reorder only when something structural changed (filters, sort mode,
        // membership) or the throttle window elapsed; with a tree expanded the
        // order stays frozen outright — membership churn (browsers constantly
        // spawn/kill sandbox processes) must NOT reorder rows mid-inspection,
        // fresh/dead pids simply swap into existing slots on the next unfreeze
        val cfgKey = listOf<Any>(showUser, showSystem, showLinux, sortBy)
        val pidSet = filtered.mapTo(HashSet(filtered.size)) { it.proc.pid }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val orderFrozen = treeExpandedPids.isNotEmpty()
        val resort = cfgKey != orderKey ||
                orderedModels.isEmpty() ||
                (!orderFrozen && (pidSet != orderPids || nowMs - lastSortElapsedMs >= SORT_THROTTLE_MS))

        val sorted = if (resort) {
            val primary = when (sortBy) {
                Sortby.Ram.id -> filtered.sortedByDescending { it.proc.memoryUsageKb }
                Sortby.Cpu.id -> filtered.sortedByDescending { it.proc.cpuUsage }
                Sortby.A_z.id -> filtered.sortedBy { it.name.lowercase() }
                else -> filtered
            }
            primary.sortedWith(
                // Stable secondary ordering: real apps float above native daemons under
                // every sort mode, pinned rows above unpinned within each group
                compareByDescending<ProcessUiModel> { it.isApp }.thenByDescending { it.isPinned.value }
            )
        } else {
            val byId = filtered.associateBy { it.proc.pid }
            orderedModels.mapNotNull { byId[it.proc.pid] }
        }

        orderKey = cfgKey
        orderPids = pidSet
        orderedModels = sorted
        if (resort) lastSortElapsedMs = nowMs

        sorted
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )


    // Use StateFlow for search query with debouncing
    private val _searchQuery = MutableStateFlow("")

    val searchResults: StateFlow<List<ProcessUiModel>> = combine(
        _searchQuery.debounce(150), // Debounce by 150ms
        filteredProcesses
    ) { query, processes ->
        if (query.isEmpty()) {
            processes
        } else {
            processes.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.proc.cmdLine.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun setShowUserApps(value: Boolean) {
        _showUserApps.value = value
    }

    fun setShowSystemApps(value: Boolean) {
        _showSystemApps.value = value
    }

    fun setSortBy(sortby: Sortby){
        Settings.sortby = sortby.id
        _sortBy.value = sortby.id
    }

    fun setShowLinuxProcess(value: Boolean) {
        _showLinuxProcess.value = value
    }

    fun togglePin(uiModel: ProcessUiModel) {
        val pinned = Settings.pinnedProcesses.toMutableSet()
        if (uiModel.isPinned.value) {
            pinned.remove(uiModel.proc.cmdLine)
            uiModel.isPinned.value = false
        } else {
            pinned.add(uiModel.proc.cmdLine)
            uiModel.isPinned.value = true
        }
        Settings.pinnedProcesses = pinned
        
        // Trigger a re-sort by updating the list
        _uiProcesses.update { currentList ->
            currentList.toList()
        }
    }

    val uiProcesses = _uiProcesses.asStateFlow()
    var isLoading = mutableStateOf(true)

    data class Process(
        val name: String,
        var nice: Int,
        val pid: Int,
        val uid: Int,
        val cpuUsage: Float,
        val parentPid: Int,
        val isForeground: Boolean,
        val memoryUsageKb: Long,
        val cmdLine: String,
        val state: String,
        val threads: Int,
        val startTime: Long,
        val elapsedTime: Float,
        val residentSetSizeKb: Long,
        val virtualMemoryKb: Long,
        val cgroup: String,
        val executablePath: String,
        val pkg: String = "",
        val pkgUser: Int = -1,
        val swapUsageKb: Long = 0,
        val frozen: Boolean = false
    )

    // Returns the previous model instance when nothing visible changed, so
    // Compose can skip recomposition for that row; transient MutableStates
    // (killing/killed/pin) keep their identity and current value
    private fun reuseOrPrev(prev: ProcessUiModel?, candidate: ProcessUiModel): ProcessUiModel {
        return if (prev != null &&
            prev.proc == candidate.proc &&
            prev.name == candidate.name &&
            prev.icon === candidate.icon &&
            prev.isSystemApp == candidate.isSystemApp &&
            prev.isUserApp == candidate.isUserApp &&
            prev.isApp == candidate.isApp &&
            prev.childCount == candidate.childCount &&
            prev.isPinned.value == candidate.isPinned.value
        ) {
            prev
        } else {
            candidate
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            daemon_messages.collect { message ->
                try {
                    val root = JSONObject(message)
                    if (root.optString("type") == "PROCESS_LIST") {
                        val jsonArray = root.getJSONArray("processes")
                        val newProcesses = mutableListOf<Process>()
                        var totalThreads = 0
                        val context = TaskManager.requireContext()
                        val pinnedSet = Settings.pinnedProcesses

                        // Previous generation for instance reuse: keeping equal
                        // Process/ProcessUiModel instances alive lets Compose skip
                        // recomposition for rows whose data didn't change, instead
                        // of rebuilding ~900 objects every refresh
                        val prevById = _uiProcesses.value.associateBy { it.proc.pid }
                        val prevProcs = HashMap<Int, Process>(prevById.size * 2)
                        for (m in prevById.values) prevProcs[m.proc.pid] = m.proc

                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val cmdLine = obj.optString("cmdLine", "")

                            val built = Process(
                                    name = obj.optString("name", ""),
                                    nice = obj.optInt("nice", 0),
                                    pid = obj.optInt("pid", 0),
                                    uid = obj.optInt("uid", 0),
                                    cpuUsage = obj.optDouble("cpuUsage", 0.0).toFloat(),
                                    parentPid = obj.optInt("parentPid", 0),
                                    isForeground = obj.optBoolean("isForeground", false),
                                    memoryUsageKb = obj.optLong("memoryUsageKb", 0L),
                                    cmdLine = cmdLine,
                                    state = obj.optString("state", ""),
                                    threads = obj.optInt("threads", 0).also { totalThreads += it },
                                    startTime = obj.optLong("startTime", 0L),
                                    elapsedTime = obj.optDouble("elapsedTime", 0.0).toFloat(),
                                    residentSetSizeKb = obj.optLong("residentSetSizeKb", 0L),
                                    virtualMemoryKb = obj.optLong("virtualMemoryKb", 0L),
                                    cgroup = obj.optString("cgroup", ""),
                                    executablePath = obj.optString("executablePath", ""),
                                    pkg = obj.optString("pkg", ""),
                                    pkgUser = obj.optInt("pkgUser", -1),
                                    swapUsageKb = obj.optLong("swapKb", 0L),
                                    frozen = obj.optBoolean("frozen", false)
                                )
                            val prevProc = prevProcs[built.pid]
                            newProcesses.add(
                                if (prevProc != null && prevProc == built) prevProc else built
                            )
                        }

                        _threadCount.value = totalThreads

                        val childCounts = newProcesses.groupingBy { it.parentPid }.eachCount()

                        // Cold start runs ~900 lookups through a 4-permit
                        // semaphore in list (=pid) order, so boot-time system
                        // daemons resolved seconds before recently launched
                        // user apps — the visible rows filled in last. Resolve
                        // app-uid processes first; output order stays pid-based.
                        val resolveOrder = newProcesses.indices.sortedBy { if (newProcesses[it].uid >= 10000) 0 else 1 }
                        val resolvedModels = arrayOfNulls<ProcessUiModel>(newProcesses.size)
                        resolveOrder.map { idx ->
                            val proc = newProcesses[idx]
                            async(Dispatchers.IO) {
                                resolvedModels[idx] = iconResolvePermits.withPermit {
                                    val isPinned = pinnedSet.contains(proc.cmdLine)
                                    val childCount = childCounts[proc.pid] ?: 0
                                    val cacheKey = "${proc.cmdLine}:${proc.uid}"
                                    val cached = appInfoCache[cacheKey]
                                    if (cached != null) {
                                        reuseOrPrev(
                                            prevById[proc.pid],
                                            ProcessUiModel(proc, cached.name, cached.icon, cached.isSystem, cached.isApp && !cached.isSystem, isApp = cached.isApp, childCount = childCount, isPinned = mutableStateOf(isPinned))
                                        )
                                    } else {
                                        val pm = context.packageManager
                                        val resolved = resolveAppInfo(
                                            context,
                                            proc.cmdLine,
                                            proc.uid,
                                            proc.pkg.takeIf { it.isNotEmpty() },
                                            proc.pkgUser
                                        )
                                        // PackageManager reflection is hidden-API blocked for packages
                                        // installed only in another Android user; LauncherApps still
                                        // resolves profile-group members, and as a last resort the
                                        // daemon reports the apk path for a direct archive parse
                                        var resolvedInfo = resolved?.info
                                        // Launcher metadata alone isn't enough; prefer a real
                                        // cross-user/archive resolve before giving up
                                        if (resolvedInfo == null &&
                                            resolved?.launcherLabel == null &&
                                            resolved?.launcherIcon == null &&
                                            proc.pkg.isNotEmpty() && proc.pkgUser >= 0
                                        ) {
                                            fetchCrossUserAppInfo(proc.pkg, proc.pkgUser)?.let { archiveInfo ->
                                                resolvedInfo = archiveInfo
                                            }
                                        }
                                        val name = resolvedInfo?.loadLabel(pm)?.toString()
                                            ?: resolved?.launcherLabel
                                            ?: resolved?.packageName
                                            ?: proc.name
                                        val icon = resolvedInfo
                                            ?.let { getAppIconBitmap(context, it, MAX_ICON_DIM_PX) }
                                            ?.asImageBitmap()
                                            ?: drawableTobitMap(resolved?.launcherIcon, MAX_ICON_DIM_PX)?.asImageBitmap()
                                        val system = resolvedInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
                                        val isApp = resolved != null || resolvedInfo != null
                                        val info = AppInfoCache(name, icon, system, isApp)
                                        appInfoCache.put(cacheKey, info)
                                        reuseOrPrev(
                                            prevById[proc.pid],
                                            ProcessUiModel(proc, name, icon, system, isApp && !system, isApp = isApp, childCount = childCount, isPinned = mutableStateOf(isPinned))
                                        )
                                    }
                                }
                            }
                        }.awaitAll()

                        val uiList = resolvedModels.map { requireNotNull(it) }

                        _procCount.value = newProcesses.size

                        withContext(Dispatchers.Main) {
                            _uiProcesses.update { uiList }
                            isLoading.value = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ProcessList", "Failed to parse process list: ${e.message}")
                }
            }
        }

        viewModelScope.launch {
            refreshProcessesAuto()
        }
    }

    // PackageManager reflection is hidden-API blocked for packages installed only in
    // another Android user, so ask the root daemon for the apk path and parse the
    // archive to recover label/icon
    private suspend fun fetchCrossUserAppInfo(pkg: String, user: Int): ApplicationInfo? {
        return withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(2000L) {
                    // Subscribe before emitting so the response can't be missed
                    val pathDeferred = async {
                        daemon_messages.first { message ->
                            try {
                                val json = JSONObject(message)
                                json.optString("type") == "PKG_APK_PATH" &&
                                        json.optString("pkg") == pkg
                            } catch (e: Exception) {
                                false
                            }
                        }.let { JSONObject(it).optString("path") }
                    }
                    send_daemon_messages.emit(
                        JSONObject().apply {
                            put("cmd", "PKG_APK_PATH")
                            put("pkg", pkg)
                            put("user", user)
                        }.toString()
                    )
                    val path = pathDeferred.await()
                    if (path.isEmpty()) {
                        null
                    } else {
                        TaskManager.requireContext().packageManager
                            .getPackageArchiveInfo(path, 0)?.applicationInfo
                    }
                }
            }.getOrNull()
        }
    }

    fun refreshProcessesManual() {
        isLoading.value = true
        viewModelScope.launch {
            send_daemon_messages.emit(JSONObject().apply { put("cmd", "LIST_PROCESS") }.toString())
        }
    }

    fun refreshProcessesAuto() {
        viewModelScope.launch {
            send_daemon_messages.emit(JSONObject().apply { put("cmd", "LIST_PROCESS") }.toString())
        }
    }
}