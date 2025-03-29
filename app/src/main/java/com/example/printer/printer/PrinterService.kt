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
import com.example.printer.utils.PreferenceUtils
import java.io.FileOutputStream

class PrinterService(private val context: Context) {
    private val TAG = "PrinterService"
    private val DEFAULT_PRINTER_NAME = "Android Virtual Printer"
    private val SERVICE_TYPE = "_ipp._tcp."
    private val PORT = 8631 // Using non-privileged port instead of 631
    
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var server: ApplicationEngine? = null
    private var customIppAttributes: List<AttributeGroup>? = null
    
    // Add error simulation properties
    private var simulateErrorMode = false
    private var errorType = "none" // Options: "none", "server-error", "client-error", "aborted", "unsupported-format"
    
    private val printJobsDirectory: File by lazy {
        File(context.filesDir, "print_jobs").apply {
            if (!exists()) mkdirs()
        }
    }
    
    fun getPrinterName(): String {
        return PreferenceUtils.getCustomPrinterName(context)
    }
    
    fun getPort(): Int = PORT
    
    /**
     * Configures error simulation
     * @param enable Whether to enable error simulation
     * @param type Type of error to simulate (none, server-error, client-error, aborted, unsupported-format)
     */
    fun configureErrorSimulation(enable: Boolean, type: String = "none") {
        simulateErrorMode = enable
        errorType = type
        Log.d(TAG, "Error simulation ${if(enable) "enabled" else "disabled"} with type: $type")
    }
    
    /**
     * Gets current error simulation status
     * @return Pair of (isEnabled, errorType)
     */
    fun getErrorSimulationStatus(): Pair<Boolean, String> {
        return Pair(simulateErrorMode, errorType)
    }
    
    fun setCustomIppAttributes(attributes: List<AttributeGroup>?) {
        customIppAttributes = attributes
        Log.d(TAG, "Set custom IPP attributes: ${attributes?.size ?: 0} groups")
    }
    
    fun getCustomIppAttributes(): List<AttributeGroup>? {
        return customIppAttributes
    }
    
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
                        // Read the entire request body first
                        val requestBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            call.receiveStream().readBytes()
                        }
                        
                        // Parse document data vs IPP header
                        // IPP starts with version-number (2 bytes), operation-id (2 bytes), request-id (4 bytes)
                        // Document data follows the IPP attributes section
                        val headerSize = 8 // version (2) + operation (2) + request-id (4)
                        var ippEndIndex = requestBytes.size
                        
                        // Assume document data starts after IPP data
                        var documentData = ByteArray(0)
                        
                        // Parse the IPP packet
                        val ippRequest = IppInputStream(requestBytes.inputStream()).readPacket()
                        Log.d(TAG, "Received IPP request: ${ippRequest.code}")
                        
                        // For Print-Job and Send-Document, extract document data
                        if (ippRequest.code == Operation.printJob.code || 
                            ippRequest.code == Operation.sendDocument.code) {
                            
                            // Try to find the document data which follows the IPP attributes
                            // These operations have document content after the IPP data
                            documentData = extractDocumentContent(requestBytes)
                        }
                        
                        // Process the IPP request and prepare a response
                        val response = processIppRequest(ippRequest, documentData, call)
                        
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
                    call.respondText("${getPrinterName()} Service Running", ContentType.Text.Plain)
                }
            }
        }
        
        server?.start(wait = false)
        Log.d(TAG, "Printer server started on port $PORT")
    }
    
    private fun extractDocumentContent(requestBytes: ByteArray): ByteArray {
        try {
            // For debugging, log the first few bytes of the data
            val headerHex = requestBytes.take(Math.min(20, requestBytes.size))
                .joinToString(" ") { String.format("%02X", it) }
            Log.d(TAG, "Request data starts with: $headerHex")
            
            // Find the end-of-attributes-tag (0x03) followed by a zero-length attribute
            // This is a more robust way to find where IPP header ends and document data begins
            var i = 8 // Skip past the 8-byte IPP header
            while (i < requestBytes.size - 1) {
                if (requestBytes[i] == 0x03.toByte()) {
                    // Found the end-of-attributes-tag
                    var endPos = i + 1
                    
                    // Sometimes there's a delimiter between attributes and document data
                    // Skip any potential delimiters
                    while (endPos < requestBytes.size && 
                           (requestBytes[endPos] == 0x00.toByte() || 
                            requestBytes[endPos] == 0x0D.toByte() || 
                            requestBytes[endPos] == 0x0A.toByte())) {
                        endPos++
                    }
                    
                    if (endPos < requestBytes.size) {
                        val docBytes = requestBytes.copyOfRange(endPos, requestBytes.size)
                        Log.d(TAG, "Extracted ${docBytes.size} bytes of document data after position $endPos")
                        
                        // Log a snippet of the document data to verify it looks like PDF or other document format
                        if (docBytes.size > 4) {
                            val prefix = docBytes.take(Math.min(20, docBytes.size))
                                .joinToString(" ") { String.format("%02X", it) }
                            Log.d(TAG, "Document data starts with: $prefix")
                            
                            // Check if it starts with %PDF for PDF documents
                            if (docBytes.size >= 4 && 
                                docBytes[0] == '%'.toByte() && 
                                docBytes[1] == 'P'.toByte() && 
                                docBytes[2] == 'D'.toByte() && 
                                docBytes[3] == 'F'.toByte()) {
                                Log.d(TAG, "Document appears to be a PDF based on signature")
                            } else {
                                Log.d(TAG, "Document does NOT have PDF signature")
                            }
                        }
                        
                        return docBytes
                    }
                    break
                }
                i++
            }
            
            Log.d(TAG, "No document content found using 0x03 end marker. Trying alternative method...")
            
            // Alternative approach: if the content-type is application/pdf, try to find the %PDF header
            for (i in 0 until requestBytes.size - 4) {
                if (requestBytes[i] == '%'.toByte() && 
                    requestBytes[i+1] == 'P'.toByte() && 
                    requestBytes[i+2] == 'D'.toByte() && 
                    requestBytes[i+3] == 'F'.toByte()) {
                    
                    val docBytes = requestBytes.copyOfRange(i, requestBytes.size)
                    Log.d(TAG, "Found PDF marker at position $i, extracted ${docBytes.size} bytes")
                    return docBytes
                }
            }
            
            // Log more information to help diagnose the issue
            Log.d(TAG, "Could not extract document data. Total request size: ${requestBytes.size} bytes")
            
            return ByteArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting document content", e)
            return ByteArray(0)
        }
    }
    
    private suspend fun processIppRequest(
        request: IppPacket, 
        documentData: ByteArray,
        call: ApplicationCall
    ): IppPacket {
        Log.d(TAG, "Processing IPP request: ${request.code}, operation: ${request.operation?.name ?: "unknown"}")
        
        // Check for error simulation before normal processing
        if (simulateErrorMode) {
            return when (errorType) {
                "server-error" -> {
                    Log.d(TAG, "Simulating server error response")
                    IppPacket(Status.serverErrorInternalError, request.requestId)
                }
                "client-error" -> {
                    Log.d(TAG, "Simulating client error response")
                    IppPacket(Status.clientErrorNotPossible, request.requestId)
                }
                "aborted" -> {
                    Log.d(TAG, "Simulating aborted job response")
                    if (request.code == Operation.printJob.code || request.code == Operation.createJob.code) {
                        val jobId = System.currentTimeMillis().toInt()
                        IppPacket(
                            Status.clientErrorNotPossible,
                            request.requestId,
                            AttributeGroup.groupOf(
                                Tag.operationAttributes,
                                Types.attributesCharset.of("utf-8"),
                                Types.attributesNaturalLanguage.of("en")
                            ),
                            AttributeGroup.groupOf(
                                Tag.jobAttributes,
                                Types.jobId.of(jobId),
                                Types.jobState.of(7), // 7 = canceled
                                Types.jobStateReasons.of("job-canceled-by-system")
                            )
                        )
                    } else {
                        IppPacket(Status.clientErrorNotPossible, request.requestId)
                    }
                }
                "unsupported-format" -> {
                    Log.d(TAG, "Simulating unsupported format response")
                    if (request.code == Operation.printJob.code || request.code == Operation.validateJob.code) {
                        IppPacket(
                            Status.clientErrorDocumentFormatNotSupported,
                            request.requestId,
                            AttributeGroup.groupOf(
                                Tag.operationAttributes,
                                Types.attributesCharset.of("utf-8"),
                                Types.attributesNaturalLanguage.of("en"),
                                Types.statusMessage.of("Document format not supported")
                            )
                        )
                    } else {
                        IppPacket(Status.successfulOk, request.requestId)
                    }
                }
                else -> {
                    // Fall through to normal processing if error type is "none" or invalid
                    null
                }
            } ?: processNormalRequest(request, documentData, call)
        } else {
            // Normal processing without error simulation
            return processNormalRequest(request, documentData, call)
        }
    }
    
    private fun processNormalRequest(
        request: IppPacket,
        documentData: ByteArray,
        call: ApplicationCall
    ): IppPacket {
        return when (request.code) {
            Operation.printJob.code -> { // Print-Job operation
                try {
                    // Check if there's document data
                    val headersList = call.request.headers.entries().map { "${it.key}: ${it.value}" }
                    Log.d(TAG, "Print job headers: ${headersList.joinToString(", ")}")
                    
                    // Dump the entire attribute set for debugging
                    Log.d(TAG, "Print-Job ALL attributes: ${request.attributeGroups}")
                    
                    // Get document format from attributes if available
                    val documentFormat = request.attributeGroups
                        .find { it.tag == Tag.operationAttributes }
                        ?.getValues(Types.documentFormat)
                        ?.firstOrNull()
                        ?.toString() ?: "application/octet-stream"
                    
                    Log.d(TAG, "Print-Job document format: $documentFormat")
                    
                    // Special case for macOS printing
                    if (documentFormat == "application/octet-stream" && 
                        (headersList.any { it.contains("darwin", ignoreCase = true) } || 
                         headersList.any { it.contains("Mac OS X", ignoreCase = true) })) {
                        Log.d(TAG, "macOS client detected, treating document as PDF by default")
                    }
                    
                    // Check supported document formats
                    val supportedFormats = listOf(
                        "application/octet-stream",
                        "application/pdf",
                        "application/postscript",
                        "application/vnd.cups-pdf",
                        "application/vnd.cups-postscript",
                        "application/vnd.cups-raw",
                        "image/jpeg",
                        "image/png"
                    )
                    
                    if (documentFormat !in supportedFormats) {
                        Log.w(TAG, "Unsupported document format: $documentFormat")
                        // Continue anyway, as we'll try to handle it
                    }
                    
                    if (documentData.isNotEmpty()) {
                        Log.d(TAG, "Received document data: ${documentData.size} bytes")
                        
                        // Generate a unique job ID
                        val jobId = System.currentTimeMillis()
                        
                        // Save the document with format info
                        saveDocument(documentData, jobId, documentFormat)
                        
                        // Create a success response with job attributes
                        val response = IppPacket(
                            Status.successfulOk,
                            request.requestId,
                            AttributeGroup.groupOf(
                                Tag.operationAttributes,
                                Types.attributesCharset.of("utf-8"),
                                Types.attributesNaturalLanguage.of("en")
                            ),
                            AttributeGroup.groupOf(
                                Tag.jobAttributes,
                                Types.jobId.of(jobId.toInt()),
                                Types.jobUri.of(URI("ipp://localhost:$PORT/jobs/$jobId")),
                                Types.jobState.of(5), // 5 = processing
                                Types.jobStateReasons.of("processing-to-stop-point")
                            )
                        )
                        
                        response
                    } else {
                        Log.e(TAG, "No document data found in Print-Job request")
                        IppPacket(Status.clientErrorBadRequest, request.requestId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Print-Job request", e)
                    IppPacket(Status.serverErrorInternalError, request.requestId)
                }
            }
            Operation.sendDocument.code -> { // Send-Document operation
                try {
                    Log.d(TAG, "Received Send-Document operation")
                    
                    // Print all headers for debugging
                    val headersList = call.request.headers.entries().map { "${it.key}: ${it.value}" }
                    Log.d(TAG, "Send-Document headers: ${headersList.joinToString(", ")}")
                    
                    // Dump the entire attribute set for debugging
                    Log.d(TAG, "Send-Document ALL attributes: ${request.attributeGroups}")
                    
                    // Get job ID from attributes
                    val jobId = request.attributeGroups
                        .find { it.tag == Tag.operationAttributes }
                        ?.getValues(Types.jobId)
                        ?.firstOrNull() as? Int
                    
                    // Get document format from attributes if available
                    val documentFormat = request.attributeGroups
                        .find { it.tag == Tag.operationAttributes }
                        ?.getValues(Types.documentFormat)
                        ?.firstOrNull()
                        ?.toString() ?: "application/octet-stream"
                    
                    // Special case for macOS printing
                    if (documentFormat == "application/octet-stream" && 
                        (headersList.any { it.contains("darwin", ignoreCase = true) } || 
                         headersList.any { it.contains("Mac OS X", ignoreCase = true) })) {
                        Log.d(TAG, "macOS client detected, treating document as PDF by default")
                    }
                    
                    // Get last-document flag to know if this is the final part
                    val isLastDocument = request.attributeGroups
                        .find { it.tag == Tag.operationAttributes }
                        ?.getValues(Types.lastDocument)
                        ?.firstOrNull() as? Boolean ?: true
                    
                    Log.d(TAG, "Send-Document for job ID: $jobId, format: $documentFormat, last document: $isLastDocument")
                    
                    if (documentData.isNotEmpty()) {
                        Log.d(TAG, "Received document data from Send-Document: ${documentData.size} bytes")
                        
                        // Use the job ID from the request or generate a new one
                        val actualJobId = if (jobId != null && jobId > 0) jobId.toLong() else System.currentTimeMillis()
                        
                        saveDocument(documentData, actualJobId, documentFormat)
                        
                        // Create a success response with job attributes
                        val response = IppPacket(
                            Status.successfulOk,
                            request.requestId,
                            AttributeGroup.groupOf(
                                Tag.operationAttributes,
                                Types.attributesCharset.of("utf-8"),
                                Types.attributesNaturalLanguage.of("en")
                            ),
                            AttributeGroup.groupOf(
                                Tag.jobAttributes,
                                Types.jobId.of(actualJobId.toInt()),
                                Types.jobUri.of(URI("ipp://localhost:$PORT/jobs/$actualJobId")),
                                Types.jobState.of(if (isLastDocument) 9 else 4), // 9 = completed, 4 = processing
                                Types.jobStateReasons.of(if (isLastDocument) "job-completed-successfully" else "job-incoming")
                            )
                        )
                        
                        response
                    } else {
                        Log.e(TAG, "No document data found in Send-Document request")
                        IppPacket(Status.clientErrorBadRequest, request.requestId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Send-Document request", e)
                    IppPacket(Status.serverErrorInternalError, request.requestId)
                }
            }
            Operation.validateJob.code -> { // Validate-Job operation
                IppPacket(Status.successfulOk, request.requestId)
            }
            Operation.createJob.code -> { // Create-Job operation
                try {
                    val jobId = System.currentTimeMillis()
                    Log.d(TAG, "Create-Job operation: Assigning job ID: $jobId")
                    Log.d(TAG, "Job attributes: ${request.attributeGroups}")
                    
                    // Create a response with job attributes
                    val response = IppPacket(
                        Status.successfulOk,
                        request.requestId,
                        AttributeGroup.groupOf(
                            Tag.operationAttributes,
                            Types.attributesCharset.of("utf-8"),
                            Types.attributesNaturalLanguage.of("en")
                        ),
                        AttributeGroup.groupOf(
                            Tag.jobAttributes,
                            Types.jobId.of(jobId.toInt()),
                            Types.jobUri.of(URI("ipp://localhost:$PORT/jobs/$jobId")),
                            Types.jobState.of(3), // 3 = pending
                            Types.jobStateReasons.of("none")
                        )
                    )
                    
                    response
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Create-Job request", e)
                    IppPacket(Status.serverErrorInternalError, request.requestId)
                }
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
        // If custom attributes are set, use them
        if (customIppAttributes != null) {
            Log.d(TAG, "Using custom IPP attributes for printer response")
            return IppPacket(
                Status.successfulOk,
                request.requestId,
                AttributeGroup.groupOf(
                    Tag.operationAttributes,
                    Types.attributesCharset.of("utf-8"),
                    Types.attributesNaturalLanguage.of("en")
                ),
                *customIppAttributes!!.toTypedArray()
            )
        }
        
        // Get the IP address of the device
        val hostAddress = getLocalIpAddress() ?: "127.0.0.1"
        
        // Create printer URI safely
        val printerUri = try {
            // Ensure the address is clean and URI-safe
            val cleanHostAddress = hostAddress.replace("[%:]".toRegex(), "")
            URI.create("ipp://$cleanHostAddress:$PORT/")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating printer URI: ${e.message}")
            URI.create("ipp://127.0.0.1:$PORT/")
        }
        
        // Create printer attributes group
        val printerAttributes = AttributeGroup.groupOf(
            Tag.printerAttributes,
            // Basic printer information
            Types.printerName.of(getPrinterName()),
            Types.printerState.of(PrinterState.idle),
            Types.printerStateReasons.of("none"),
            Types.printerIsAcceptingJobs.of(true),
            Types.printerUri.of(printerUri),
            Types.printerLocation.of("Mobile Device"),
            Types.printerInfo.of("${getPrinterName()} - Mobile PDF Printer"),
            Types.printerMakeAndModel.of("${getPrinterName()} v1.0"),
            
            // Supported document formats
            Types.documentFormatSupported.of(
                "application/pdf", 
                "application/octet-stream",
                "application/vnd.cups-raw",
                "application/vnd.cups-pdf",
                "image/jpeg",
                "image/png",
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
                Operation.getJobAttributes.code,
                Operation.sendDocument.code
            ),
            
            // Capabilities
            Types.colorSupported.of(true)
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
    
    private fun saveDocument(docBytes: ByteArray, jobId: Long = System.currentTimeMillis(), documentFormat: String = "application/octet-stream") {
        try {
            // Log incoming document format
            Log.d(TAG, "Saving document with format: $documentFormat and size: ${docBytes.size} bytes")
            
            // Try to find PDF signature in bytes (%PDF)
            var isPdf = false
            var pdfStartIndex = -1
            
            // Search for PDF header
            for (i in 0 until docBytes.size - 4) {
                if (docBytes[i] == '%'.toByte() && 
                    docBytes[i + 1] == 'P'.toByte() && 
                    docBytes[i + 2] == 'D'.toByte() && 
                    docBytes[i + 3] == 'F'.toByte()) {
                    isPdf = true
                    pdfStartIndex = i
                    Log.d(TAG, "Found PDF signature at position $i")
                    break
                }
            }
            
            // Process based on what we found
            if (isPdf && pdfStartIndex >= 0) {
                // Create PDF file with only the PDF content
                val pdfBytes = docBytes.copyOfRange(pdfStartIndex, docBytes.size)
                val filename = "print_job_${jobId}.pdf"
                val file = File(printJobsDirectory, filename)
                
                Log.d(TAG, "Saving extracted PDF data (${pdfBytes.size} bytes) to: ${file.absolutePath}")
                
                FileOutputStream(file).use { it.write(pdfBytes) }
                
                Log.d(TAG, "PDF document extracted and saved to: ${file.absolutePath}")
                
                if (file.exists() && file.length() > 0) {
                    // Notify that a new job was received
                    val intent = android.content.Intent("com.example.printer.NEW_PRINT_JOB")
                    intent.putExtra("job_path", file.absolutePath)
                    intent.putExtra("job_size", pdfBytes.size)
                    intent.putExtra("job_id", jobId)
                    intent.putExtra("document_format", "application/pdf")
                    intent.putExtra("detected_format", "pdf")
                    Log.d(TAG, "Broadcasting print job notification: ${intent.action}")
                    context.sendBroadcast(intent)
                }
                return
            }
            
            // If we reach here, it's not a PDF or we couldn't find a PDF signature
            // Save raw data first to allow debugging
            val rawFilename = "print_job_${jobId}.raw"
            val rawFile = File(printJobsDirectory, rawFilename)
            
            Log.d(TAG, "Saving original raw data to: ${rawFile.absolutePath}")
            FileOutputStream(rawFile).use { it.write(docBytes) }
            
            // Now try to convert to PDF if possible based on format
            val isPrintableFormat = documentFormat.contains("pdf", ignoreCase = true) || 
                                  documentFormat.contains("postscript", ignoreCase = true) ||
                                  documentFormat.contains("vnd.cups", ignoreCase = true) ||
                                  documentFormat == "application/octet-stream"
            
            if (isPrintableFormat) {
                // Create a PDF wrapper for the content
                val pdfWrapper = createPdfWrapper(docBytes, documentFormat)
                val pdfFilename = "print_job_${jobId}.pdf"
                val pdfFile = File(printJobsDirectory, pdfFilename)
                
                Log.d(TAG, "Creating synthetic PDF with original data: ${pdfFile.absolutePath}")
                FileOutputStream(pdfFile).use { it.write(pdfWrapper) }
                
                if (pdfFile.exists() && pdfFile.length() > 0) {
                    // Notify about the PDF file instead of raw
                    val intent = android.content.Intent("com.example.printer.NEW_PRINT_JOB")
                    intent.putExtra("job_path", pdfFile.absolutePath)
                    intent.putExtra("job_size", pdfWrapper.size)
                    intent.putExtra("job_id", jobId)
                    intent.putExtra("document_format", "application/pdf")
                    intent.putExtra("detected_format", "pdf")
                    Log.d(TAG, "Broadcasting PDF print job notification: ${intent.action}")
                    context.sendBroadcast(intent)
                    
                    // Delete the raw file since we have PDF now
                    rawFile.delete()
                    return
                }
            }
            
            // If PDF conversion fails, notify about the raw file
            if (rawFile.exists() && rawFile.length() > 0) {
                val intent = android.content.Intent("com.example.printer.NEW_PRINT_JOB")
                intent.putExtra("job_path", rawFile.absolutePath)
                intent.putExtra("job_size", docBytes.size)
                intent.putExtra("job_id", jobId)
                intent.putExtra("document_format", documentFormat)
                intent.putExtra("detected_format", "raw")
                Log.d(TAG, "Broadcasting raw print job notification: ${intent.action}")
                context.sendBroadcast(intent)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving document", e)
        }
    }
    
    /**
     * Creates a basic PDF wrapper around arbitrary data
     */
    private fun createPdfWrapper(data: ByteArray, format: String): ByteArray {
        try {
            // Very simple PDF creation
            val header = "%PDF-1.7\n"
            val obj1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
            val obj2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
            val obj3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n"
            
            // Create a stream object with the raw data
            val streamData = "4 0 obj\n<< /Length ${data.size} >>\nstream\n"
            val streamEnd = "\nendstream\nendobj\n"
            
            // PDF trailer
            val xref = "xref\n0 5\n0000000000 65535 f\n0000000010 00000 n\n0000000060 00000 n\n0000000120 00000 n\n0000000210 00000 n\n"
            val trailer = "trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n300\n%%EOF"
            
            // Combine everything
            val pdfContent = header + obj1 + obj2 + obj3 + streamData
            val result = ByteArrayOutputStream()
            result.write(pdfContent.toByteArray())
            result.write(data)
            result.write(streamEnd.toByteArray())
            result.write(xref.toByteArray())
            result.write(trailer.toByteArray())
            
            return result.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating PDF wrapper", e)
            // Return original data if PDF creation fails
            return data
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
                serviceName = getPrinterName()
                serviceType = SERVICE_TYPE
                port = PORT
                
                // Add printer attributes as TXT records
                val hostAddress = getLocalIpAddress() ?: "127.0.0.1"
                val attributes = mapOf(
                    "URF" to "none",
                    "adminurl" to "http://$hostAddress:$PORT/",
                    "pdl" to "application/pdf,image/urf",
                    "txtvers" to "1",
                    "priority" to "30",
                    "qtotal" to "1",
                    "kind" to "document",
                    "TLS" to "1.2"
                )
                
                attributes.forEach { (key, value) ->
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
                    // Skip loopback addresses and IPv6 addresses (which cause URI issues)
                    if (!address.isLoopbackAddress && address is InetAddress && !address.hostAddress.contains(":")) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        // Explicitly return a valid placeholder IPv4 address
        return "127.0.0.1"
    }
} 