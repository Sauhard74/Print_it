package com.example.printer.domain.usecases

import com.example.printer.domain.entities.NetworkPrinter
import com.example.printer.domain.repositories.PrinterDiscoveryRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use case for discovering network printers
 */
class DiscoverNetworkPrintersUseCase(
    private val printerDiscoveryRepository: PrinterDiscoveryRepository
) {
    /**
     * Starts discovery and returns a flow of discovery results
     */
    suspend operator fun invoke(timeoutMs: Long = 30000): Flow<PrinterDiscoveryRepository.DiscoveryResult> {
        return printerDiscoveryRepository.startDiscovery(timeoutMs)
    }

    /**
     * Observes discovered printers
     */
    fun observeDiscoveredPrinters(): Flow<List<NetworkPrinter>> {
        return printerDiscoveryRepository.observeDiscoveredPrinters()
    }

    /**
     * Stops discovery
     */
    suspend fun stopDiscovery() {
        printerDiscoveryRepository.stopDiscovery()
    }
} 