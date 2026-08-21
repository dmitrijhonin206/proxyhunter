@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyhunter.telegram.ui.screens.proxylist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyStatus
import com.proxyhunter.telegram.domain.usecase.TelegramConnectAction

// Главный экран: список прокси-карточек с индикацией статуса,
// панель фильтров сверху, кнопки "Обновить" (парсинг) и "Проверить все".
@Composable
fun ProxyListScreen(
    onOpenDetails: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onAddProxy: () -> Unit,
    viewModel: ProxyListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    state.pendingConnect?.let { pending ->
        ConnectActionDialog(
            action = pending.action,
            onDismiss = viewModel::consumeConnectAction,
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                viewModel.confirmUsingProxy()
            },
            onOpenLink = { uri ->
                openTelegramLink(context, uri)
                viewModel.confirmUsingProxy()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ProxyHunter for Telegram") },
                actions = {
                    IconButton(onClick = viewModel::refreshFromSources) {
                        if (state.isRefreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Refresh, contentDescription = "Обновить список")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProxy) {
                Icon(Icons.Default.Add, contentDescription = "Добавить прокси вручную")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            FilterBar(
                filter = state.filter,
                onFilterChange = viewModel::updateFilter,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::checkAll, enabled = !state.isChecking, modifier = Modifier.weight(1f)) {
                    Text(if (state.isChecking) "Проверка…" else "Проверить все")
                }
            }

            if (state.proxies.isEmpty()) {
                EmptyState(isRefreshing = state.isRefreshing)
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.proxies, key = { it.id }) { proxy ->
                        ProxyCard(
                            proxy = proxy,
                            isActive = proxy.id == state.activeProxyId,
                            onClick = { onOpenDetails(proxy.id) },
                            onFavoriteToggle = { viewModel.toggleFavorite(proxy.id, !proxy.isFavorite) },
                            onUseInTelegram = { viewModel.onUseInTelegram(proxy) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyCard(
    proxy: Proxy,
    isActive: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onUseInTelegram: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = proxy.latestStatus)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${proxy.ip}:${proxy.port}", style = MaterialTheme.typography.bodyLarge)
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        ActiveBadge()
                    }
                }
                Text(
                    "${proxy.protocol}" + (proxy.country?.let { " · $it" } ?: "") +
                        (proxy.latestLatencyMs?.let { " · ${it}ms" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFavoriteToggle) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = "Избранное")
            }
            Button(onClick = onUseInTelegram, enabled = proxy.latestStatus == ProxyStatus.WORKING) {
                Text("Использовать")
            }
        }
    }
}

// Метка "Используется" на карточке прокси, который пользователь подтвердил как активный
// (нажал "Открыть в Telegram"/"Скопировать параметры" в диалоге подключения). Это тот же
// activeProxyId, за которым следит CheckWorker для автопереключения при падении прокси.
@Composable
private fun ActiveBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "Используется",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StatusDot(status: ProxyStatus) {
    val color = when (status) {
        ProxyStatus.WORKING -> MaterialTheme.colorScheme.tertiary       // зелёный по теме M3
        ProxyStatus.FAILED, ProxyStatus.TIMEOUT -> MaterialTheme.colorScheme.error
        ProxyStatus.NOT_CHECKED -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color, shape = CircleShape),
    )
}

@Composable
private fun EmptyState(isRefreshing: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (isRefreshing) "Загружаем список прокси…" else "Список пуст",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Нажмите «Обновить», чтобы загрузить прокси из встроенных источников.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectActionDialog(
    action: TelegramConnectAction,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключение в Telegram") },
        text = {
            when (action) {
                is TelegramConnectAction.DeepLink -> Text("Откройте прокси прямо в Telegram одним тапом.")
                is TelegramConnectAction.ManualInstruction -> Column {
                    action.steps.forEachIndexed { i, step -> Text("${i + 1}. $step") }
                }
            }
        },
        confirmButton = {
            when (action) {
                is TelegramConnectAction.DeepLink -> TextButton(onClick = { onOpenLink(action.uri) }) {
                    Text("Открыть в Telegram")
                }
                is TelegramConnectAction.ManualInstruction -> TextButton(onClick = { onCopy(action.copyText) }) {
                    Text("Скопировать параметры")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun openTelegramLink(context: Context, uri: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }
}
