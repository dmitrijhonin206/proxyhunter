package com.proxyhunter.telegram

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxyhunter.telegram.data.local.SettingsRepository
import com.proxyhunter.telegram.ui.navigation.ProxyHunterNavHost
import com.proxyhunter.telegram.ui.theme.ProxyHunterTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* результат не критичен для базового функционала */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // POST_NOTIFICATIONS нужен на Android 13+ для уведомлений о завершении парсинга
        // и о падении активного прокси (см. worker/ProxyHunterNotifier).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val isDarkTheme by themeViewModel.isDarkTheme.collectAsState()
            ProxyHunterTheme(isDarkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProxyHunterNavHost()
                }
            }
        }
    }
}

// Отдельная лёгкая ViewModel только для темы — читается на самом верхнем уровне (MainActivity),
// поэтому не хотим тянуть сюда весь RootViewModel с его логикой онбординга.
@HiltViewModel
class ThemeViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {
    val isDarkTheme: StateFlow<Boolean?> = settings.isDarkTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
