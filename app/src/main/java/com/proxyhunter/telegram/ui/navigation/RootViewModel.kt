package com.proxyhunter.telegram.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxyhunter.telegram.data.local.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val hasSeenRiskWarning: Flow<Boolean> = settings.hasSeenRiskWarning

    fun markRiskWarningSeen() = viewModelScope.launch {
        settings.setHasSeenRiskWarning()
    }
}
