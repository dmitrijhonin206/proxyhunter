package com.proxyhunter.telegram.ui.screens.addproxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import com.proxyhunter.telegram.domain.usecase.ProxyFormValidation
import com.proxyhunter.telegram.domain.usecase.ValidateProxyInputUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddProxyUiState(
    val ip: String = "",
    val port: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val username: String = "",
    val password: String = "",
    val secret: String = "",
    val validation: ProxyFormValidation = ProxyFormValidation(),
    // Ошибки не показываются, пока пользователь не попытался сохранить — иначе поля
    // подсвечиваются красным ещё до того, как он вообще начал печатать.
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class AddProxyViewModel @Inject constructor(
    private val repository: ProxyRepository,
    private val validate: ValidateProxyInputUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddProxyUiState())
    val uiState: StateFlow<AddProxyUiState> = _uiState.asStateFlow()

    fun setIp(value: String) = updateAndRevalidate { it.copy(ip = value) }

    fun setPort(value: String) = updateAndRevalidate { it.copy(port = value.filter(Char::isDigit)) }

    fun setProtocol(value: ProxyProtocol) = updateAndRevalidate { it.copy(protocol = value) }

    fun setSecret(value: String) = updateAndRevalidate { it.copy(secret = value) }

    fun setUsername(value: String) = _uiState.update { it.copy(username = value) }

    fun setPassword(value: String) = _uiState.update { it.copy(password = value) }

    private fun updateAndRevalidate(transform: (AddProxyUiState) -> AddProxyUiState) {
        _uiState.update { current ->
            val next = transform(current)
            next.copy(validation = validate(next.ip, next.port, next.protocol, next.secret))
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.validation.isValid) {
            _uiState.update { it.copy(showErrors = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            repository.addCustomProxy(
                Proxy(
                    ip = state.ip.trim(),
                    port = state.port.toInt(),
                    protocol = state.protocol,
                    username = state.username.takeIf { it.isNotBlank() && state.protocol != ProxyProtocol.MTPROTO },
                    password = state.password.takeIf { it.isNotBlank() && state.protocol != ProxyProtocol.MTPROTO },
                    mtprotoSecret = state.secret.takeIf { it.isNotBlank() && state.protocol == ProxyProtocol.MTPROTO },
                    sourceUrl = "manual",
                    addedAt = System.currentTimeMillis(),
                    isCustom = true,
                ),
            )
            _uiState.update { it.copy(isSaving = false, saved = true) }
        }
    }
}
