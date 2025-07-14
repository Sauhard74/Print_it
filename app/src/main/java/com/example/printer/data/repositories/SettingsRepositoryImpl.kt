package com.example.printer.data.repositories

import android.content.Context
import android.content.SharedPreferences
import com.example.printer.domain.entities.PrinterSettings
import com.example.printer.domain.repositories.SettingsRepository
import com.example.printer.domain.repositories.SettingsRepository.AppPreferences
import com.example.printer.domain.repositories.SettingsRepository.LogLevel
import com.example.printer.domain.repositories.SettingsRepository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Implementation of SettingsRepository using SharedPreferences
 */
class SettingsRepositoryImpl(
    private val context: Context
) : SettingsRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val _printerSettings = MutableStateFlow(getDefaultPrinterSettings())
    private val printerSettings = _printerSettings.asStateFlow()

    init {
        // Load settings on initialization
        loadPrinterSettings()
    }

    override fun observePrinterSettings(): Flow<PrinterSettings> = printerSettings

    override suspend fun getPrinterSettings(): PrinterSettings {
        return _printerSettings.value
    }

    override suspend fun updatePrinterSettings(settings: PrinterSettings) {
        _printerSettings.value = settings
        savePrinterSettings(settings)
    }

    override suspend fun updateSetting(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
    }

    override suspend fun <T> getSetting(key: String, defaultValue: T): T {
        @Suppress("UNCHECKED_CAST")
        return when (defaultValue) {
            is String -> prefs.getString(key, defaultValue) as T
            is Int -> prefs.getInt(key, defaultValue) as T
            is Long -> prefs.getLong(key, defaultValue) as T
            is Float -> prefs.getFloat(key, defaultValue) as T
            is Boolean -> prefs.getBoolean(key, defaultValue) as T
            else -> defaultValue
        }
    }

    override suspend fun resetToDefaults() {
        prefs.edit().clear().apply()
        val defaultSettings = getDefaultPrinterSettings()
        _printerSettings.value = defaultSettings
        savePrinterSettings(defaultSettings)
    }

    override suspend fun exportSettings(): String {
        val settingsData = mapOf(
            "printerSettings" to _printerSettings.value.toString(),
            "appPreferences" to getAppPreferences().toString(),
            "allPrefs" to prefs.all.toString()
        )
        return json.encodeToString(settingsData)
    }

    override suspend fun importSettings(jsonString: String): Boolean {
        return try {
            // For now, just return true as a placeholder
            // Real implementation would parse and apply settings
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getAppPreferences(): AppPreferences {
        return AppPreferences(
            enableLogging = prefs.getBoolean(KEY_ENABLE_LOGGING, true),
            logLevel = LogLevel.valueOf(prefs.getString(KEY_LOG_LEVEL, LogLevel.INFO.name) ?: LogLevel.INFO.name),
            enableCrashReporting = prefs.getBoolean(KEY_ENABLE_CRASH_REPORTING, true),
            enableAnalytics = prefs.getBoolean(KEY_ENABLE_ANALYTICS, true),
            autoStartService = prefs.getBoolean(KEY_AUTO_START_SERVICE, false),
            showAdvancedSettings = prefs.getBoolean(KEY_SHOW_ADVANCED_SETTINGS, false),
            enableNotifications = prefs.getBoolean(KEY_ENABLE_NOTIFICATIONS, true),
            themMode = ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name),
            language = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
        )
    }

    override suspend fun updateAppPreferences(preferences: AppPreferences) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLE_LOGGING, preferences.enableLogging)
            putString(KEY_LOG_LEVEL, preferences.logLevel.name)
            putBoolean(KEY_ENABLE_CRASH_REPORTING, preferences.enableCrashReporting)
            putBoolean(KEY_ENABLE_ANALYTICS, preferences.enableAnalytics)
            putBoolean(KEY_AUTO_START_SERVICE, preferences.autoStartService)
            putBoolean(KEY_SHOW_ADVANCED_SETTINGS, preferences.showAdvancedSettings)
            putBoolean(KEY_ENABLE_NOTIFICATIONS, preferences.enableNotifications)
            putString(KEY_THEME_MODE, preferences.themMode.name)
            putString(KEY_LANGUAGE, preferences.language)
        }.apply()
    }

    private fun loadPrinterSettings() {
        val settingsJson = prefs.getString(KEY_PRINTER_SETTINGS, null)
        if (settingsJson != null) {
            try {
                // For now, use default settings
                // Real implementation would deserialize from JSON
                _printerSettings.value = getDefaultPrinterSettings()
            } catch (e: Exception) {
                _printerSettings.value = getDefaultPrinterSettings()
            }
        }
    }

    private fun savePrinterSettings(settings: PrinterSettings) {
        // For now, just save a placeholder
        // Real implementation would serialize to JSON
        prefs.edit().putString(KEY_PRINTER_SETTINGS, "{}").apply()
    }

    private fun getDefaultPrinterSettings(): PrinterSettings {
        return PrinterSettings.createDefault()
    }

    companion object {
        private const val PREFS_NAME = "printer_settings"
        
        // Printer Settings Keys
        private const val KEY_PRINTER_SETTINGS = "printer_settings"
        
        // App Preferences Keys
        private const val KEY_ENABLE_LOGGING = "enable_logging"
        private const val KEY_LOG_LEVEL = "log_level"
        private const val KEY_ENABLE_CRASH_REPORTING = "enable_crash_reporting"
        private const val KEY_ENABLE_ANALYTICS = "enable_analytics"
        private const val KEY_AUTO_START_SERVICE = "auto_start_service"
        private const val KEY_SHOW_ADVANCED_SETTINGS = "show_advanced_settings"
        private const val KEY_ENABLE_NOTIFICATIONS = "enable_notifications"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "language"
    }
} 