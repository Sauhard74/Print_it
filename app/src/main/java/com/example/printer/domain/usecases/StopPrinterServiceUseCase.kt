package com.example.printer.domain.usecases

import com.example.printer.printer.PrinterService

/**
 * Use case for stopping the printer service
 */
class StopPrinterServiceUseCase(
    private val printerService: PrinterService
) {
    /**
     * Stops the printer service
     */
    suspend operator fun invoke(): Result<Unit> {
        return try {
            printerService.stopPrinterService()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 