package com.proxyhunter.telegram.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Обязательный экран при первом запуске: предупреждение о рисках публичных прокси.
// Пользователь не может попасть в основной UI, не подтвердив ознакомление.
@Composable
fun RiskWarningScreen(onAccept: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Важно перед началом", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Приложение агрегирует публичные прокси-серверы из открытых источников. " +
                "Такие серверы контролируются третьими лицами, а не разработчиком приложения.\n\n" +
                "• Не передавайте через публичные прокси пароли, коды подтверждения и другие личные данные.\n" +
                "• Используйте прокси только для обхода сетевых ограничений в официальном клиенте Telegram.\n" +
                "• Приложение не гарантирует конфиденциальность трафика, проходящего через сторонние серверы.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text("Понимаю и продолжаю")
        }
    }
}
