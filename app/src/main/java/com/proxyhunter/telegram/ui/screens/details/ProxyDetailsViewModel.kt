package com.proxyhunter.telegram.ui.screens.details

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxyhunter.telegram.domain.model.CheckResult
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import com.proxyhunter.telegram.worker.vpn.ProxyHunterVpnService
import com.proxyhunter.telegram.worker.vpn.VpnStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProxyDetailsUiState(
    val proxy: Proxy? = null,
    val history: List<CheckResult> = emptyList(),
    val isVpnActive: Boolean = false,
)

@HiltViewModel
class ProxyDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProxyRepository,
    private val vpnStateHolder: VpnStateHolder,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val proxyId: Long = checkNotNull(savedStateHandle["proxyId"])

    val uiState: StateFlow<ProxyDetailsUiState> = combine(
        repository.observeProxy(proxyId),
        repository.observeCheckHistory(proxyId),
        vpnStateHolder.activeProxyId,
    ) { proxy, history, activeVpnProxyId ->
        ProxyDetailsUiState(proxy, history, isVpnActive = activeVpnProxyId == proxyId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxyDetailsUiState())

    fun recheck() = viewModelScope.launch {
        repository.checkSingle(proxyId)
    }

    fun toggleFavorite() = viewModelScope.launch {
        val current = uiState.value.proxy ?: return@launch
        repository.toggleFavorite(proxyId, !current.isFavorite)
    }

    // Вызывается из экрана ПОСЛЕ того, как получено согласие пользователя на VPN
    // (VpnService.prepare() либо уже был удовлетворён ранее, либо подтверждён через
    // системный диалог) — сам запрос согласия требует Activity-контекста и живёт в
    // Composable, не в ViewModel.
    fun startVpn() {
        val intent = Intent(appContext, ProxyHunterVpnService::class.java)
            .setAction(ProxyHunterVpnService.ACTION_START)
            .putExtra(ProxyHunterVpnService.EXTRA_PROXY_ID, proxyId)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopVpn() {
        val intent = Intent(appContext, ProxyHunterVpnService::class.java)
            .setAction(ProxyHunterVpnService.ACTION_STOP)
        ContextCompat.startForegroundService(appContext, intent)
    }
}
