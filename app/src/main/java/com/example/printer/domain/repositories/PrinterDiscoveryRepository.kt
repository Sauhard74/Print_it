package com.example.printer.domain.repositories

import com.example.printer.domain.entities.NetworkPrinter
import com.hp.jipp.encoding.AttributeGroup
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for network printer discovery
 * Handles discovery and querying of network printers
 */
interface PrinterDiscoveryRepository {
    
    /**
     * Observes discovered network printers with real-time updates
     * @return Flow of discovered printers
     */
    fun observeDiscoveredPrinters(): Flow<List<NetworkPrinter>>
    
    /**
     * Starts network printer discovery
     * @param timeoutMs Discovery timeout in milliseconds
     * @return Flow of discovery results
     */
    suspend fun startDiscovery(timeoutMs: Long = 30000): Flow<DiscoveryResult>
    
    /**
     * Stops network printer discovery
     */
    suspend fun stopDiscovery()
    
    /**
     * Queries specific printer attributes
     * @param printer The network printer to query
     * @return List of IPP attribute groups, or null if query failed
     */
    suspend fun queryPrinterAttributes(printer: NetworkPrinter): List<AttributeGroup>?
    
    /**
     * Gets cached printer attributes
     * @param printerId The printer identifier
     * @return Cached attributes if available
     */
    suspend fun getCachedPrinterAttributes(printerId: String): List<AttributeGroup>?
    
    /**
     * Caches printer attributes for future use
     * @param printerId The printer identifier
     * @param attributes The attributes to cache
     */
    suspend fun cachePrinterAttributes(printerId: String, attributes: List<AttributeGroup>)
    
    /**
     * Validates printer connectivity
     * @param printer The printer to validate
     * @return Connectivity status
     */
    suspend fun validatePrinterConnectivity(printer: NetworkPrinter): ConnectivityStatus
    
    /**
     * Gets printer capabilities summary
     * @param printer The printer to analyze
     * @return Printer capabilities or null if unavailable
     */
    suspend fun getPrinterCapabilities(printer: NetworkPrinter): PrinterCapabilities?
    
    /**
     * Discovery result events
     */
    sealed class DiscoveryResult {
        object Started : DiscoveryResult()
        data class PrinterFound(val printer: NetworkPrinter) : DiscoveryResult()
        data class PrinterLost(val printer: NetworkPrinter) : DiscoveryResult()
        object Completed : DiscoveryResult()
        data class Error(val message: String, val exception: Throwable?) : DiscoveryResult()
    }
    
    /**
     * Printer connectivity status
     */
    enum class ConnectivityStatus {
        CONNECTED,
        DISCONNECTED,
        TIMEOUT,
        ERROR,
        UNKNOWN
    }
    
    /**
     * Printer capabilities information
     */
    data class PrinterCapabilities(
        val supportedFormats: List<String>,
        val supportedMediaSizes: List<String>,
        val colorSupported: Boolean,
        val duplexSupported: Boolean,
        val maxResolution: String?,
        val supportedOperations: List<String>,
        val deviceInfo: DeviceInfo
    ) {
        data class DeviceInfo(
            val manufacturer: String?,
            val model: String?,
            val serialNumber: String?,
            val firmwareVersion: String?,
            val description: String?
        )
    }
} 