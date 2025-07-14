package com.example.printer.domain.usecases

import com.example.printer.domain.entities.NetworkPrinter
import com.example.printer.domain.repositories.PrinterDiscoveryRepository
import com.hp.jipp.encoding.AttributeGroup

/**
 * Use case for getting printer attributes
 */
class GetPrinterAttributesUseCase(
    private val printerDiscoveryRepository: PrinterDiscoveryRepository
) {
    /**
     * Queries printer attributes
     */
    suspend operator fun invoke(printer: NetworkPrinter): Result<List<AttributeGroup>?> {
        return try {
            val attributes = printerDiscoveryRepository.queryPrinterAttributes(printer)
            Result.success(attributes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets cached printer attributes
     */
    suspend fun getCachedAttributes(printerId: String): List<AttributeGroup>? {
        return printerDiscoveryRepository.getCachedPrinterAttributes(printerId)
    }

    /**
     * Gets printer capabilities
     */
    suspend fun getPrinterCapabilities(printer: NetworkPrinter): Result<PrinterDiscoveryRepository.PrinterCapabilities?> {
        return try {
            val capabilities = printerDiscoveryRepository.getPrinterCapabilities(printer)
            Result.success(capabilities)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 