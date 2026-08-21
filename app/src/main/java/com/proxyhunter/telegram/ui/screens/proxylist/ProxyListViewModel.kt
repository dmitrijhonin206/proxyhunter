package com.proxyhunter.telegram.ui.screens.proxylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxyhunter.telegram.data.local.SettingsRepository
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyFilter
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import com.proxyhunter.telegram.domain.usecase.GenerateTelegramConnectActionUseCase
import com.proxyhunter.telegram.domain.usecase.TelegramConnectAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingConnect(val proxyId: Long, val action: TelegramConnectAction)

data class ProxyListUiState(
    val proxies: List<Proxy> = emptyList(),
    val filter: ProxyFilter = ProxyFilter(),
    val isRefreshing: Boolean = false,
    val isChecking: Boolean = false,
    val pendingConnect: PendingConnect? = null,
    val activeProxyId: Long? = null,
)

// Промежуточное состояние операций (refresh/check/pending-dialog) — объединяется отдельно
// от списка прокси и активного id, чтобы не упираться в arity-лимит combine() для потоков
// с разными типами (kotlinx.coroutines.flow.combine типизированно поддерживает до 5 потоков).
private data class OperationState(
    val isRefreshing: Boolean,
    val isChecking: Boolean,
    val pendingConnect: PendingConnect?,
)

@HiltViewModel
class ProxyListViewModel @Inject constructor(
    private val repository: ProxyRepository,
    private val settings: SettingsRepository,
    private val generateConnectAction: GenerateTelegramConnectActionUseCase,
) : ViewModel() {

    private val filter = MutableStateFlow(ProxyFilter())
    private val isRefreshing = MutableStateFlow(false)
    private val isChecking = MutableStateFlow(false)
    private val pendingConnect = MutableStateFlow<PendingConnect?>(null)

    private val operationState = combine(isRefreshing, isChecking, pendingConnect) { refreshing, checking, pending ->
        OperationState(refreshing, checking, pending)
    }

    val uiState: StateFlow<ProxyListUiState> = combine(
        filter.flatMapLatest { repository.observeProxies(it) },
        filter,
        operationState,
        settings.activeProxyId,
    ) { proxies, currentFilter, ops, activeId ->
        ProxyListUiState(
            proxies = proxies,
            filter = currentFilter,
            isRefreshing = ops.isRefreshing,
            isChecking = ops.isChecking,
            pendingConnect = ops.pendingConnect,
            activeProxyId = activeId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxyListUiState())

    fun updateFilter(newFilter: ProxyFilter) {
        filter.value = newFilter
    }

    fun refreshFromSources() = viewModelScope.launch {
        isRefreshing.value = true
        runCatching { repository.refreshFromSources() }
        isRefreshing.value = false
    }

    fun checkAll() = viewModelScope.launch {
        isChecking.value = true
        runCatching { repository.checkAll() }
        isChecking.value = false
    }

    fun checkSingle(proxyId: Long) = viewModelScope.launch {
        repository.checkSingle(proxyId)
    }

    fun toggleFavorite(proxyId: Long, isFavorite: Boolean) = viewModelScope.launch {
        repository.toggleFavorite(proxyId, isFavorite)
    }

    // Открывает диалог подключения — ЕЩЁ не помечает прокси активным. Активным он
    // становится только когда пользователь реально подтверждает действие (открывает
    // ссылку в Telegram или копирует параметры) — см. confirmUsingProxy. Так CheckWorker
    // не начнёт присматривать за прокси, который пользователь просто посмотрел, но не выбрал.
    fun onUseInTelegram(proxy: Proxy) {
        pendingConnect.value = PendingConnect(proxy.id, generateConnectAction(proxy))
    }

    fun confirmUsingProxy() = viewModelScope.launch {
        val proxyId = pendingConnect.value?.proxyId ?: return@launch
        settings.setActiveProxyId(proxyId)
        pendingConnect.value = null
    }

    fun consumeConnectAction() {
        pendingConnect.value = null
    }
}
