package com.example.printer.printer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import java.io.File
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.Tag
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import com.hp.jipp.model.PrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import java.net.URI

class PrinterService(private val context: Context) {
    private val TAG = "PrinterService"
    private val PRINTER_NAME = "Android Virtual Printer"
    private val SERVICE_TYPE = "_ipp._tcp"
    private val PORT = 8631 // Using non-privileged port instead of 631
    
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var server: ApplicationEngine? = null
    
    private val printJobsDirectory: File by lazy {
        File(context.filesDir, "print_jobs").apply {
            if (!exists()) mkdirs()
        }
    }
    
    fun getPrinterName(): String = PRINTER_NAME
    
    fun getPort(): Int = PORT
    
    fun startPrinterService(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            startServer()
            registerService(onSuccess, onError)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start printer service", e)
            onError("Failed to start printer service: ${e.message}")
        }
    }
    
    fun stopPrinterService() {
        try {
            unregisterService()
            stopServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping printer service", e)
        }
    }
    
    private fun startServer() {
        server = embeddedServer(Netty, port = PORT) {
            routing {
                post("/") {
                    try {
                        val requestBytes = call.receiveStream().readBytes()
                        val ippRequest = IppInputStream(requestBytes.inputStream()).readPacket()
                        
                        Log.d(TAG, "Received IPP request: ${ippRequest.code}")
                        
                        // Process the IPP request and prepare a response
                        val response = processIppRequest(ippRequest, call)
                        
                        // Send the response
                        val outputStream = ByteArrayOutputStream()
                        IppOutputStream(outputStream).write(response)
                        call.respondBytes(
                            outputStream.toByteArray(),
                            ContentType("application", "ipp")
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing IPP request", e)
                        call.respond(HttpStatusCode.InternalServerError, "Error processing print request")
                    }
                }
                
                get("/") {
                    call.respondText("Virtual Printer Service Running", ContentType.Text.Plain)
                }
            }
        }
        
        server?.start(wait = false)
        Log.d(TAG, "Printer server started on port $PORT")
    }
    
    private suspend fun processIppRequest(request: IppPacket, call: ApplicationCall): IppPacket {
        return when (request.code) {
            Operation.printJob.code -> { // Print-Job operation
                // Extract and save document data from the request
                val docBytes = call.receive<ByteArray>()
                saveDocument(docBytes)
                
                // Create a success response
                IppPacket(Status.successfulOk, request.requestId)
            }
            Operation.validateJob.code -> { // Validate-Job operation
                IppPacket(Status.successfulOk, request.requestId)
            }
            Operation.createJob.code -> { // Create-Job operation
                IppPacket(Status.successfulOk, request.requestId)
            }
            Operation.getPrinterAttributes.code -> { // Get-Printer-Attributes operation
                // This provides information about printer capabilities
                createPrinterAttributesResponse(request)
            }
            else -> {
                // For any unhandled operations, return a positive response
                IppPacket(Status.successfulOk, request.requestId)
            }
        }
    }
    
    private fun createPrinterAttributesResponse(request: IppPacket): IppPacket {
        // Get the IP address of the device
        val hostAddress = getLocalIpAddress() ?: "127.0.0.1"
        val printerUri = URI.create("ipp://$hostAddress:$PORT/")
        
        // Create printer attributes group
        val printerAttributes = AttributeGroup.groupOf(
            Tag.printerAttributes,
            // Basic printer information
            Types.printerName.of(PRINTER_NAME),
            Types.printerState.of(PrinterState.idle),
            Types.printerStateReasons.of("none"),
            Types.printerIsAcceptingJobs.of(true),
            Types.printerUri.of(printerUri),
            Types.printerLocation.of("Mobile Device"),
            Types.printerInfo.of("Android Virtual Printer - Mobile PDF Printer"),
            Types.printerMakeAndModel.of("Android Virtual Printer v1.0"),
            
            // Supported document formats
            Types.documentFormatSupported.of(
                "application/pdf", 
                "application/octet-stream",
                "text/plain"
            ),
            Types.documentFormat.of("application/pdf"),
            
            // Media support
            Types.mediaDefault.of("iso_a4_210x297mm"),
            Types.mediaSupported.of(
                "iso_a4_210x297mm", 
                "iso_a5_148x210mm",
                "na_letter_8.5x11in", 
                "na_legal_8.5x14in"
            ),
            
            // Job attributes
            Types.jobSheetsDefault.of("none"),
            Types.jobSheetsSupported.of("none", "standard"),
            
            // Operations supported
            Types.operationsSupported.of(
                Operation.printJob.code,
                Operation.validateJob.code,
                Operation.createJob.code,
                Operation.getPrinterAttributes.code,
                Operation.getJobAttributes.code
            ),
            
            // Capabilities
            Types.colorSupported.of(true)
            // Copies and sides support are removed for simplicity as they were causing type issues
        )
        
        // Create the response packet with operation attributes and printer attributes
        return IppPacket(
            Status.successfulOk,
            request.requestId,
            AttributeGroup.groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            ),
            printerAttributes
        )
    }
    
    private fun saveDocument(docBytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileName = "print-job-${System.currentTimeMillis()}.pdf"
                val file = File(printJobsDirectory, fileName)
                file.writeBytes(docBytes)
                Log.d(TAG, "Saved document: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving document", e)
            }
        }
    }
    
    private fun stopServer() {
        server?.stop(1000, 2000)
        server = null
        Log.d(TAG, "Printer server stopped")
    }
    
    private fun registerService(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = PRINTER_NAME
                serviceType = SERVICE_TYPE
                port = PORT
                
                // Add printer attributes as TXT records
                val txtRecords = mapOf(
                    "rp" to "ipp/print",
                    "ty" to PRINTER_NAME,
                    "pdl" to "application/pdf,application/octet-stream,text/plain",
                    "note" to "Virtual Printer",
                    "priority" to "50",
                    "product" to "Android Virtual Printer",
                    "Color" to "T",  // T for True
                    "Duplex" to "F", // F for False (one-sided only)
                    "usb_MFG" to "Android",
                    "usb_MDL" to "VirtualPrinter"
                )
                
                txtRecords.forEach { (key, value) ->
                    setAttribute(key, value)
                }
            }
            
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Printer service registered: ${serviceInfo.serviceName}")
                    onSuccess()
                }
                
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val error = "Registration failed with error code: $errorCode"
                    Log.e(TAG, error)
                    onError(error)
                }
                
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Printer service unregistered")
                }
                
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Unregistration failed with error code: $errorCode")
                }
            }
            
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            onError("Error registering service: ${e.message}")
        }
    }
    
    private fun unregisterService() {
        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
                registrationListener = null
            }
            nsdManager = null
            Log.d(TAG, "Service unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return null
    }
} 