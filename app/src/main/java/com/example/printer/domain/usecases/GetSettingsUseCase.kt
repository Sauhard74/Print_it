package com.example.printer.domain.usecases

import com.example.printer.domain.entities.PrinterSettings
import com.example.printer.domain.repositories.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use case for getting printer settings
 */
class GetSettingsUseCase(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Gets current printer settings
     */
    suspend operator fun invoke(): Result<PrinterSettings> {
        return try {
            val settings = settingsRepository.getPrinterSettings()
            Result.success(settings)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Observes printer settings changes
     */
    fun observeSettings(): Flow<PrinterSettings> {
        return settingsRepository.observePrinterSettings()
    }

    /**
     * Gets a specific setting value
     */
    suspend fun <T> getSetting(key: String, defaultValue: T): Result<T> {
        return try {
            val value = settingsRepository.getSetting(key, defaultValue)
            Result.success(value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets app preferences
     */
    suspend fun getAppPreferences(): Result<SettingsRepository.AppPreferences> {
        return try {
            val preferences = settingsRepository.getAppPreferences()
            Result.success(preferences)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Exports settings to JSON
     */
    suspend fun exportSettings(): Result<String> {
        return try {
            val json = settingsRepository.exportSettings()
            Result.success(json)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 