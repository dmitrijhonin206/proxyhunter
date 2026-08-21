package com.proxyhunter.telegram.worker.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Сервис и Compose-экраны не могут напрямую обмениваться состоянием (VpnService — не
// ViewModel), поэтому синхронизируются через общий Hilt-синглтон: ProxyHunterVpnService
// пишет сюда id активного прокси при старте/остановке туннеля, ProxyDetailsViewModel
// читает, чтобы показать корректное состояние кнопки VPN даже если экран был переоткрыт
// уже после запуска туннеля из уведомления или из другого места.
@Singleton
class VpnStateHolder @Inject constructor() {

    private val _activeProxyId = MutableStateFlow<Long?>(null)
    val activeProxyId: StateFlow<Long?> = _activeProxyId.asStateFlow()

    fun setActive(proxyId: Long?) {
        _activeProxyId.value = proxyId
    }
}
