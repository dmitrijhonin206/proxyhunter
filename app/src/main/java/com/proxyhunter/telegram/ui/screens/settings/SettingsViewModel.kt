package com.proxyhunter.telegram.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxyhunter.telegram.data.export.ExportFormat
import com.proxyhunter.telegram.data.export.ProxyExportSerializer
import com.proxyhunter.telegram.data.local.SettingsRepository
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import com.proxyhunter.telegram.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val parsingIntervalHours: Int = SettingsRepository.DEFAULT_PARSING_INTERVAL_HOURS,
    val checkIntervalHours: Int = SettingsRepository.DEFAULT_CHECK_INTERVAL_HOURS,
    val checkTimeoutMs: Int = SettingsRepository.DEFAULT_CHECK_TIMEOUT_MS,
    val isDarkTheme: Boolean? = null,
    val customSourceUrls: List<String> = emptyList(),
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    // Одноразовое сообщение об итоге экспорта/импорта — показывается один раз (снэкбар/текст)
    // и сбрасывается через consumeExportImportMessage(), а не хранится как постоянное состояние.
    val exportImportMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val workScheduler: WorkScheduler,
    private val repository: ProxyRepository,
    private val serializer: ProxyExportSerializer,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val exportImportState = MutableStateFlow(ExportImportState())

    // combine() типизированно поддерживает до 5 разнотипных потоков; шестой (exportImportState)
    // объединяется отдельным вызовом поверх уже собранных пяти — как и в ProxyListViewModel,
    // vararg-версия combine() здесь не подходит: она требует одинаковый тип для всех потоков,
    // а тут Int/Int/Int/Boolean?/List<String>/ExportImportState вперемешку.
    private val settingsState = combine(
        settings.parsingIntervalHours,
        settings.checkIntervalHours,
        settings.checkTimeoutMs,
        settings.isDarkTheme,
        settings.customSourceUrls,
    ) { parsing, check, timeout, dark, sources ->
        SettingsFieldsState(parsing, check, timeout, dark, sources)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsState,
        exportImportState,
    ) { fields, exportImport ->
        SettingsUiState(
            parsingIntervalHours = fields.parsingIntervalHours,
            checkIntervalHours = fields.checkIntervalHours,
            checkTimeoutMs = fields.checkTimeoutMs,
            isDarkTheme = fields.isDarkTheme,
            customSourceUrls = fields.customSourceUrls,
            isExporting = exportImport.isExporting,
            isImporting = exportImport.isImporting,
            exportImportMessage = exportImport.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    // Смена интервала сразу переставляет периодическую работу в WorkManager
    // (ExistingPeriodicWorkPolicy.UPDATE в WorkScheduler), без перезапуска приложения.
    fun setParsingInterval(hours: Int) = viewModelScope.launch {
        settings.setParsingIntervalHours(hours)
        workScheduler.schedulePeriodicParsing(hours)
    }

    fun setCheckInterval(hours: Int) = viewModelScope.launch {
        settings.setCheckIntervalHours(hours)
        workScheduler.schedulePeriodicCheck(hours)
    }

    fun setCheckTimeout(ms: Int) = viewModelScope.launch {
        settings.setCheckTimeoutMs(ms)
    }

    fun setDarkTheme(value: Boolean?) = viewModelScope.launch {
        settings.setDarkTheme(value)
    }

    fun addCustomSource(url: String) = viewModelScope.launch {
        if (url.isNotBlank()) settings.addCustomSourceUrl(url.trim())
    }

    // uri приходит из ActivityResultContracts.CreateDocument(mimeType) на стороне экрана —
    // пользователь сам выбирает имя файла и папку через системный диалог (Storage Access
    // Framework), приложению не нужны разрешения на хранилище.
    fun exportProxies(uri: Uri, format: ExportFormat) = viewModelScope.launch {
        exportImportState.update { it.copy(isExporting = true) }

        val result = runCatching {
            val proxies = repository.getAllProxies()
            val content = when (format) {
                ExportFormat.JSON -> serializer.toJson(proxies)
                ExportFormat.CSV -> serializer.toCsv(proxies)
            }
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Не удалось открыть файл для записи")
            proxies.size
        }

        val message = result.fold(
            onSuccess = { count -> "Экспортировано прокси: $count" },
            onFailure = { "Не удалось экспортировать: ${it.message}" },
        )
        exportImportState.update { it.copy(isExporting = false, message = message) }
    }

    // uri приходит из ActivityResultContracts.OpenDocument() — формат определяется по
    // содержимому файла (JSON начинается с '['), а не по расширению: content:// URI из
    // системного пикера не всегда даёт удобный доступ к оригинальному имени файла.
    fun importProxies(uri: Uri) = viewModelScope.launch {
        exportImportState.update { it.copy(isImporting = true) }

        val result = runCatching {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Не удалось открыть файл для чтения")

            val parsed = if (content.trim().startsWith("[")) {
                serializer.fromJson(content)
            } else {
                serializer.fromCsv(content)
            }

            val imported = repository.importProxies(parsed.proxies)
            imported to parsed.skippedRows
        }

        val message = result.fold(
            onSuccess = { (imported, skipped) ->
                if (skipped > 0) {
                    "Импортировано: $imported, пропущено (некорректные строки): $skipped"
                } else {
                    "Импортировано прокси: $imported"
                }
            },
            onFailure = { "Не удалось импортировать: ${it.message}" },
        )
        exportImportState.update { it.copy(isImporting = false, message = message) }
    }

    fun consumeExportImportMessage() {
        exportImportState.update { it.copy(message = null) }
    }
}

private data class SettingsFieldsState(
    val parsingIntervalHours: Int,
    val checkIntervalHours: Int,
    val checkTimeoutMs: Int,
    val isDarkTheme: Boolean?,
    val customSourceUrls: List<String>,
)

private data class ExportImportState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
)
