package com.example.printer.domain.entities

/**
 * Domain entity representing printer settings and configuration
 * Contains all configurable options for the virtual printer service
 */
data class PrinterSettings(
    val printerName: String,
    val port: Int,
    val enableService: Boolean,
    val networkSettings: NetworkSettings,
    val documentSettings: DocumentSettings,
    val securitySettings: SecuritySettings,
    val debugSettings: DebugSettings,
    val advancedSettings: AdvancedSettings
) {
    
    /**
     * Network-related settings
     */
    data class NetworkSettings(
        val advertiseService: Boolean,
        val serviceType: String,
        val allowedNetworks: Set<String>,
        val blockedIPs: Set<String>,
        val enableIPv6: Boolean,
        val bindToAllInterfaces: Boolean,
        val customTxtRecords: Map<String, String>
    )
    
    /**
     * Document processing settings
     */
    data class DocumentSettings(
        val supportedFormats: Set<String>,
        val maxDocumentSize: Long,
        val autoProcessPDF: Boolean,
        val preserveOriginalFormat: Boolean,
        val enableFormatConversion: Boolean,
        val compressionLevel: CompressionLevel,
        val defaultMediaSize: String,
        val enableMetadataExtraction: Boolean
    )
    
    /**
     * Security and access control settings
     */
    data class SecuritySettings(
        val enableAuthentication: Boolean,
        val requireTLS: Boolean,
        val allowedUsers: Set<String>,
        val enableAccessLogging: Boolean,
        val maxConnectionsPerIP: Int,
        val enableRateLimiting: Boolean,
        val rateLimitRequests: Int,
        val rateLimitWindowSeconds: Int
    )
    
    /**
     * Debug and logging settings
     */
    data class DebugSettings(
        val enableDebugLogging: Boolean,
        val logLevel: LogLevel,
        val logToFile: Boolean,
        val maxLogFileSize: Long,
        val logRetentionDays: Int,
        val enablePerformanceMetrics: Boolean,
        val enableIPPTracing: Boolean,
        val verboseErrorReporting: Boolean
    )
    
    /**
     * Advanced printer configuration
     */
    data class AdvancedSettings(
        val simulateErrors: Boolean,
        val errorSimulationType: ErrorSimulationType,
        val customIppAttributes: Map<String, String>,
        val enableCustomOperations: Boolean,
        val serverThreads: Int,
        val connectionTimeout: Int,
        val requestTimeout: Int,
        val enableCompression: Boolean,
        val bufferSize: Int
    )
    
    /**
     * Document compression levels
     */
    enum class CompressionLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        MAXIMUM
    }
    
    /**
     * Logging levels
     */
    enum class LogLevel {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        NONE
    }
    
    /**
     * Error simulation types for testing
     */
    enum class ErrorSimulationType {
        NONE,
        SERVER_ERROR,
        CLIENT_ERROR,
        TIMEOUT,
        NETWORK_ERROR,
        AUTHENTICATION_ERROR,
        UNSUPPORTED_FORMAT,
        INSUFFICIENT_STORAGE,
        RANDOM
    }
    
    companion object {
        /**
         * Creates default printer settings
         */
        fun createDefault(): PrinterSettings {
            return PrinterSettings(
                printerName = "Android Virtual Printer",
                port = 8631,
                enableService = false,
                networkSettings = NetworkSettings(
                    advertiseService = true,
                    serviceType = "_ipp._tcp.",
                    allowedNetworks = emptySet(),
                    blockedIPs = emptySet(),
                    enableIPv6 = false,
                    bindToAllInterfaces = true,
                    customTxtRecords = emptyMap()
                ),
                documentSettings = DocumentSettings(
                    supportedFormats = setOf(
                        "application/pdf",
                        "application/octet-stream",
                        "image/jpeg",
                        "image/png",
                        "text/plain"
                    ),
                    maxDocumentSize = 100 * 1024 * 1024, // 100MB
                    autoProcessPDF = true,
                    preserveOriginalFormat = true,
                    enableFormatConversion = true,
                    compressionLevel = CompressionLevel.MEDIUM,
                    defaultMediaSize = "iso_a4_210x297mm",
                    enableMetadataExtraction = true
                ),
                securitySettings = SecuritySettings(
                    enableAuthentication = false,
                    requireTLS = false,
                    allowedUsers = emptySet(),
                    enableAccessLogging = true,
                    maxConnectionsPerIP = 10,
                    enableRateLimiting = false,
                    rateLimitRequests = 100,
                    rateLimitWindowSeconds = 60
                ),
                debugSettings = DebugSettings(
                    enableDebugLogging = false,
                    logLevel = LogLevel.INFO,
                    logToFile = false,
                    maxLogFileSize = 10 * 1024 * 1024, // 10MB
                    logRetentionDays = 7,
                    enablePerformanceMetrics = false,
                    enableIPPTracing = false,
                    verboseErrorReporting = false
                ),
                advancedSettings = AdvancedSettings(
                    simulateErrors = false,
                    errorSimulationType = ErrorSimulationType.NONE,
                    customIppAttributes = emptyMap(),
                    enableCustomOperations = false,
                    serverThreads = 4,
                    connectionTimeout = 30000,
                    requestTimeout = 60000,
                    enableCompression = false,
                    bufferSize = 8192
                )
            )
        }
        
        /**
         * Creates settings optimized for Chromium QA testing
         */
        fun createChromiumQAProfile(): PrinterSettings {
            return createDefault().copy(
                printerName = "Chromium QA Virtual Printer",
                debugSettings = DebugSettings(
                    enableDebugLogging = true,
                    logLevel = LogLevel.VERBOSE,
                    logToFile = true,
                    maxLogFileSize = 50 * 1024 * 1024, // 50MB
                    logRetentionDays = 30,
                    enablePerformanceMetrics = true,
                    enableIPPTracing = true,
                    verboseErrorReporting = true
                ),
                documentSettings = DocumentSettings(
                    supportedFormats = setOf(
                        "application/pdf",
                        "application/postscript",
                        "application/octet-stream",
                        "image/jpeg",
                        "image/png",
                        "image/gif",
                        "text/plain",
                        "application/vnd.cups-pdf",
                        "application/vnd.cups-postscript"
                    ),
                    maxDocumentSize = 500 * 1024 * 1024, // 500MB
                    autoProcessPDF = true,
                    preserveOriginalFormat = true,
                    enableFormatConversion = true,
                    compressionLevel = CompressionLevel.LOW,
                    defaultMediaSize = "iso_a4_210x297mm",
                    enableMetadataExtraction = true
                ),
                securitySettings = SecuritySettings(
                    enableAuthentication = false,
                    requireTLS = false,
                    allowedUsers = emptySet(),
                    enableAccessLogging = true,
                    maxConnectionsPerIP = 50,
                    enableRateLimiting = false,
                    rateLimitRequests = 1000,
                    rateLimitWindowSeconds = 60
                )
            )
        }
    }
    
    /**
     * Validates the current settings for consistency and correctness
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (printerName.isBlank()) {
            errors.add("Printer name cannot be blank")
        }
        
        if (port < 1024 || port > 65535) {
            errors.add("Port must be between 1024 and 65535")
        }
        
        if (documentSettings.maxDocumentSize <= 0) {
            errors.add("Maximum document size must be positive")
        }
        
        if (debugSettings.maxLogFileSize <= 0) {
            errors.add("Maximum log file size must be positive")
        }
        
        if (advancedSettings.serverThreads <= 0) {
            errors.add("Server threads must be positive")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Error(errors)
        }
    }
    
    /**
     * Result of settings validation
     */
    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Error(val errors: List<String>) : ValidationResult()
    }
} 