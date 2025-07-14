package com.example.printer.domain.entities

import java.net.InetAddress
import java.time.LocalDateTime

/**
 * Domain entity representing a network printer
 * Contains comprehensive information about discovered network printers
 */
data class NetworkPrinter(
    val id: String,
    val name: String,
    val address: InetAddress,
    val port: Int,
    val serviceType: String,
    val discoveryMethod: DiscoveryMethod,
    val discoveredAt: LocalDateTime,
    val lastSeen: LocalDateTime,
    val status: PrinterStatus,
    val capabilities: PrinterCapabilities?,
    val metadata: PrinterMetadata
) {
    
    /**
     * How the printer was discovered
     */
    enum class DiscoveryMethod {
        DNS_SD,     // Bonjour/mDNS service discovery
        BROADCAST,  // Network broadcast
        MANUAL,     // Manually added
        CACHED      // Previously discovered and cached
    }
    
    /**
     * Current printer status
     */
    enum class PrinterStatus {
        ONLINE,
        OFFLINE,
        BUSY,
        ERROR,
        UNKNOWN
    }
    
    /**
     * Printer capabilities and supported features
     */
    data class PrinterCapabilities(
        val supportedDocumentFormats: Set<String>,
        val supportedMediaSizes: Set<String>,
        val colorCapabilities: ColorCapabilities,
        val finishingOptions: Set<FinishingOption>,
        val inputTrays: Set<String>,
        val outputBins: Set<String>,
        val maxCopies: Int?,
        val supportedResolutions: Set<String>,
        val supportedCompressions: Set<String>,
        val ippVersion: String?,
        val supportedOperations: Set<String>
    )
    
    /**
     * Color printing capabilities
     */
    data class ColorCapabilities(
        val supportsColor: Boolean,
        val supportsMonochrome: Boolean,
        val colorModes: Set<String>
    )
    
    /**
     * Finishing options (stapling, hole punching, etc.)
     */
    enum class FinishingOption {
        NONE,
        STAPLE,
        PUNCH,
        FOLD,
        TRIM,
        BIND,
        SADDLE_STITCH,
        EDGE_STITCH
    }
    
    /**
     * Additional printer metadata
     */
    data class PrinterMetadata(
        val manufacturer: String?,
        val model: String?,
        val serialNumber: String?,
        val firmwareVersion: String?,
        val description: String?,
        val location: String?,
        val contact: String?,
        val deviceUri: String?,
        val makeAndModel: String?,
        val printerInfo: String?,
        val printerMoreInfo: String?,
        val deviceId: String?,
        val uuid: String?
    )
    
    /**
     * Gets the full printer identifier
     */
    fun getFullIdentifier(): String {
        return "${address.hostAddress}:$port"
    }
    
    /**
     * Gets the display name for the printer
     */
    fun getDisplayName(): String {
        return if (name.isNotBlank()) name else getFullIdentifier()
    }
    
    /**
     * Checks if the printer supports a specific document format
     */
    fun supportsFormat(format: String): Boolean {
        return capabilities?.supportedDocumentFormats?.any { 
            it.equals(format, ignoreCase = true) 
        } ?: false
    }
    
    /**
     * Checks if the printer supports color printing
     */
    fun supportsColor(): Boolean {
        return capabilities?.colorCapabilities?.supportsColor ?: false
    }
    
    /**
     * Gets a summary of the printer's key capabilities
     */
    fun getCapabilitiesSummary(): String {
        val caps = capabilities ?: return "Unknown capabilities"
        
        val formatCount = caps.supportedDocumentFormats.size
        val mediaCount = caps.supportedMediaSizes.size
        val colorSupport = if (caps.colorCapabilities.supportsColor) "Color" else "Monochrome"
        
        return "$colorSupport, $formatCount formats, $mediaCount media sizes"
    }
    
    /**
     * Checks if the printer was recently seen
     */
    fun isRecentlySeen(thresholdMinutes: Long = 5): Boolean {
        return LocalDateTime.now().minusMinutes(thresholdMinutes).isBefore(lastSeen)
    }
    
    /**
     * Gets the age of this discovery in minutes
     */
    fun getDiscoveryAgeMinutes(): Long {
        return java.time.Duration.between(discoveredAt, LocalDateTime.now()).toMinutes()
    }
} 