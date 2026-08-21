package com.proxyhunter.telegram.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.proxyhunter.telegram.data.export.ExportFormat

private val INTERVAL_OPTIONS_HOURS = listOf(1, 2, 6, 12, 24)
private val TIMEOUT_OPTIONS_MS = listOf(5000, 8000, 10000)
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var newSourceUrl by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // Экспорт: пользователь сам выбирает имя файла и папку через системный диалог
    // (Storage Access Framework) — приложению не нужны разрешения на хранилище.
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportProxies(it, ExportFormat.JSON) } }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.exportProxies(it, ExportFormat.CSV) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importProxies(it) } }

    LaunchedEffect(state.exportImportMessage) {
        state.exportImportMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeExportImportMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                SettingsSection(title = "Автообновление") {
                    ChipRow(
                        label = "Парсинг каждые",
                        options = INTERVAL_OPTIONS_HOURS,
                        selected = state.parsingIntervalHours,
                        formatOption = { "$it ч" },
                        onSelect = viewModel::setParsingInterval,
                    )
                    Spacer(Modifier.height(12.dp))
                    ChipRow(
                        label = "Проверка каждые",
                        options = INTERVAL_OPTIONS_HOURS,
                        selected = state.checkIntervalHours,
                        formatOption = { "$it ч" },
                        onSelect = viewModel::setCheckInterval,
                    )
                }
            }

            item {
                SettingsSection(title = "Таймаут проверки") {
                    ChipRow(
                        label = "Таймаут",
                        options = TIMEOUT_OPTIONS_MS,
                        selected = state.checkTimeoutMs,
                        formatOption = { "${it / 1000} с" },
                        onSelect = viewModel::setCheckTimeout,
                    )
                }
            }

            item {
                SettingsSection(title = "Тема") {
                    Column {
                        ThemeOptionRow("Системная", state.isDarkTheme == null) { viewModel.setDarkTheme(null) }
                        ThemeOptionRow("Светлая", state.isDarkTheme == false) { viewModel.setDarkTheme(false) }
                        ThemeOptionRow("Тёмная", state.isDarkTheme == true) { viewModel.setDarkTheme(true) }
                    }
                }
            }

            item {
                SettingsSection(title = "Источники парсинга") {
                    Text(
                        "Добавьте URL со списком прокси (построчно ip:port или JSON-массив)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newSourceUrl,
                            onValueChange = { newSourceUrl = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("https://…") },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.addCustomSource(newSourceUrl)
                            newSourceUrl = ""
                        }) {
                            Text("Добавить")
                        }
                    }
                }
            }

            items(state.customSourceUrls) { url ->
                Text(url, style = MaterialTheme.typography.bodySmall)
            }

            item {
                SettingsSection(title = "Экспорт / импорт списка") {
                    Text(
                        "Экспорт сохраняет весь список прокси в файл. Импорт добавляет прокси " +
                            "из файла к уже существующим (дубликаты по IP:порту пропускаются).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportJsonLauncher.launch("proxies.json") },
                            enabled = !state.isExporting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Экспорт JSON")
                        }
                        OutlinedButton(
                            onClick = { exportCsvLauncher.launch("proxies.csv") },
                            enabled = !state.isExporting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Экспорт CSV")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
                        enabled = !state.isImporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isImporting) "Импорт…" else "Импортировать список")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    formatOption: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(formatOption(option)) },
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
