package com.example.printer.domain.usecases

import com.example.printer.domain.entities.PrinterSettings
import com.example.printer.domain.repositories.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Use case for starting the virtual printer service
 * Handles configuration validation and service initialization
 */
class StartPrinterServiceUseCase(
    private val printerService: Any, // Will be replaced with actual PrinterService
    private val settingsRepository: SettingsRepository
) {
    
    /**
     * Starts the printer service with current settings
     * @return Flow of service start results
     */
    operator fun invoke(): Flow<Result> = flow {
        emit(Result.Loading)
        
        try {
            // Get current settings
            val settings = settingsRepository.getPrinterSettings()
            
            // Validate settings
            val validationResult = settings.validate()
            if (validationResult is PrinterSettings.ValidationResult.Error) {
                emit(Result.ValidationError(validationResult.errors))
                return@flow
            }
            
            // TODO: Implement service starting logic when PrinterService is refactored
            emit(Result.Success(
                ServiceInfo(
                    printerName = settings.printerName,
                    port = settings.port,
                    ipAddress = "192.168.1.100", // Placeholder
                    serviceUri = "ipp://192.168.1.100:${settings.port}/",
                    isAdvertised = settings.networkSettings.advertiseService
                )
            ))
            
        } catch (e: Exception) {
            emit(Result.UnexpectedError(e))
        }
    }
    
    /**
     * Results of starting the printer service
     */
    sealed class Result {
        object Loading : Result()
        data class Success(val serviceInfo: ServiceInfo) : Result()
        object AlreadyRunning : Result()
        data class ValidationError(val errors: List<String>) : Result()
        data class ServiceError(val message: String, val exception: Throwable?) : Result()
        data class PortInUse(val port: Int) : Result()
        data class NetworkError(val message: String) : Result()
        data class UnexpectedError(val exception: Throwable) : Result()
    }
    
    /**
     * Information about the started service
     */
    data class ServiceInfo(
        val printerName: String,
        val port: Int,
        val ipAddress: String,
        val serviceUri: String,
        val isAdvertised: Boolean
    )
} 