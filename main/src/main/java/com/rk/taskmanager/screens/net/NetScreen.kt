package com.rk.taskmanager.screens.net

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.commons.ui.InfoCard
import com.rk.commons.ui.InfoItem
import com.rk.commons.ui.SectionHeader
import com.rk.components.SettingsToggle
import com.rk.taskmanager.daemon.daemon_messages
import com.rk.taskmanager.daemon.send_daemon_messages
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.Locale

// Local replacement for the pro-module network screen while it is absent
// from this build; backed by the daemon's net commands

private data class NetInterface(val name: String, val totalBytes: Long)

private fun interfaceLabel(name: String): String =
    if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp")) {
        "$name (VPN)"
    } else {
        name
    }

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.ENGLISH, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.ENGLISH, "%.2f MB", mb)
        kb >= 1 -> String.format(Locale.ENGLISH, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

@Composable
fun NetScreen(modifier: Modifier = Modifier) {
    var interfaces by remember { mutableStateOf(listOf<NetInterface>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var rxSpeed by remember { mutableStateOf(0.0) }
    var txSpeed by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (isActive) {
            send_daemon_messages.emit(JSONObject().apply { put("cmd", "LIST_NET_INTERFACES") }.toString())
            delay(5000)
        }
    }

    LaunchedEffect(selected) {
        val iface = selected ?: return@LaunchedEffect
        while (isActive) {
            send_daemon_messages.emit(
                JSONObject().apply {
                    put("cmd", "NET_PING")
                    put("interface", iface)
                }.toString()
            )
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        daemon_messages.collect { message ->
            try {
                val json = JSONObject(message)
                when (json.optString("type")) {
                    "NET_INTERFACE_LIST" -> {
                        val array = json.getJSONArray("interfaces")
                        val list = (0 until array.length()).map { i ->
                            val obj = array.getJSONObject(i)
                            NetInterface(obj.optString("name", ""), obj.optLong("totalBytes", 0L))
                        }
                        // Most active first so the default selection lands on real
                        // traffic (tun0 while a VPN is up) instead of idle dummies
                        interfaces = list.sortedByDescending { it.totalBytes }
                        if (selected == null || list.none { it.name == selected }) {
                            selected = interfaces.firstOrNull()?.name
                        }
                    }

                    "NET_STATS" -> {
                        rxSpeed = json.optDouble("rxBytesPerSec", 0.0)
                        txSpeed = json.optDouble("txBytesPerSec", 0.0)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Network")

                    InfoItem(label = "Interface", value = selected?.let(::interfaceLabel) ?: "-", highlighted = true)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(label = "Download", value = formatBytes(rxSpeed.toLong()) + "/s")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            InfoItem(label = "Upload", value = formatBytes(txSpeed.toLong()) + "/s")
                        }
                    }
                }
            }

            HorizontalDivider()

            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Interfaces")

                    interfaces.forEach { iface ->
                        val label = interfaceLabel(iface.name)
                        SettingsToggle(
                            label = if (iface.name == selected) "$label ✓" else label,
                            description = formatBytes(iface.totalBytes),
                            default = false,
                            showSwitch = false,
                            sideEffect = { selected = iface.name }
                        )
                    }
                }
            }
        }
    }
}
