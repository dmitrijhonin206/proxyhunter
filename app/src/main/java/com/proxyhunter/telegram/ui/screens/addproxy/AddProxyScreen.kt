package com.proxyhunter.telegram.ui.screens.addproxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.proxyhunter.telegram.domain.model.ProxyProtocol

// Экран ручного добавления прокси ("возможность вручную добавить прокси" из ТЗ).
// Поля для логина/пароля показываются для SOCKS5/HTTP/HTTPS, поле секрета — для MTProto;
// оба необязательны (публичные прокси часто без авторизации / без секрета).
@Composable
fun AddProxyScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddProxyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить прокси") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtocolSelector(selected = state.protocol, onSelect = viewModel::setProtocol)

            OutlinedTextField(
                value = state.ip,
                onValueChange = viewModel::setIp,
                label = { Text("IP-адрес") },
                placeholder = { Text("203.0.113.10") },
                isError = state.showErrors && state.validation.ipError != null,
                supportingText = { ErrorOrHint(state.showErrors, state.validation.ipError) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.port,
                onValueChange = viewModel::setPort,
                label = { Text("Порт") },
                placeholder = { Text("1080") },
                isError = state.showErrors && state.validation.portError != null,
                supportingText = { ErrorOrHint(state.showErrors, state.validation.portError) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.protocol == ProxyProtocol.MTPROTO) {
                OutlinedTextField(
                    value = state.secret,
                    onValueChange = viewModel::setSecret,
                    label = { Text("Секрет (необязательно)") },
                    placeholder = { Text("ee1234… / dd1234… / обычный hex") },
                    isError = state.showErrors && state.validation.secretError != null,
                    supportingText = {
                        ErrorOrHint(
                            state.showErrors,
                            state.validation.secretError,
                            hint = "Оставьте пустым, если у прокси нет секрета",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::setUsername,
                    label = { Text("Логин (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    label = { Text("Пароль (необязательно)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSaving) "Сохранение…" else "Сохранить")
            }
        }
    }
}

@Composable
private fun ProtocolSelector(selected: ProxyProtocol, onSelect: (ProxyProtocol) -> Unit) {
    Column {
        Text("Протокол", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProxyProtocol.entries.forEach { protocol ->
                FilterChip(
                    selected = selected == protocol,
                    onClick = { onSelect(protocol) },
                    label = { Text(protocol.name) },
                )
            }
        }
    }
}

@Composable
private fun ErrorOrHint(showErrors: Boolean, error: String?, hint: String? = null) {
    val text = if (showErrors && error != null) error else hint
    if (text != null) Text(text)
}
