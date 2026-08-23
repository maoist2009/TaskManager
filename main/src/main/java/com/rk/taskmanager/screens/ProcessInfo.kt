package com.rk.taskmanager.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.rk.components.SettingsToggle
import com.rk.components.TextCard
import com.rk.components.XedDialog
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.taskmanager.ProcessUiModel
import com.rk.taskmanager.ProcessViewModel
import com.rk.taskmanager.TaskManager
import com.rk.taskmanager.daemon.daemon_messages
import com.rk.taskmanager.daemon.send_daemon_messages
import com.rk.commons.getString
import com.rk.taskmanager.settings.SettingsRoutes
import com.rk.commons.strings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

fun elapsedFromStartTime(startTimeTicks: Long): String {
    val processStartMillis = startTimeToMillis(startTimeTicks)
    val now = System.currentTimeMillis()
    val elapsedSeconds = (now - processStartMillis) / 1000

    val h = TimeUnit.SECONDS.toHours(elapsedSeconds)
    val m = TimeUnit.SECONDS.toMinutes(elapsedSeconds) % 60
    val s = elapsedSeconds % 60

    return String.format("%02d:%02d:%02d", h, m, s)
}

fun startTimeToMillis(startTimeTicks: Long): Long {
    val ticksPerSecond = sysconf() // custom helper below
    val bootTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val processStartMillis = bootTimeMillis + (startTimeTicks * 1000 / ticksPerSecond)
    return processStartMillis
}

fun sysconf(): Long {
    return Os.sysconf(OsConstants._SC_CLK_TCK)
}

const val PER_USER_RANGE = 100000

fun getAppUserId(uid: Int): Int = uid / PER_USER_RANGE

fun looksLikePackageName(value: String): Boolean {
    return value.isNotEmpty() && !value.startsWith("/") &&
            !value.contains(':') && !value.contains(' ')
}

class ResolvedApp(
    val packageName: String,
    val info: ApplicationInfo?,
    // Label/icon recovered via LauncherApps when PackageManager cannot resolve the
    // package in any user visible to us
    val launcherLabel: String? = null,
    val launcherIcon: Drawable? = null
)

// Launchers may query activity metadata for every member of their profile group
// (main user <-> work profile) without any special permission, while PackageManager
// reflection across users is hidden-API blocked
fun getLauncherActivity(context: Context, packageName: String, fullUid: Int): LauncherActivityInfo? {
    if (packageName.isEmpty()) return null
    return runCatching {
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        la?.getActivityList(packageName, UserHandle.getUserHandleForUid(fullUid))?.firstOrNull()
    }.onFailure {
        Log.w("TMDebug", "LauncherApps lookup failed for $packageName uid=$fullUid: $it")
    }.getOrNull()
}

fun getApplicationInfoAnyUser(context: Context, packageName: String, userId: Int): ApplicationInfo? {
    val pm = context.packageManager
    try {
        return pm.getApplicationInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        // Not visible in the calling user; the process may belong to another Android user
    }

    if (userId <= 0 || userId == getAppUserId(android.os.Process.myUid())) return null

    return runCatching {
        val userHandle = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        try {
            PackageManager::class.java.getMethod(
                "getApplicationInfoAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                UserHandle::class.java
            ).invoke(pm, packageName, 0, userHandle) as ApplicationInfo
        } catch (e: NoSuchMethodException) {
            PackageManager::class.java.getMethod(
                "getApplicationInfoAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(pm, packageName, 0, userId) as ApplicationInfo
        }
    }.onFailure {
        Log.w("TMDebug", "getApplicationInfoAsUser failed for $packageName user=$userId: $it")
    }.getOrNull()
}

fun resolveAppInfo(
    context: Context,
    cmdLine: String,
    uid: Int,
    daemonPkg: String? = null,
    daemonUser: Int = -1
): ResolvedApp? {
    val userId = if (daemonUser >= 0) daemonUser else getAppUserId(uid)

    // The daemon enumerates packages for every running user as root; when it reports
    // the owning package the name is authoritative even if PackageManager hides other
    // users (label/icon then degrade gracefully to best-effort). Trust it only when the
    // cmdline names that same package (":subprocess" suffixes count); path-like cmdlines
    // (shared-uid native binaries) keep their raw name.
    if (!daemonPkg.isNullOrEmpty() &&
        (cmdLine == daemonPkg || cmdLine.startsWith("$daemonPkg:"))
    ) {
        getApplicationInfoAnyUser(context, daemonPkg, userId)?.let {
            return ResolvedApp(daemonPkg, it)
        }
        // Package lives in another user; launchers still see its profile-group members
        getLauncherActivity(context, daemonPkg, uid)?.let {
            return ResolvedApp(
                daemonPkg,
                null,
                launcherLabel = it.label.toString(),
                launcherIcon = it.getIcon(0)
            )
        }
        return ResolvedApp(daemonPkg, null)
    }

    if (!looksLikePackageName(cmdLine)) return null

    getApplicationInfoAnyUser(context, cmdLine, userId)?.let { return ResolvedApp(cmdLine, it) }

    if (uid < android.os.Process.FIRST_APPLICATION_UID) return null

    // The package is not resolvable in our user but the uid encodes its owning user;
    // fall back to the packages associated with the full uid (covers work profiles etc.)
    val packages = try {
        context.packageManager.getPackagesForUid(uid)
    } catch (e: Exception) {
        null
    } ?: return null

    val info = packages.firstNotNullOfOrNull { getApplicationInfoAnyUser(context, it, userId) }
    if (info != null) return ResolvedApp(info.packageName, info)

    // Last resort before giving up: recover label/icon through LauncherApps even
    // though PackageManager cannot hand out the ApplicationInfo itself
    return packages.firstOrNull()?.let { pkgName ->
        val launcherActivity = getLauncherActivity(context, pkgName, uid)
        ResolvedApp(
            pkgName,
            null,
            launcherLabel = launcherActivity?.label?.toString(),
            launcherIcon = launcherActivity?.getIcon(0)
        )
    }
}

fun drawableTobitMap(drawable: Drawable?, maxDim: Int = 0): Bitmap? {
    return drawable?.let {
        if (it is BitmapDrawable &&
            (maxDim <= 0 || maxOf(it.bitmap.width, it.bitmap.height) <= maxDim)
        ) {
            it.bitmap
        } else {
            val width = maxOf(1, it.intrinsicWidth)
            val height = maxOf(1, it.intrinsicHeight)
            var targetWidth = width
            var targetHeight = height
            // Cap the rendered size so full-size launcher drawables don't allocate
            // screen-sized bitmaps for list thumbnails
            if (maxDim > 0 && maxOf(width, height) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(width, height)
                targetWidth = maxOf(1, (width * scale).roundToInt())
                targetHeight = maxOf(1, (height * scale).roundToInt())
            }
            val bitmap = createBitmap(targetWidth, targetHeight)
            val canvas = Canvas(bitmap)
            it.setBounds(0, 0, canvas.width, canvas.height)
            it.draw(canvas)
            bitmap
        }
    }
}

fun getAppIconBitmap(context: Context, appInfo: ApplicationInfo, maxDim: Int = 0): Bitmap? {
    val drawable = try {
        appInfo.loadIcon(context.packageManager)
    } catch (e: Exception) {
        null
    }
    return drawableTobitMap(drawable, maxDim)
}


suspend fun killProc(proc: ProcessViewModel.Process): Boolean {
    var killResult = false

    val context = TaskManager.requireContext()

    // Only force-stop when the cmdline is exactly the package being killed;
    // anything else (native binaries, :subprocesses) keeps the single-pid kill path
    val app = resolveAppInfo(context, proc.cmdLine, proc.uid, proc.pkg.takeIf { it.isNotEmpty() }, proc.pkgUser)
    val appPackage = app?.packageName?.takeIf { it == proc.cmdLine }

    killResult = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(3000L) {
                val resultDeferred = async {
                    daemon_messages.first { message ->
                        try {
                            val json = JSONObject(message)
                            json.optString("type") == "KILL_RESULT"
                        } catch (e: Exception) {
                            false
                        }
                    }.let { JSONObject(it).optBoolean("success") }
                }

                // Send kill command
                val cmd = JSONObject().apply {
                    if (appPackage != null) {
                        put("cmd", "FORCE_STOP")
                        put("pkg", appPackage)
                        put("user", if (proc.pkgUser >= 0) proc.pkgUser else getAppUserId(proc.uid))
                    } else {
                        put("cmd", "KILL")
                        put("pid", proc.pid)
                    }
                }
                send_daemon_messages.emit(cmd.toString())

                // Wait for result
                resultDeferred.await()
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrDefault(false)
    }

    com.rk.commons.settings.Settings.kills++
    return killResult
}

val procByPid = mutableStateMapOf<Int, WeakReference<ProcessUiModel?>?>()


@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)
@Composable
fun ProcessInfo(
    modifier: Modifier = Modifier,
    navController: NavController,
    viewModel: ProcessViewModel,
    proc: ProcessUiModel
) {
    var showKillDialog by remember { mutableStateOf<ProcessUiModel?>(null) }

    val username = remember { mutableStateOf(strings.unknown.getString()) }
    val scope = rememberCoroutineScope()
    val cpuUsage = remember { mutableIntStateOf(-1) }

    // Keyed by pid, not the instance: every list refresh replaces ProcessUiModel
    // instances and would otherwise restart these loops into a refresh storm
    LaunchedEffect(proc.proc.pid) {
        username.value = getUsernameFromUid(proc?.proc?.uid!!) ?: proc?.proc?.uid.toString()
    }


    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        TopAppBar(title = {
            Text(stringResource(strings.proc_info))
        }, navigationIcon = {
            IconButton(onClick = {
                navController.popBackStack()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = stringResource(strings.go_back)
                )
            }
        })
    }) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding), contentAlignment = Alignment.Center) {
            Column(modifier.verticalScroll(rememberScrollState())) {
                PreferenceGroup {
                    val enabled = proc!!.proc.pid > 1 && proc!!.killed.value.not() && proc!!.proc.cmdLine != "zygote" && proc!!.proc.cmdLine != "zygote64"
                    val interactionSource = remember { MutableInteractionSource() }
                    PreferenceTemplate(
                        modifier = modifier
                            .combinedClickable(
                                enabled = enabled,
                                indication = ripple(),
                                interactionSource = interactionSource,
                                onClick = {
                                    showKillDialog = proc
                                }
                            ),
                        contentModifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 16.dp)
                            .padding(start = 16.dp),
                        title = {
                            Text(
                                fontWeight = FontWeight.Bold,
                                text =
                                    if (proc!!.killing.value) {
                                        stringResource(
                                            if (proc.isApp) {
                                                strings.stopping
                                            } else {
                                                strings.killing
                                            }
                                        )
                                    } else {
                                        if (proc!!.killed.value!!) {
                                            stringResource(
                                                if (proc.isApp) {
                                                    strings.killed
                                                } else {
                                                    strings.stopped
                                                }
                                            )
                                        } else {
                                            stringResource(strings.kill)
                                        }
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        description = { Text(stringResource(strings.kill_proc)) },
                        enabled = enabled,
                        applyPaddings = false,
                        endWidget = null,
                        startWidget = {
                            if (proc!!.killing.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .alpha(if (enabled) 1f else 0.3f),
                                )
                            } else {
                                if (proc!!.killed.value) {
                                    Icon(
                                        modifier = Modifier
                                            .padding(start = 16.dp)
                                            .alpha(if (enabled) 1f else 0.3f),
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null
                                    )
                                }else{
                                    Icon(
                                        modifier = Modifier
                                            .padding(start = 16.dp)
                                            .alpha(if (enabled) 1f else 0.3f),
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = null
                                    )
                                }

                            }
                        }
                    )

                    SettingsToggle(
                        label = if (proc.isPinned.value) stringResource(strings.unpin) else stringResource(strings.pin),
                        description = if (proc.isPinned.value) stringResource(strings.unpin_desc) else stringResource(strings.pin_desc),
                        default = proc.isPinned.value,
                        // TEMP: premium check removed for local debugging; restore
                        // `isEnabled = bridge?.isPro()?.value == true` before release
                        isEnabled = true,
                        showSwitch = true,
                        sideEffect = {
                            viewModel.togglePin(proc)
                        }
                    )
                }

                PreferenceGroup {
                    var name by remember { mutableStateOf(strings.loading.getString()) }


                    LaunchedEffect(proc.proc.pid) {
                        val context = TaskManager.requireContext()
                        val resolved = resolveAppInfo(
                            context,
                            proc!!.proc.cmdLine,
                            proc.proc.uid,
                            proc.proc.pkg.takeIf { it.isNotEmpty() },
                            proc.proc.pkgUser
                        )
                        name = resolved?.info?.loadLabel(context.packageManager)?.toString()
                            ?: resolved?.packageName
                            ?: proc!!.proc.name
                    }

                    TextCard(text = stringResource(strings.name), description = name.trim())
                    TextCard(text = stringResource(strings.pid), description = proc!!.proc.pid.toString())
                    TextCard(
                        text = stringResource(
                            if (proc.isApp) {
                                strings.str_package
                            } else {
                                strings.command
                            }
                        ),
                        description = proc!!.proc.cmdLine.ifEmpty {
                            stringResource(strings.no_cmd)
                        }
                    )
                    TextCard(text = stringResource(strings.user), description = username.value)

                    // Owning Android user: prefer the daemon-reported owner of the package,
                    // fall back to decoding it from the process uid
                    val androidUserId =
                        if (proc.proc.pkgUser >= 0) proc.proc.pkgUser else getAppUserId(proc.proc.uid)
                    TextCard(
                        text = "Android user",
                        description = if (androidUserId == getAppUserId(android.os.Process.myUid())) {
                            "Main user (user $androidUserId)"
                        } else {
                            "User $androidUserId"
                        }
                    )


                    LaunchedEffect(Unit) {
                        daemon_messages.collect { message ->
                            try {
                                val json = JSONObject(message)
                                if (json.optString("type") == "PROCESS_CPU_USAGE") {
                                    cpuUsage.intValue = json.optInt("usage", -1)
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    LaunchedEffect(proc.proc.pid) {
                        while (isActive) {
                            val cmd = JSONObject().apply {
                                put("cmd", "PING_PID_CPU")
                                put("pid", proc!!.proc.pid)
                            }
                            send_daemon_messages.emit(cmd.toString())
                            delay(1000)
                        }
                    }

                    // Keep the child-process listing (and cpu figures) fresh while
                    // this page is open; the processes screen stops polling once
                    // navigated away
                    LaunchedEffect(proc.proc.pid) {
                        while (isActive) {
                            viewModel.refreshProcessesAuto()
                            delay(5000)
                        }
                    }

                    TextCard(
                        text = stringResource(strings.cpu_usage),
                        description = (if (cpuUsage.intValue == -1) {
                            proc!!.proc.cpuUsage.roundToInt().toString()
                        } else {
                            cpuUsage.intValue
                        }).toString() + "% (${strings.estimated.getString()})"
                    )
                    TextCard(
                        text = stringResource(strings.is_foreground),
                        description = proc!!.proc.isForeground.toString()
                    )

                    fun formatSize(kb: Long): String {
                        return if (kb >= 1000) {
                            val mb = kb / 1024f
                            String.format(java.util.Locale.US, "%.2f MB", mb)
                        } else {
                            "$kb KB"
                        }
                    }

                    TextCard(
                        text = stringResource(strings.ram_usage),
                        description = formatSize(proc!!.proc.memoryUsageKb)
                    )

                    if (proc!!.proc.residentSetSizeKb != proc!!.proc.memoryUsageKb) {
                        TextCard(
                            text = stringResource(strings.actual_ram_usage),
                            description = formatSize(proc!!.proc.residentSetSizeKb)
                        )
                    }

                    if (proc.proc.swapUsageKb > 0) {
                        TextCard(
                            text = "Swapped out",
                            description = formatSize(proc.proc.swapUsageKb)
                        )
                    }

                    if (proc.proc.frozen) {
                        TextCard(text = "Frozen", description = "true (cgroup freezer)")
                    }


                    TextCard(
                        text = stringResource(strings.niceness),
                        description = "${proc!!.proc.nice}"
                    )

                    TextCard(
                        text = stringResource(strings.status),
                        description = proc!!.proc.state
                    )

                    TextCard(
                        text = stringResource(strings.threads),
                        description = proc!!.proc.threads.toString()
                    )

                    TextCard(
                        text = stringResource(strings.start_time),
                        description = DateFormat.getDateTimeInstance().format(
                            Date(startTimeToMillis(proc!!.proc.startTime))
                        )
                    )

                    var elapsed by remember { mutableStateOf("") }

                    val startTimeTicks = proc!!.proc.startTime
                    LaunchedEffect(startTimeTicks) {
                        while (isActive) {
                            elapsed = elapsedFromStartTime(startTimeTicks)
                            delay(1000)
                        }
                    }

                    TextCard(
                        text = stringResource(strings.elapsed_time),
                        description = elapsed
                    )

                    if (proc!!.proc.executablePath != "null") {
                        TextCard(
                            text = stringResource(strings.exec_path),
                            description = proc!!.proc.executablePath
                        )
                    }

                    if (proc!!.proc.parentPid != 0) {

                        val text = stringResource(strings.parent_pid)
                        val description = proc!!.proc.parentPid.toString()
                        SettingsToggle(
                            label = text,
                            description = description,
                            default = false,
                            showSwitch = false,
                            onLongClick = {
                                val clipboard = TaskManager.requireContext()
                                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(text, description)
                                clipboard.setPrimaryClip(clip)

                                Toast.makeText(
                                    TaskManager.requireContext(),
                                    strings.copied.getString(),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            endWidget = {
                                Icon(
                                    modifier = Modifier.padding(end = 16.dp),
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null
                                )
                            },
                            sideEffect = {
                                scope.launch(Dispatchers.IO) {
                                    val parent = viewModel.uiProcesses.value.find {
                                        it.proc.pid == proc!!.proc.parentPid
                                    }

                                    withContext(Dispatchers.Main){
                                        if (parent != null){
                                            navController.navigate(
                                                SettingsRoutes.ProcessInfo.createRoute(
                                                    parent
                                                )
                                            )
                                        }else{
                                            Toast.makeText(TaskManager.requireContext(), strings.parent_not_found.getString(), Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                }

                            })

                    }

                    val childProcesses = viewModel.uiProcesses.value.filter {
                        it.proc.parentPid == proc!!.proc.pid && it.proc.pid != proc.proc.pid
                    }
                    if (childProcesses.isNotEmpty()) {
                        var childListExpanded by rememberSaveable { mutableStateOf(false) }

                        SettingsToggle(
                            label = "Child processes",
                            description = childProcesses.size.toString(),
                            default = false,
                            showSwitch = false,
                            endWidget = {
                                Icon(
                                    modifier = Modifier
                                        .padding(end = 16.dp)
                                        .rotate(if (childListExpanded) 180f else 0f),
                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            },
                            sideEffect = {
                                childListExpanded = !childListExpanded
                            }
                        )

                        AnimatedVisibility(visible = childListExpanded) {
                            // AnimatedVisibility stacks children like a Box; rows
                            // need an explicit Column or they draw on top of each other
                            Column {
                                childProcesses.forEach { child ->
                                    val childDesc = buildString {
                                        append("PID ${child.proc.pid} · ")
                                        append(String.format(java.util.Locale.ENGLISH, "%.1f", child.proc.cpuUsage))
                                        append("% · ${child.proc.memoryUsageKb / 1024} MB")
                                        if (child.proc.swapUsageKb > 0) {
                                            append(" · ⇄ ${child.proc.swapUsageKb / 1024} MB")
                                        }
                                        if (child.proc.frozen) {
                                            append(" · ❄")
                                        }
                                    }
                                    SettingsToggle(
                                        label = child.name,
                                        description = childDesc,
                                        default = false,
                                        showSwitch = false,
                                        endWidget = {
                                            Icon(
                                                modifier = Modifier.padding(end = 16.dp),
                                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                contentDescription = null
                                            )
                                        },
                                        sideEffect = {
                                            scope.launch(Dispatchers.IO) {
                                                withContext(Dispatchers.Main){
                                                    navController.navigate(
                                                        SettingsRoutes.ProcessInfo.createRoute(child)
                                                    )
                                                }
                                            }
                                        })
                                }
                            }
                        }
                    }

                    val context = LocalContext.current
                    if (proc.isApp){
                        SettingsToggle(
                            label = stringResource(strings.app_info),
                            description = stringResource(strings.app_info_desc),
                            default = false,
                            showSwitch = false,
                            endWidget = {
                                Icon(
                                    modifier = Modifier.padding(end = 16.dp),
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = null
                                )
                            },
                            sideEffect = {
                                val packageName = proc!!.proc.cmdLine
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:$packageName".toUri()
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            })
                    }


                }


                if (proc?.isApp == true) {
                    val descriptionState by produceState<DescriptionState>(initialValue = DescriptionState.Loading, key1 = proc?.proc?.cmdLine) {
                        val db = TaskManager.getDatabase(TaskManager.requireContext())
                        val desc = withContext(Dispatchers.IO) {
                            db.appDao().getDescription(proc!!.proc.cmdLine)
                        }

                        value = if (desc.isNullOrBlank()) {
                            DescriptionState.Empty
                        } else {
                            DescriptionState.Success(desc)
                        }
                    }

                    PreferenceGroup(heading = stringResource(strings.debloater_info)) {
                        when (descriptionState) {
                            is DescriptionState.Loading -> TextCard(text = stringResource(strings.loading), description = null, selection = true, copyDesOnLong = false)
                            is DescriptionState.Success -> TextCard(text = null, description = (descriptionState as DescriptionState.Success).text, selection = true,copyDesOnLong = false)
                            is DescriptionState.Empty -> TextCard(text = null, description = stringResource(strings.no_info_debloater), selection = true,copyDesOnLong = false)
                        }
                    }
                }




                Spacer(modifier = Modifier.padding(16.dp))
            }
        }

    }

    if (showKillDialog != null) {
        if (com.rk.commons.settings.Settings.confirmkill){
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


suspend fun getUsernameFromUid(uid: Int): String? = withContext(Dispatchers.IO) {
    val shellName = try {
        val process = ProcessBuilder("id", "-nu", uid.toString()).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        reader.readLine()?.trim()?.takeIf { name ->
            name.isNotEmpty() && name.any { !it.isDigit() }
        }
    } catch (e: Exception) {
        null
    }

    // Android app uids have no passwd entry; resolve them through PackageManager
    shellName ?: run {
        try {
            TaskManager.requireContext().packageManager
                .getPackagesForUid(uid)?.firstOrNull { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}

sealed class DescriptionState {
    object Loading : DescriptionState()
    data class Success(val text: String) : DescriptionState()
    object Empty : DescriptionState()
}
