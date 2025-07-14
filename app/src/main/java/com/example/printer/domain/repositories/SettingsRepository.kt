package com.example.printer.domain.repositories

import com.example.printer.domain.entities.PrinterSettings
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for application settings management
 * Handles storage and retrieval of user preferences and configuration
 */
interface SettingsRepository {
    
    /**
     * Observes printer settings with real-time updates
     * @return Flow of current printer settings
     */
    fun observePrinterSettings(): Flow<PrinterSettings>
    
    /**
     * Gets current printer settings
     * @return Current printer settings
     */
    suspend fun getPrinterSettings(): PrinterSettings
    
    /**
     * Updates printer settings
     * @param settings The new settings to apply
     */
    suspend fun updatePrinterSettings(settings: PrinterSettings)
    
    /**
     * Updates specific printer setting
     * @param key The setting key
     * @param value The setting value
     */
    suspend fun updateSetting(key: String, value: Any)
    
    /**
     * Gets a specific setting value
     * @param key The setting key
     * @param defaultValue Default value if setting not found
     * @return The setting value or default
     */
    suspend fun <T> getSetting(key: String, defaultValue: T): T
    
    /**
     * Resets all settings to defaults
     */
    suspend fun resetToDefaults()
    
    /**
     * Exports settings to JSON string for backup
     * @return JSON representation of settings
     */
    suspend fun exportSettings(): String
    
    /**
     * Imports settings from JSON string
     * @param json JSON representation of settings
     * @return True if import successful, false otherwise
     */
    suspend fun importSettings(json: String): Boolean
    
    /**
     * Gets application preferences
     * @return Current application preferences
     */
    suspend fun getAppPreferences(): AppPreferences
    
    /**
     * Updates application preferences
     * @param preferences New application preferences
     */
    suspend fun updateAppPreferences(preferences: AppPreferences)
    
    /**
     * Application-wide preferences
     */
    data class AppPreferences(
        val enableLogging: Boolean,
        val logLevel: LogLevel,
        val enableCrashReporting: Boolean,
        val enableAnalytics: Boolean,
        val autoStartService: Boolean,
        val showAdvancedSettings: Boolean,
        val enableNotifications: Boolean,
        val themMode: ThemeMode,
        val language: String
    )
    
    /**
     * Logging levels
     */
    enum class LogLevel {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
    
    /**
     * Theme modes
     */
    enum class ThemeMode {
        SYSTEM,
        LIGHT,
        DARK
    }
} 