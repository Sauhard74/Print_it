package com.example.printer.services.discovery

import android.content.Context
import com.example.printer.domain.entities.NetworkPrinter
import com.example.printer.domain.repositories.PrinterDiscoveryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service for network printer discovery and management
 * Provides high-level discovery operations and state management
 */
class DiscoveryService(
    private val context: Context,
    private val printerDiscoveryRepository: PrinterDiscoveryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    
    private val _discoveredPrinters = MutableStateFlow<List<NetworkPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<NetworkPrinter>> = _discoveredPrinters.asStateFlow()
    
    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    val discoveryStatus: StateFlow<DiscoveryStatus> = _discoveryStatus.asStateFlow()

    init {
        // Observe discovered printers from repository
        scope.launch {
            printerDiscoveryRepository.observeDiscoveredPrinters().collect { printers ->
                _discoveredPrinters.value = printers
            }
        }
    }

    /**
     * Starts network printer discovery
     */
    fun startDiscovery(timeoutMs: Long = 30000) {
        if (_isDiscovering.value) return
        
        scope.launch {
            _isDiscovering.value = true
            _discoveryStatus.value = DiscoveryStatus.Starting
            
            try {
                printerDiscoveryRepository.startDiscovery(timeoutMs).collect { result ->
                    when (result) {
                        is PrinterDiscoveryRepository.DiscoveryResult.Started -> {
                            _discoveryStatus.value = DiscoveryStatus.Discovering
                        }
                        is PrinterDiscoveryRepository.DiscoveryResult.PrinterFound -> {
                            _discoveryStatus.value = DiscoveryStatus.Discovering
                        }
                        is PrinterDiscoveryRepository.DiscoveryResult.PrinterLost -> {
                            _discoveryStatus.value = DiscoveryStatus.Discovering
                        }
                        is PrinterDiscoveryRepository.DiscoveryResult.Completed -> {
                            _isDiscovering.value = false
                            _discoveryStatus.value = DiscoveryStatus.Completed
                        }
                        is PrinterDiscoveryRepository.DiscoveryResult.Error -> {
                            _isDiscovering.value = false
                            _discoveryStatus.value = DiscoveryStatus.Error(result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                _isDiscovering.value = false
                _discoveryStatus.value = DiscoveryStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Stops network printer discovery
     */
    fun stopDiscovery() {
        scope.launch {
            printerDiscoveryRepository.stopDiscovery()
            _isDiscovering.value = false
            _discoveryStatus.value = DiscoveryStatus.Stopped
        }
    }

    /**
     * Refreshes the list of discovered printers
     */
    fun refreshPrinters() {
        if (!_isDiscovering.value) {
            startDiscovery()
        }
    }

    /**
     * Gets detailed information about a specific printer
     */
    suspend fun getPrinterDetails(printer: NetworkPrinter): PrinterDiscoveryRepository.PrinterCapabilities? {
        return printerDiscoveryRepository.getPrinterCapabilities(printer)
    }

    /**
     * Validates connectivity to a specific printer
     */
    suspend fun validatePrinterConnectivity(printer: NetworkPrinter): PrinterDiscoveryRepository.ConnectivityStatus {
        return printerDiscoveryRepository.validatePrinterConnectivity(printer)
    }

    /**
     * Discovery status states
     */
    sealed class DiscoveryStatus {
        object Idle : DiscoveryStatus()
        object Starting : DiscoveryStatus()
        object Discovering : DiscoveryStatus()
        object Completed : DiscoveryStatus()
        object Stopped : DiscoveryStatus()
        data class Error(val message: String) : DiscoveryStatus()
    }
} 