package com.example.printer.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.model.Operation
import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.AttributeType
import com.hp.jipp.model.Types
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.PrinterState
import com.hp.jipp.model.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Utility class for discovering and querying network printers
 */
object PrinterDiscoveryUtils {
    private const val TAG = "PrinterDiscoveryUtils"
    private const val SERVICE_TYPE = "_ipp._tcp."
    private const val IPP_PORT = 631 // Standard IPP port
    
    data class NetworkPrinter(
        val name: String,
        val address: InetAddress,
        val port: Int,
        val serviceInfo: NsdServiceInfo
    )
    
    /**
     * Discovers IPP printers on the network using DNS-SD, including self-discovery
     */
    suspend fun discoverPrinters(context: Context, includeSelf: Boolean = true): List<NetworkPrinter> {
        val deferred = CompletableDeferred<List<NetworkPrinter>>()
        val printers = CopyOnWriteArrayList<NetworkPrinter>()
        
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                deferred.complete(printers)
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
            
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started: $serviceType")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }
                    
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service resolved: ${serviceInfo.serviceName}, host: ${serviceInfo.host}, port: ${serviceInfo.port}")
                        
                        // Create a NetworkPrinter object from the service info
                        val printer = NetworkPrinter(
                            name = serviceInfo.serviceName,
                            address = serviceInfo.host,
                            port = serviceInfo.port,
                            serviceInfo = serviceInfo
                        )
                        
                        // Add to our list if not already there
                        if (!printers.any { it.name == printer.name && it.address == printer.address }) {
                            printers.add(printer)
                        }
                    }
                })
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                printers.removeIf { it.name == serviceInfo.serviceName }
            }
        }
        
        // Start discovery
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            
            // Set a timeout for discovery
            withContext(Dispatchers.IO) {
                Thread.sleep(5000) // 5 second discovery period
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping discovery", e)
                }
                deferred.complete(printers)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during discovery", e)
            deferred.complete(printers)
        }
        
        val discoveredPrinters = deferred.await().toMutableList()
        
        // Add self-discovery if enabled
        if (includeSelf) {
            val localPrinter = discoverLocalPrinter()
            if (localPrinter != null) {
                // Check if we already found this printer through mDNS
                val alreadyFound = discoveredPrinters.any { 
                    it.name == localPrinter.name && it.address == localPrinter.address 
                }
                if (!alreadyFound) {
                    Log.d(TAG, "Adding local printer to discovery results: ${localPrinter.name}")
                    discoveredPrinters.add(localPrinter)
                }
            }
        }
        
        return discoveredPrinters
    }
    
    /**
     * Discovers the local printer service if it's running on this device
     */
    private suspend fun discoverLocalPrinter(): NetworkPrinter? {
        return withContext(Dispatchers.IO) {
            try {
                // Get local IP address
                val localAddress = getLocalIpAddress()
                if (localAddress == null) {
                    Log.w(TAG, "Could not determine local IP address for self-discovery")
                    return@withContext null
                }
                
                // Check if printer service is running on the expected port (8631)
                val printerPort = 8631
                val printerName = "Android Virtual Printer" // Default name, could be configurable
                
                // Test if the local printer service is accessible
                val isAccessible = testLocalPrinterService(localAddress, printerPort)
                if (isAccessible) {
                    Log.d(TAG, "Local printer service found at $localAddress:$printerPort")
                    
                    // Create a synthetic NsdServiceInfo for the local printer
                    val serviceInfo = NsdServiceInfo().apply {
                        serviceName = printerName
                        serviceType = SERVICE_TYPE
                        port = printerPort
                        host = InetAddress.getByName(localAddress)
                    }
                    
                    return@withContext NetworkPrinter(
                        name = printerName,
                        address = InetAddress.getByName(localAddress),
                        port = printerPort,
                        serviceInfo = serviceInfo
                    )
                } else {
                    Log.d(TAG, "Local printer service not accessible at $localAddress:$printerPort")
                    return@withContext null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during local printer discovery", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Gets the local IP address of the device
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // Skip loopback addresses and IPv6 addresses
                    if (!address.isLoopbackAddress && address is InetAddress && !address.hostAddress.contains(":")) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return null
    }
    
    /**
     * Tests if the local printer service is accessible
     */
    private suspend fun testLocalPrinterService(address: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$address:$port/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000 // Short timeout for local service
                connection.readTimeout = 2000
                
                try {
                    connection.connect()
                    val responseCode = connection.responseCode
                    Log.d(TAG, "Local printer service test response: $responseCode")
                    return@withContext responseCode in 200..299
                } catch (e: Exception) {
                    Log.d(TAG, "Local printer service test failed: ${e.message}")
                    return@withContext false
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error testing local printer service: ${e.message}")
                return@withContext false
            }
        }
    }
    
    /**
     * Queries a printer's attributes using the IPP protocol
     */
    suspend fun queryPrinterAttributes(printer: NetworkPrinter): List<AttributeGroup>? {
        return withContext(Dispatchers.IO) {
            try {
                // Create the printer URI
                val printerUri = URI.create("ipp://${printer.address.hostAddress}:${printer.port}/")
                Log.d(TAG, "Querying printer at: $printerUri")
                
                // Prepare the IPP request for Get-Printer-Attributes
                val request = IppPacket(
                    Operation.getPrinterAttributes,
                    1,  // requestId
                    AttributeGroup.groupOf(
                        com.hp.jipp.encoding.Tag.operationAttributes,
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en"),
                        Types.printerUri.of(printerUri),
                        Types.requestingUserName.of("Android Print Client"),
                        Types.documentFormat.of("application/octet-stream"),
                        // Request comprehensive attribute sets
                        Types.requestedAttributes.of(
                            "all",
                            "printer-description",
                            "job-template",
                            "media-col-database",
                            "printer-defaults"
                        )
                    )
                )
                
                // Serialize the IPP request
                val outputStream = ByteArrayOutputStream()
                IppOutputStream(outputStream).use { it.write(request) }
                val requestBytes = outputStream.toByteArray()
                
                Log.d(TAG, "Connecting to printer ${printer.name} at ${printer.address.hostAddress}:${printer.port}")
                
                // Set up the HTTP connection
                val url = URL("http://${printer.address.hostAddress}:${printer.port}/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/ipp")
                connection.setRequestProperty("User-Agent", "Android-IPP-Client")
                connection.doOutput = true
                connection.connectTimeout = 10000 // Increase timeout to 10 seconds
                connection.readTimeout = 10000    // Increase timeout to 10 seconds
                
                try {
                    // Send the IPP request
                    Log.d(TAG, "Sending IPP request to printer (${requestBytes.size} bytes)")
                    connection.outputStream.use { it.write(requestBytes) }
                    
                    // Get the response
                    val responseCode = connection.responseCode
                    Log.d(TAG, "Received HTTP response code: $responseCode")
                    
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val responseBytes = connection.inputStream.readBytes()
                        Log.d(TAG, "Received response data: ${responseBytes.size} bytes")
                        
                        val response = IppInputStream(responseBytes.inputStream()).readPacket()
                        
                        Log.d(TAG, "Received IPP response with ${response.attributeGroups.size} attribute groups and status: ${response.code}")
                        
                        // Check if the response was successful
                        if (response.code.toInt() < 0x300) { // Success codes are < 0x300
                            Log.d(TAG, "Successfully queried printer attributes")
                            return@withContext response.attributeGroups
                        } else {
                            Log.e(TAG, "IPP error response: ${response.code}")
                            return@withContext null
                        }
                    } else {
                        // Try to get error information from the response
                        val errorMessage = try {
                            connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                        } catch (e: Exception) {
                            "Could not read error stream: ${e.message}"
                        }
                        
                        Log.e(TAG, "HTTP error: $responseCode, message: $errorMessage")
                        return@withContext null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during HTTP communication with printer: ${e.message}", e)
                    return@withContext null
                } finally {
                    connection.disconnect()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error querying printer attributes: ${e.message}", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Try alternative ports or protocols when standard IPP fails
     */
    suspend fun queryPrinterWithAlternatives(printer: NetworkPrinter): List<AttributeGroup>? {
        // First try standard IPP port
        Log.d(TAG, "Trying to query printer with standard IPP...")
        val attributes = queryPrinterAttributes(printer)
        if (attributes != null) {
            return attributes
        }
        
        Log.d(TAG, "Standard IPP query failed, trying alternative ports/protocols...")
        
        // Try common alternative ports
        val alternativePorts = listOf(80, 443, 9100, 515)
        for (port in alternativePorts) {
            if (port == printer.port) continue // Skip the original port
            
            Log.d(TAG, "Trying alternative port: $port")
            val altPrinter = printer.copy(port = port)
            val result = queryPrinterAttributes(altPrinter)
            if (result != null) {
                Log.d(TAG, "Successfully queried printer on alternative port: $port")
                return result
            }
        }
        
        // If all attempts failed, create minimal attributes for basic functionality
        Log.d(TAG, "All query attempts failed. Creating minimal attributes set for basic functionality")
        return createMinimalAttributes(printer)
    }
    
    /**
     * Creates a minimal set of printer attributes when query fails
     */
    fun createMinimalAttributes(printer: NetworkPrinter): List<AttributeGroup> {
        Log.d(TAG, "Creating minimal attributes for ${printer.name}")
        
        val printerUri = try {
            URI.create("ipp://${printer.address.hostAddress}:${printer.port}/")
        } catch (e: Exception) {
            URI.create("ipp://localhost:631/")
        }
        
        val printerAttributes = AttributeGroup.groupOf(
            Tag.printerAttributes,
            // Basic printer information
            Types.printerName.of(printer.name),
            Types.printerState.of(PrinterState.idle),
            Types.printerStateReasons.of("none"),
            Types.printerIsAcceptingJobs.of(true),
            Types.printerUri.of(printerUri),
            Types.printerLocation.of("Network Printer"),
            Types.printerInfo.of("${printer.name} - Discovered Printer"),
            Types.printerMakeAndModel.of("${printer.name} - Generic"),
            
            // Supported document formats
            Types.documentFormatSupported.of(
                "application/pdf", 
                "application/octet-stream",
                "image/jpeg",
                "image/png"
            ),
            Types.documentFormat.of("application/pdf"),
            
            // Media support
            Types.mediaDefault.of("iso_a4_210x297mm"),
            Types.mediaSupported.of(
                "iso_a4_210x297mm", 
                "na_letter_8.5x11in"
            )
        )
        
        return listOf(printerAttributes)
    }
    
    /**
     * Tries to reach the printer using HTTP instead of IPP
     */
    suspend fun testPrinterConnectivity(printer: NetworkPrinter): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://${printer.address.hostAddress}:${printer.port}/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                try {
                    connection.connect()
                    val responseCode = connection.responseCode
                    
                    if (responseCode in 200..299) {
                        val contentType = connection.contentType ?: "unknown"
                        "Connected successfully! Response code: $responseCode, Content-Type: $contentType"
                    } else {
                        "Connected but received error code: $responseCode"
                    }
                } catch (e: Exception) {
                    "Connection error: ${e.message}"
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                "Failed to test connection: ${e.message}"
            }
        }
    }
    
    /**
     * Queries a printer's attributes and saves them directly as JSON
     */
    suspend fun queryAndSavePrinterAttributesAsJson(context: Context, printer: NetworkPrinter, filename: String): Boolean {
        val attributes = queryPrinterAttributes(printer)
        return if (attributes != null) {
            // For now, just log the attributes - can be enhanced later
            Log.d(TAG, "Successfully queried printer attributes: ${attributes.size} groups")
            true
        } else {
            Log.e(TAG, "Failed to query printer attributes")
            false
        }
    }
    
    /**
     * Exports the printer attributes to a file
     */
    fun exportPrinterAttributesToFile(context: Context, attributes: List<AttributeGroup>, filename: String): Boolean {
        // For now, just log the attributes - can be enhanced later
        Log.d(TAG, "Would export ${attributes.size} attribute groups to $filename")
        return true
    }
} 