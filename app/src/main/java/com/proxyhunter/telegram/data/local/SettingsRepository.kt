package com.proxyhunter.telegram.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "proxyhunter_settings")

private object Keys {
    val ACTIVE_PROXY_ID = longPreferencesKey("active_proxy_id")
    val PARSING_INTERVAL_HOURS = intPreferencesKey("parsing_interval_hours")
    val CHECK_INTERVAL_HOURS = intPreferencesKey("check_interval_hours")
    val CHECK_TIMEOUT_MS = intPreferencesKey("check_timeout_ms")
    val DARK_THEME = booleanPreferencesKey("dark_theme")   // null = follow system
    val CUSTOM_SOURCE_URLS = stringPreferencesKey("custom_source_urls") // ";"-separated
    val HAS_SEEN_RISK_WARNING = booleanPreferencesKey("has_seen_risk_warning")
}

// Настройки пользователя: интервалы фонового парсинга/проверки, таймауты,
// тема оформления, активный (используемый в Telegram) прокси, пользовательские источники.
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val parsingIntervalHours: Flow<Int> =
        dataStore.data.map { it[Keys.PARSING_INTERVAL_HOURS] ?: DEFAULT_PARSING_INTERVAL_HOURS }

    val checkIntervalHours: Flow<Int> =
        dataStore.data.map { it[Keys.CHECK_INTERVAL_HOURS] ?: DEFAULT_CHECK_INTERVAL_HOURS }

    val checkTimeoutMs: Flow<Int> =
        dataStore.data.map { it[Keys.CHECK_TIMEOUT_MS] ?: DEFAULT_CHECK_TIMEOUT_MS }

    val isDarkTheme: Flow<Boolean?> = dataStore.data.map { it[Keys.DARK_THEME] }

    val activeProxyId: Flow<Long?> = dataStore.data.map { it[Keys.ACTIVE_PROXY_ID] }

    val hasSeenRiskWarning: Flow<Boolean> = dataStore.data.map { it[Keys.HAS_SEEN_RISK_WARNING] ?: false }

    suspend fun setHasSeenRiskWarning() = dataStore.edit { it[Keys.HAS_SEEN_RISK_WARNING] = true }

    val customSourceUrls: Flow<List<String>> = dataStore.data.map {
        it[Keys.CUSTOM_SOURCE_URLS]?.split(";")?.filter { url -> url.isNotBlank() } ?: emptyList()
    }

    suspend fun getActiveProxyId(): Long? = dataStore.data.first()[Keys.ACTIVE_PROXY_ID]

    suspend fun setActiveProxyId(id: Long?) = dataStore.edit { prefs ->
        if (id == null) prefs.remove(Keys.ACTIVE_PROXY_ID) else prefs[Keys.ACTIVE_PROXY_ID] = id
    }

    suspend fun setParsingIntervalHours(hours: Int) = dataStore.edit { it[Keys.PARSING_INTERVAL_HOURS] = hours }

    suspend fun setCheckIntervalHours(hours: Int) = dataStore.edit { it[Keys.CHECK_INTERVAL_HOURS] = hours }

    suspend fun setCheckTimeoutMs(ms: Int) = dataStore.edit { it[Keys.CHECK_TIMEOUT_MS] = ms }

    suspend fun setDarkTheme(value: Boolean?) = dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.DARK_THEME) else prefs[Keys.DARK_THEME] = value
    }

    suspend fun addCustomSourceUrl(url: String) = dataStore.edit { prefs ->
        val current = prefs[Keys.CUSTOM_SOURCE_URLS]?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
        prefs[Keys.CUSTOM_SOURCE_URLS] = (current + url).distinct().joinToString(";")
    }

    companion object {
        const val DEFAULT_PARSING_INTERVAL_HOURS = 6
        const val DEFAULT_CHECK_INTERVAL_HOURS = 2
        const val DEFAULT_CHECK_TIMEOUT_MS = 8000
    }
}
