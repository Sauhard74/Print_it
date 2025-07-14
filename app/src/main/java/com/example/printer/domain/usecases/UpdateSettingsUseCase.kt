package com.example.printer.domain.usecases

import com.example.printer.domain.entities.PrinterSettings
import com.example.printer.domain.repositories.SettingsRepository

/**
 * Use case for updating printer settings
 */
class UpdateSettingsUseCase(
    private val settingsRepository: SettingsRepository
) {
    /**
     * Updates printer settings
     */
    suspend operator fun invoke(settings: PrinterSettings): Result<Unit> {
        return try {
            settingsRepository.updatePrinterSettings(settings)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates a specific setting
     */
    suspend fun updateSetting(key: String, value: Any): Result<Unit> {
        return try {
            settingsRepository.updateSetting(key, value)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates app preferences
     */
    suspend fun updateAppPreferences(preferences: SettingsRepository.AppPreferences): Result<Unit> {
        return try {
            settingsRepository.updateAppPreferences(preferences)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 