package com.example.printer.data.repositories

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.printer.domain.entities.NetworkPrinter
import com.example.printer.domain.repositories.PrinterDiscoveryRepository
import com.example.printer.domain.repositories.PrinterDiscoveryRepository.ConnectivityStatus
import com.example.printer.domain.repositories.PrinterDiscoveryRepository.DiscoveryResult
import com.example.printer.domain.repositories.PrinterDiscoveryRepository.PrinterCapabilities
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of PrinterDiscoveryRepository using Android Network Service Discovery
 */
class PrinterDiscoveryRepositoryImpl(
    private val context: Context
) : PrinterDiscoveryRepository {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _discoveredPrinters = MutableStateFlow<List<NetworkPrinter>>(emptyList())
    private val discoveredPrinters: StateFlow<List<NetworkPrinter>> = _discoveredPrinters.asStateFlow()
    
    private val printerCache = ConcurrentHashMap<String, NetworkPrinter>()
    private val attributeCache = ConcurrentHashMap<String, List<AttributeGroup>>()
    
    private var isDiscovering = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun observeDiscoveredPrinters(): Flow<List<NetworkPrinter>> = discoveredPrinters

    override suspend fun startDiscovery(timeoutMs: Long): Flow<DiscoveryResult> = callbackFlow {
        if (isDiscovering) {
            trySend(DiscoveryResult.Error("Discovery already in progress", null))
            close()
            return@callbackFlow
        }

        trySend(DiscoveryResult.Started)
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                trySend(DiscoveryResult.Error("Discovery start failed: $errorCode", null))
                isDiscovering = false
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                trySend(DiscoveryResult.Error("Discovery stop failed: $errorCode", null))
                isDiscovering = false
                close()
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                isDiscovering = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                isDiscovering = false
                trySend(DiscoveryResult.Completed)
                close()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    // Resolve the service to get full details
                    resolveService(it) { printer ->
                        printerCache[printer.id] = printer
                        updateDiscoveredPrinters()
                        trySend(DiscoveryResult.PrinterFound(printer))
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    val printerId = "${it.serviceName}_${it.serviceType}"
                    printerCache.remove(printerId)?.let { printer ->
                        updateDiscoveredPrinters()
                        trySend(DiscoveryResult.PrinterLost(printer))
                    }
                }
            }
        }

        try {
            nsdManager.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            
            // Auto-stop discovery after timeout
            scope.launch {
                try {
                    withTimeout(timeoutMs) {
                        awaitClose()
                    }
                } catch (e: Exception) {
                    stopDiscovery()
                }
            }
            
            awaitClose()
        } catch (e: Exception) {
            trySend(DiscoveryResult.Error("Discovery failed", e))
            close()
        }
    }

    override suspend fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Ignore errors when stopping
            }
        }
        isDiscovering = false
        discoveryListener = null
    }

    override suspend fun queryPrinterAttributes(printer: NetworkPrinter): List<AttributeGroup>? {
        return withContext(Dispatchers.IO) {
            try {
                // Create empty mock attributes for testing
                val attributes = emptyList<AttributeGroup>()
                
                // Cache the results
                cachePrinterAttributes(printer.id, attributes)
                
                attributes
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun getCachedPrinterAttributes(printerId: String): List<AttributeGroup>? {
        return attributeCache[printerId]
    }

    override suspend fun cachePrinterAttributes(printerId: String, attributes: List<AttributeGroup>) {
        attributeCache[printerId] = attributes
    }

    override suspend fun validatePrinterConnectivity(printer: NetworkPrinter): ConnectivityStatus {
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(5000) {
                    // Mock connectivity validation
                    if (printer.status == NetworkPrinter.PrinterStatus.ONLINE) {
                        ConnectivityStatus.CONNECTED
                    } else {
                        ConnectivityStatus.DISCONNECTED
                    }
                }
            } catch (e: IOException) {
                ConnectivityStatus.DISCONNECTED
            } catch (e: Exception) {
                if (e.message?.contains("timeout") == true) {
                    ConnectivityStatus.TIMEOUT
                } else {
                    ConnectivityStatus.ERROR
                }
            }
        }
    }

    override suspend fun getPrinterCapabilities(printer: NetworkPrinter): PrinterCapabilities? {
        val attributes = queryPrinterAttributes(printer) ?: return null
        
        return try {
            PrinterCapabilities(
                supportedFormats = listOf("application/pdf", "application/postscript", "image/jpeg", "image/png"),
                supportedMediaSizes = listOf("iso_a4_210x297mm", "na_letter_8.5x11in", "iso_a3_297x420mm"),
                colorSupported = true,
                duplexSupported = true,
                maxResolution = "600x600dpi",
                supportedOperations = listOf("Print-Job", "Get-Printer-Attributes", "Get-Jobs"),
                deviceInfo = PrinterCapabilities.DeviceInfo(
                    manufacturer = "Virtual",
                    model = "Test Printer",
                    serialNumber = "123456",
                    firmwareVersion = "1.0",
                    description = "Virtual printer for testing"
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, callback: (NetworkPrinter) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                // Resolution failed, ignore this service
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    val now = LocalDateTime.now()
                    
                    val printer = NetworkPrinter(
                        id = "${it.serviceName}_${it.serviceType}",
                        name = it.serviceName,
                        address = it.host,
                        port = it.port,
                        serviceType = it.serviceType,
                        discoveryMethod = NetworkPrinter.DiscoveryMethod.DNS_SD,
                        discoveredAt = now,
                        lastSeen = now,
                        status = NetworkPrinter.PrinterStatus.ONLINE,
                        capabilities = createMockCapabilities(),
                        metadata = createMockMetadata(it)
                    )
                    callback(printer)
                }
            }
        }
        
        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    private fun updateDiscoveredPrinters() {
        _discoveredPrinters.value = printerCache.values.toList()
    }

    private fun createMockCapabilities(): NetworkPrinter.PrinterCapabilities {
        return NetworkPrinter.PrinterCapabilities(
            supportedDocumentFormats = setOf("application/pdf", "application/postscript", "image/jpeg"),
            supportedMediaSizes = setOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
            colorCapabilities = NetworkPrinter.ColorCapabilities(
                supportsColor = true,
                supportsMonochrome = true,
                colorModes = setOf("color", "monochrome")
            ),
            finishingOptions = setOf(NetworkPrinter.FinishingOption.NONE, NetworkPrinter.FinishingOption.STAPLE),
            inputTrays = setOf("auto", "tray1", "tray2"),
            outputBins = setOf("face-down", "face-up"),
            maxCopies = 999,
            supportedResolutions = setOf("300x300dpi", "600x600dpi"),
            supportedCompressions = setOf("none", "deflate"),
            ippVersion = "2.0",
            supportedOperations = setOf("Print-Job", "Get-Printer-Attributes", "Get-Jobs")
        )
    }

    private fun createMockMetadata(serviceInfo: NsdServiceInfo): NetworkPrinter.PrinterMetadata {
        return NetworkPrinter.PrinterMetadata(
            manufacturer = "Virtual",
            model = "Test Printer",
            serialNumber = "123456",
            firmwareVersion = "1.0",
            description = "Virtual printer for testing",
            location = "Virtual Lab",
            contact = "test@example.com",
            deviceUri = "ipp://${serviceInfo.host.hostAddress}:${serviceInfo.port}/",
            makeAndModel = "Virtual Test Printer v1.0",
            printerInfo = serviceInfo.serviceName,
            printerMoreInfo = "http://${serviceInfo.host.hostAddress}:${serviceInfo.port}/",
            deviceId = "VTP-123456",
            uuid = "uuid:${serviceInfo.serviceName}"
        )
    }
} 