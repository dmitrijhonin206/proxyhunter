package com.proxyhunter.telegram.ui.screens.details

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.proxyhunter.telegram.domain.model.CheckResult
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.model.ProxyStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Экран деталей: полная информация о прокси, график истории латентности,
// кнопки действий (перепроверить, избранное, VPN-режим). "Использовать в Telegram"
// переиспользует тот же диалог, что и на списке — оставлено на ProxyListScreen намеренно,
// чтобы не дублировать логику генерации ссылки; сюда можно прокинуть тот же callback при желании.
@Composable
fun ProxyDetailsScreen(
    proxyId: Long,
    onBack: () -> Unit,
    viewModel: ProxyDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val proxy = state.proxy
    val context = LocalContext.current

    // Системный диалог согласия на VPN (VpnService.prepare()) — Android обязан показать
    // его явно перед первым запуском туннеля; если согласие уже дано ранее, prepare()
    // вернёт null и диалог не появится вовсе.
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startVpn()
    }

    fun requestVpnStart() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) vpnPrepareLauncher.launch(prepareIntent) else viewModel.startVpn()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(proxy?.let { "${it.ip}:${it.port}" } ?: "Загрузка…") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            if (proxy?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Избранное",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (proxy == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            InfoRow("Протокол", proxy.protocol.name)
            InfoRow("Статус", statusLabel(proxy.latestStatus))
            InfoRow("Страна", proxy.country ?: "—")
            InfoRow("Последняя проверка", proxy.lastCheckedAt?.let { formatTime(it) } ?: "Ещё не проверялся")
            InfoRow("Источник", proxy.sourceUrl)

            Spacer(Modifier.height(24.dp))
            Text("История скорости", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SpeedChart(history = state.history)

            Spacer(Modifier.height(24.dp))
            Button(onClick = viewModel::recheck, modifier = Modifier.fillMaxWidth()) {
                Text("Проверить снова")
            }

            Spacer(Modifier.height(24.dp))
            VpnSection(
                proxy = proxy,
                isVpnActive = state.isVpnActive,
                onStart = { requestVpnStart() },
                onStop = viewModel::stopVpn,
            )
        }
    }
}

// VPN-режим реально работает только для SOCKS5 (см. докстринг TunnelEngine): HTTP-прокси
// не поддерживает UDP-релей через CONNECT в принципе, а MTProto — это Telegram-специфичный
// прикладной релей, а не общий IP/SOCKS-шлюз. Для остальных протоколов кнопка скрыта, а не
// просто задизейблена — задизейбленная кнопка без объяснения читалась бы как "баг", а не
// как архитектурное ограничение.
@Composable
private fun VpnSection(
    proxy: Proxy,
    isVpnActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column {
        Text("VPN-режим", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (proxy.protocol != ProxyProtocol.SOCKS5) {
            Text(
                "Доступно только для SOCKS5-прокси. Для ${proxy.protocol.name} используйте " +
                    "настройку прокси прямо в Telegram (кнопка «Использовать» в списке).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Text(
            "Направляет UDP-трафик устройства через этот прокси на системном уровне " +
                "(не только Telegram). TCP-трафик сейчас не поддерживается VPN-режимом.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (isVpnActive) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Остановить VPN")
            }
        } else {
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Запустить VPN через этот прокси")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// Простой линейный график латентности по последним проверкам — без сторонних чарт-либ,
// достаточно для MVP. NOT_CHECKED/FAILED точки не рисуются (нет latencyMs), только тренд WORKING.
@Composable
private fun SpeedChart(history: List<CheckResult>) {
    val points = history.asReversed().mapNotNull { it.latencyMs }

    if (points.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Пока нет данных о скорости", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val max = (points.maxOrNull() ?: 1).coerceAtLeast(1)

    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        if (points.size < 2) return@Canvas
        val stepX = size.width / (points.size - 1)
        val path = points.mapIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value.toFloat() / max) * size.height
            Offset(x, y)
        }
        for (i in 0 until path.size - 1) {
            drawLine(
                color = lineColor,
                start = path[i],
                end = path[i + 1],
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun statusLabel(status: ProxyStatus) = when (status) {
    ProxyStatus.WORKING -> "Рабочий"
    ProxyStatus.FAILED -> "Не рабочий"
    ProxyStatus.TIMEOUT -> "Таймаут"
    ProxyStatus.NOT_CHECKED -> "Не проверен"
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
