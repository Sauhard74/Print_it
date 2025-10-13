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
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.Tag
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.model.MediaCol
import com.hp.jipp.model.MediaColDatabase
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
import com.example.printer.utils.IppAttributesUtils
import com.example.printer.logging.PrinterLogger
import com.example.printer.logging.LogCategory
import com.example.printer.logging.LogLevel
import java.io.FileOutputStream
import com.example.printer.plugins.PluginFramework
import com.example.printer.queue.PrintJob
import com.example.printer.queue.PrintJobQueue
import com.example.printer.queue.PrintJobState

class PrinterService(private val context: Context) {
    private val TAG = "PrinterService"
    private val DEFAULT_PRINTER_NAME = "Android Virtual Printer"
    private val SERVICE_TYPE = "_ipp._tcp."
    private val PORT = 8631 // Using non-privileged port instead of 631
    
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var server: ApplicationEngine? = null
    private var customIppAttributes: List<AttributeGroup>? = null
    private val logger by lazy { PrinterLogger.getInstance(context) }
    private val pluginFramework by lazy { PluginFramework.getInstance(context) }
    
    // Add error simulation properties
    private var simulateErrorMode = false
    private var errorType = "none" // Options: "none", "server-error", "client-error", "aborted", "unsupported-format"
    
    // Service status tracking
    enum class ServiceStatus(val displayName: String, val description: String) {
        STOPPED("Stopped", "Printer service is not running"),
        STARTING("Starting", "Printer service is initializing"),
        RUNNING("Running", "Printer service is active and accepting jobs"),
        ERROR_SIMULATION("Error Mode", "Printer is simulating errors for testing")
    }
    
    private val printJobsDirectory: File by lazy {
        File(context.filesDir, "print_jobs").apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * Returns values of an attribute from the custom IPP attributes, if present
     */
    private fun getCustomAttributeValues(name: String): List<String> {
        val groups = customIppAttributes ?: return emptyList()
        val values = mutableListOf<String>()
        groups.forEach { group ->
            try {
                val attr = group[name]
                if (attr != null) {
                    val count = attr.size
                    if (count > 0) {
                        for (i in 0 until count) {
                            values.add(attr[i].toString())
                        }
                    } else {
                        attr.getValue()?.let { values.add(it.toString()) }
                    }
                }
            } catch (_: Exception) {}
        }
        return values
    }

    /**
     * Checks printer-is-accepting-jobs from custom attributes
     */
    private fun isAcceptingJobsAccordingToCustomAttributes(): Boolean {
        val groups = customIppAttributes ?: return true
        groups.forEach { group ->
            try {
                val attr = group[Types.printerIsAcceptingJobs]
                val v = attr?.getValue() as? Boolean
                if (v != null) return v
            } catch (_: Exception) {}
            try {
                val generic = group["printer-is-accepting-jobs"]
                val v = generic?.toString()?.equals("true", true)
                if (v == true) return true
                if (v == false) return false
            } catch (_: Exception) {}
        }
        return true
    }

    fun getPrinterName(): String {
        return PreferenceUtils.getCustomPrinterName(context)
    }
    
    fun getPort(): Int = PORT
    
    /**
     * Get current service status
     */
    fun getServiceStatus(): ServiceStatus {
        return when {
            simulateErrorMode -> ServiceStatus.ERROR_SIMULATION
            server == null -> ServiceStatus.STOPPED
            registrationListener == null -> ServiceStatus.STARTING
            else -> ServiceStatus.RUNNING
        }
    }
    
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
        
        if (attributes != null) {
            logger.d(LogCategory.IPP_PROTOCOL, TAG, "Custom IPP attributes loaded", metadata = mapOf(
                "groups" to attributes.size,
                "attributes_detail" to attributes.mapIndexed { index, group ->
                    try {
                        var count = 0
                        val iterator = group.iterator()
                        while (iterator.hasNext()) {
                            iterator.next()
                            count++
                        }
                        "Group $index (${group.tag}): $count attributes"
                    } catch (e: Exception) {
                        "Group $index (${group.tag}): error counting"
                    }
                }
            ))
        } else {
            logger.d(LogCategory.IPP_PROTOCOL, TAG, "Custom IPP attributes cleared")
        }
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
                // Handle both standard IPP path and root path for compatibility
                listOf("/ipp/print", "/").forEach { path ->
                    post(path) {
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
                            Log.d(TAG, "Received IPP request on path $path: ${ippRequest.code}")
                            logger.d(LogCategory.IPP_PROTOCOL, TAG, "Request ${ippRequest.operation?.name} on $path",
                                metadata = mapOf(
                                    "requestId" to ippRequest.requestId,
                                    "groups" to ippRequest.attributeGroups.size
                                )
                            )
                            
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
                            val ippOutputStream = IppOutputStream(outputStream)
                            ippOutputStream.write(response)
                            
                            call.respondBytes(
                                outputStream.toByteArray(),
                                ContentType("application", "ipp")
                            )
                            logger.i(LogCategory.IPP_PROTOCOL, TAG, "Responded ${response.status} for ${ippRequest.operation?.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing IPP request on path $path", e)
                            logger.e(LogCategory.IPP_PROTOCOL, TAG, "IPP processing error on $path", e)
                            call.respond(HttpStatusCode.InternalServerError, "Error processing print request")
                        }
                    }
                }
                
                get("/") {
                    call.respondText("${getPrinterName()} Service Running", ContentType.Text.Plain)
                }
                
                get("/ipp/print") {
                    call.respondText("${getPrinterName()} IPP Service Running", ContentType.Text.Plain)
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
            
            // First try: Look for PDF signature throughout the data
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
            
            // Second try: Find the end-of-attributes-tag (0x03)
            var i = 8 // Skip past the 8-byte IPP header
            while (i < requestBytes.size - 1) {
                if (requestBytes[i] == 0x03.toByte()) {
                    // Found the end-of-attributes-tag
                    var endPos = i + 1
                    
                    // Account for potential padding after end of attributes
                    while (endPos < requestBytes.size && 
                          (requestBytes[endPos] == 0x00.toByte() || 
                           requestBytes[endPos] == 0x0D.toByte() || 
                           requestBytes[endPos] == 0x0A.toByte())) {
                        endPos++
                    }
                    
                    if (endPos < requestBytes.size) {
                        val docBytes = requestBytes.copyOfRange(endPos, requestBytes.size)
                        Log.d(TAG, "Extracted ${docBytes.size} bytes of document data after position $endPos")
                        
                        return docBytes
                    }
                    break
                }
                i++
            }
            
            // Last resort: Try to find the highest chunk of non-IPP binary data
            // This is more complex, but look for the longest sequence of bytes that don't look like IPP
            // (For now, log this case and return an empty array)
            Log.d(TAG, "Could not extract document data using standard methods")
            
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
                    // Execute delay simulator FIRST - before any processing or response
                    try {
                        // Create a temporary job for plugin delay processing
                        val tempJob = com.example.printer.queue.PrintJob(
                            id = System.currentTimeMillis(),
                            name = "Temp Job for Delay",
                            filePath = "temp",
                            documentFormat = "application/pdf",
                            size = documentData.size.toLong(),
                            submissionTime = System.currentTimeMillis(),
                            state = com.example.printer.queue.PrintJobState.PENDING
                        )
                        // Use a new coroutine scope to avoid Compose conflicts
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { 
                            pluginFramework.executeBeforeJobProcessing(tempJob)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Plugin delay simulation error: ${e.message}", e)
                    }
                    
                    // Respect custom attributes if provided
                    if (!isAcceptingJobsAccordingToCustomAttributes()) {
                        Log.w(TAG, "Rejecting Print-Job: printer-is-accepting-jobs=false from custom attributes")
                        return IppPacket(Status.serverErrorServiceUnavailable, request.requestId)
                    }
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
                    
                    // Check supported document formats (use custom list if provided)
                    val customSupported = getCustomAttributeValues("document-format-supported")
                    val supportedFormats = if (customSupported.isNotEmpty()) customSupported else listOf(
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
                        logger.w(LogCategory.IPP_PROTOCOL, TAG, "Rejected document format $documentFormat; supported=$supportedFormats")
                        // If custom attributes are set, enforce the constraint; otherwise, continue
                        if (customIppAttributes != null) {
                            Log.w(TAG, "Custom attributes enforcing document format restriction - rejecting $documentFormat")
                            logger.w(LogCategory.DOCUMENT_PROCESSING, TAG, "Custom attributes rejected unsupported format", 
                                metadata = mapOf("format" to documentFormat, "supported" to supportedFormats))
                            return IppPacket(Status.clientErrorDocumentFormatNotSupported, request.requestId)
                        } else {
                            Log.d(TAG, "No custom attributes set - accepting unsupported format $documentFormat")
                        }
                    }
                    
                    if (documentData.isNotEmpty()) {
                        Log.d(TAG, "Received document data: ${documentData.size} bytes")
                        logger.d(LogCategory.DOCUMENT_PROCESSING, TAG, "Received document data", metadata = mapOf("bytes" to documentData.size))
                        
                        // Generate a unique job ID
                        val jobId = System.currentTimeMillis()
                        Log.d(TAG, "Processing Print-Job with ID: $jobId, document size: ${documentData.size} bytes, format: $documentFormat")
                        
                        // Register job in queue for plugin hooks
                        val job = com.example.printer.queue.PrintJob(
                            id = jobId,
                            name = request.attributeGroups.find { it.tag == Tag.operationAttributes }?.getValues(Types.jobName)?.firstOrNull()?.toString() ?: "Print Job",
                            filePath = File(printJobsDirectory, "print_job_${jobId}.tmp").absolutePath,
                            documentFormat = documentFormat,
                            size = documentData.size.toLong(),
                            submissionTime = System.currentTimeMillis(),
                            state = com.example.printer.queue.PrintJobState.PENDING,
                            stateReasons = listOf("none"),
                            jobOriginatingUserName = request.attributeGroups.find { it.tag == Tag.operationAttributes }?.getValues(Types.requestingUserName)?.firstOrNull()?.toString() ?: "anonymous",
                            impressionsCompleted = 0,
                            metadata = emptyMap()
                        )
                        // Plugin before hooks already executed at operation start
                        
                        // Execute plugin processing hook (allows delay/modification)
                        var finalDocumentData = documentData
                        
                        // Log original document first bytes for debugging
                        val originalFirstBytes = documentData.take(16).joinToString(" ") { "%02X".format(it) }
                        Log.d(TAG, "Original document first 16 bytes: $originalFirstBytes")
                        
                        try {
                            val pluginResult = kotlinx.coroutines.runBlocking { pluginFramework.executeJobProcessing(job, documentData) }
                            if (pluginResult?.processedBytes != null) {
                                Log.d(TAG, "Plugin modified document: original=${documentData.size} bytes, modified=${pluginResult.processedBytes.size} bytes")
                                // Log first 16 bytes of modified document for debugging
                                val firstBytes = pluginResult.processedBytes.take(16).joinToString(" ") { "%02X".format(it) }
                                Log.d(TAG, "Plugin output first 16 bytes: $firstBytes")
                                finalDocumentData = pluginResult.processedBytes
                            } else {
                                Log.d(TAG, "Plugin returned null, using original document")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Plugin processJob raised: ${e.message}", e)
                        }
                        
                        // Save the document (possibly modified by plugins) with format info
                        saveDocument(finalDocumentData, jobId, documentFormat)
                        
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
                        logger.i(LogCategory.PRINT_JOB, TAG, "Accepted Print-Job", jobId = jobId,
                            metadata = mapOf("format" to documentFormat))
                        
                        response
                    } else {
                        Log.e(TAG, "No document data found in Print-Job request")
                        logger.e(LogCategory.PRINT_JOB, TAG, "No document data in Print-Job")
                        IppPacket(Status.clientErrorBadRequest, request.requestId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Print-Job request", e)
                    IppPacket(Status.serverErrorInternalError, request.requestId)
                }
            }
            Operation.sendDocument.code -> { // Send-Document operation
                try {
                    // Execute delay simulator FIRST - before any processing or response
                    try {
                        val tempJob = com.example.printer.queue.PrintJob(
                            id = System.currentTimeMillis(),
                            name = "Temp Job for Send-Document Delay",
                            filePath = "temp",
                            documentFormat = "application/pdf",
                            size = documentData.size.toLong(),
                            submissionTime = System.currentTimeMillis(),
                            state = com.example.printer.queue.PrintJobState.PENDING
                        )
                        // Use a new coroutine scope to avoid Compose conflicts
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { 
                            pluginFramework.executeBeforeJobProcessing(tempJob)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Plugin delay simulation error: ${e.message}", e)
                    }
                    
                    if (!isAcceptingJobsAccordingToCustomAttributes()) {
                        Log.w(TAG, "Rejecting Send-Document: printer-is-accepting-jobs=false from custom attributes")
                        return IppPacket(Status.serverErrorServiceUnavailable, request.requestId)
                    }
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
                        val job = com.example.printer.queue.PrintJob(
                            id = actualJobId,
                            name = request.attributeGroups.find { it.tag == Tag.operationAttributes }?.getValues(Types.jobName)?.firstOrNull()?.toString() ?: "Send Document",
                            filePath = File(printJobsDirectory, "print_job_${actualJobId}.tmp").absolutePath,
                            documentFormat = documentFormat,
                            size = documentData.size.toLong(),
                            submissionTime = System.currentTimeMillis(),
                            state = com.example.printer.queue.PrintJobState.PENDING,
                            stateReasons = listOf("none"),
                            jobOriginatingUserName = request.attributeGroups.find { it.tag == Tag.operationAttributes }?.getValues(Types.requestingUserName)?.firstOrNull()?.toString() ?: "anonymous",
                            impressionsCompleted = 0,
                            metadata = emptyMap()
                        )
                        // Plugin before hooks already executed at operation start
                        
                        // Execute plugin processing hook (allows delay/modification)
                        var finalDocumentData = documentData
                        try {
                            val pluginResult = kotlinx.coroutines.runBlocking { pluginFramework.executeJobProcessing(job, documentData) }
                            if (pluginResult?.processedBytes != null) {
                                Log.d(TAG, "Plugin modified Send-Document: original=${documentData.size} bytes, modified=${pluginResult.processedBytes.size} bytes")
                                finalDocumentData = pluginResult.processedBytes
                            }
                        } catch (e: Exception) { Log.w(TAG, "Plugin process hook error: ${e.message}") }
                        
                        // Save the document (possibly modified by plugins) with format info
                        saveDocument(finalDocumentData, actualJobId, documentFormat)
                        
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
                        logger.e(LogCategory.PRINT_JOB, TAG, "No document data in Send-Document", jobId = jobId?.toLong())
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
                val baseResponse = createPrinterAttributesResponse(request)
                // Allow plugins to customize attributes
                try {
                    val customized = kotlinx.coroutines.runBlocking { pluginFramework.executeIppAttributeCustomization(baseResponse.attributeGroups.toList()) }
                    return IppPacket(
                        baseResponse.status,
                        baseResponse.requestId,
                        *customized.toTypedArray()
                    )
                } catch (_: Exception) {}
                baseResponse
            }
            Operation.cancelJob.code -> { // Cancel-Job operation
                try {
                    val jobId = request.attributeGroups
                        .find { it.tag == Tag.operationAttributes }
                        ?.getValues(Types.jobId)
                        ?.firstOrNull() as? Int

                    if (jobId == null) {
                        IppPacket(Status.clientErrorBadRequest, request.requestId)
                    } else {
                        val queue = PrintJobQueue.getInstance(context)
                        if (queue.cancelJob(jobId.toLong())) {
                            IppPacket(Status.successfulOk, request.requestId)
                        } else {
                            IppPacket(Status.clientErrorNotFound, request.requestId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing Cancel-Job request", e)
                    IppPacket(Status.serverErrorInternalError, request.requestId)
                }
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
            logger.d(LogCategory.IPP_PROTOCOL, TAG, "Using custom IPP attributes", metadata = mapOf(
                "groups" to customIppAttributes!!.size,
                "total_attributes" to customIppAttributes!!.sumOf { group ->
                    try {
                        // Count attributes in each group safely
                        var count = 0
                        val iterator = group.iterator()
                        while (iterator.hasNext()) {
                            iterator.next()
                            count++
                        }
                        count
                    } catch (e: Exception) {
                        Log.w(TAG, "Error counting attributes in group", e)
                        0
                    }
                }
            ))
            
            // Log specific attributes being applied
            customIppAttributes!!.forEach { group ->
                try {
                    Log.d(TAG, "Custom attribute group: ${group.tag}")
                    val iterator = group.iterator()
                    while (iterator.hasNext()) {
                        val attr = iterator.next()
                        Log.d(TAG, "  - ${attr.name}: ${attr.getValue()}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error logging attribute group", e)
                }
            }
            
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
        
        // Create printer attributes group with all required IPP attributes
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
            
            // Required printer attributes for IPP compliance
            Types.printerUpTime.of(System.currentTimeMillis().toInt() / 1000), // Uptime in seconds since boot
            Types.printerUriSupported.of(printerUri),
            Types.queuedJobCount.of(0), // Number of jobs currently queued
            
            // Charset and language support (required by RFC 8011)
            Types.charsetConfigured.of("utf-8"),
            Types.charsetSupported.of("utf-8"),
            Types.naturalLanguageConfigured.of("en"),
            Types.generatedNaturalLanguageSupported.of("en"),
            
            // IPP version support (required)
            Types.ippVersionsSupported.of("1.1", "2.0"),
            
            // Security and authentication (required)
            Types.uriSecuritySupported.of("none"),
            Types.uriAuthenticationSupported.of("none"),
            
            // Compression support (required)
            Types.compressionSupported.of("none"),
            
            // PDL override support (required)
            Types.pdlOverrideSupported.of("not-attempted"),
            
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
            Types.documentFormatDefault.of("application/pdf"),
            
            // Media support
            Types.mediaDefault.of("iso_a4_210x297mm"),
            Types.mediaSupported.of(
                "iso_a4_210x297mm", 
                "iso_a5_148x210mm",
                "na_letter_8.5x11in", 
                "na_legal_8.5x14in"
            ),
            Types.mediaTypeSupported.of("stationery", "photographic"),
            Types.mediaSourceSupported.of("auto", "main"),
            Types.mediaColDatabase.of(
              listOf<MediaColDatabase>(
                // A4
                MediaColDatabase(
                  mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(21000), IntOrIntRange(29700)),
                  mediaBottomMargin = 0,
                  mediaTopMargin = 0,
                  mediaLeftMargin = 0,
                  mediaRightMargin = 0,
                ),
                // A5
                MediaColDatabase(
                  mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(14800), IntOrIntRange(21000)),
                  mediaBottomMargin = 0,
                  mediaTopMargin = 0,
                  mediaLeftMargin = 0,
                  mediaRightMargin = 0,
                ),
                // Letter
                MediaColDatabase(
                  mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(21590), IntOrIntRange(27940)),
                  mediaBottomMargin = 0,
                  mediaTopMargin = 0,
                  mediaLeftMargin = 0,
                  mediaRightMargin = 0,
                ),
                // Legal
                MediaColDatabase(
                  mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(21590), IntOrIntRange(35560)),
                  mediaBottomMargin = 0,
                  mediaTopMargin = 0,
                  mediaLeftMargin = 0,
                  mediaRightMargin = 0,
                ),
              )
            ),

            // Job attributes
            Types.jobSheetsDefault.of("none"),
            Types.jobSheetsSupported.of("none", "standard"),
            
            // Operations supported
            Types.operationsSupported.of(
                Operation.printJob.code,
                Operation.validateJob.code,
                Operation.createJob.code,
                Operation.cancelJob.code,
                Operation.getPrinterAttributes.code,
                Operation.getJobAttributes.code,
                Operation.sendDocument.code
            ),
            
            // Capabilities
            Types.colorSupported.of(true),
            Types.printerResolutionSupported.of(Resolution(300, 300, ResolutionUnit.dotsPerInch))
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
            
            // First, try to extract actual document content from IPP wrapper
            val extractedContent = extractDocumentFromIPP(docBytes)
            if (extractedContent != null) {
                Log.d(TAG, "Successfully extracted document content (${extractedContent.size} bytes) from IPP wrapper")
                saveExtractedDocument(extractedContent, jobId, documentFormat)
                return
            }
            
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
                    Log.d(TAG, "Successfully saved PDF document: ${file.absolutePath} (${file.length()} bytes)")
                    // Notify that a new job was received
                    val intent = android.content.Intent("com.example.printer.NEW_PRINT_JOB")
                    intent.putExtra("job_path", file.absolutePath)
                    intent.putExtra("job_size", pdfBytes.size)
                    intent.putExtra("job_id", jobId)
                    intent.putExtra("document_format", "application/pdf")
                    intent.putExtra("detected_format", "pdf")
                    Log.d(TAG, "Broadcasting print job notification: ${intent.action}")
                    context.sendBroadcast(intent)
                } else {
                    Log.e(TAG, "Failed to save PDF document or file is empty")
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
     * Creates a standard-compliant PDF wrapper around arbitrary data
     */
    private fun createPdfWrapper(data: ByteArray, format: String): ByteArray {
        try {
            // Check if it's already a PDF
            if (data.size > 4 &&
                data[0] == '%'.toByte() && 
                data[1] == 'P'.toByte() && 
                data[2] == 'D'.toByte() && 
                data[3] == 'F'.toByte()) {
                Log.d(TAG, "Data is already a valid PDF, returning as-is")
                return data
            }
            
            // Create a proper PDF structure
            val baos = ByteArrayOutputStream()
            
            // PDF header
            val header = "%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n"
            baos.write(header.toByteArray())
            
            // Keep track of positions for the xref table
            val objPositions = mutableMapOf<Int, Int>()
            
            // Object 1: Catalog
            objPositions[1] = baos.size()
            baos.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".toByteArray())
            
            // Object 2: Pages
            objPositions[2] = baos.size()
            baos.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".toByteArray())
            
            // Object 3: Page
            objPositions[3] = baos.size()
            baos.write("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n".toByteArray())
            
            // Object 4: Content Stream with data
            objPositions[4] = baos.size()
            baos.write("4 0 obj\n<< /Length ${data.size} >>\nstream\n".toByteArray())
            baos.write(data)
            baos.write("\nendstream\nendobj\n".toByteArray())
            
            // Add info object with metadata
            objPositions[5] = baos.size()
            val dateStr = java.text.SimpleDateFormat("yyyyMMddHHmmss").format(java.util.Date())
            baos.write("""
                5 0 obj
                << 
                   /Title (Android Virtual Printer Document)
                   /Author (Android Virtual Printer)
                   /Subject (Print Job)
                   /Keywords (print, virtual printer)
                   /Creator (Android Virtual Printer)
                   /Producer (Android Virtual Printer v1.0)
                   /CreationDate (D:$dateStr)
                >>
                endobj
                
            """.trimIndent().toByteArray())
            
            // XRef table
            val xrefPosition = baos.size()
            baos.write("xref\n0 6\n".toByteArray())
            baos.write("0000000000 65535 f \n".toByteArray())  // Object 0 entry
            
            // Write the positions for each object
            for (i in 1..5) {
                val pos = objPositions[i] ?: 0
                val posStr = pos.toString().padStart(10, '0')
                baos.write("$posStr 00000 n \n".toByteArray())
            }
            
            // Trailer
            baos.write("""
                trailer
                <<
                   /Size 6
                   /Root 1 0 R
                   /Info 5 0 R
                >>
                startxref
                $xrefPosition
                %%EOF
            """.trimIndent().toByteArray())
            
            return baos.toByteArray()
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
                    "rp" to "ipp/print", // Resource path for IPP
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
    
    /**
     * Extracts actual document content from IPP wrapper
     */
    private fun extractDocumentFromIPP(ippData: ByteArray): ByteArray? {
        try {
            Log.d(TAG, "Attempting to extract document from IPP wrapper (${ippData.size} bytes)")
            
            // Look for common document signatures within the IPP data
            val signatures = mapOf(
                "PDF" to byteArrayOf('%'.toByte(), 'P'.toByte(), 'D'.toByte(), 'F'.toByte()),
                "JPEG" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                "PNG" to byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()),
                "PostScript" to byteArrayOf('%'.toByte(), '!'.toByte(), 'P'.toByte(), 'S'.toByte())
            )
            
            for ((format, signature) in signatures) {
                val position = findPattern(ippData, signature)
                if (position >= 0) {
                    Log.d(TAG, "Found $format signature at position $position")
                    val extracted = ippData.copyOfRange(position, ippData.size)
                    Log.d(TAG, "Extracted $format document: ${extracted.size} bytes")
                    return extracted
                }
            }
            
            // If no signature found, try to find content after HTTP headers
            val contentStart = findContentStart(ippData)
            if (contentStart >= 0) {
                Log.d(TAG, "Found content start at position $contentStart")
                val extracted = ippData.copyOfRange(contentStart, ippData.size)
                Log.d(TAG, "Extracted content after headers: ${extracted.size} bytes")
                return extracted
            }
            
            Log.w(TAG, "No document content found in IPP wrapper")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting document from IPP wrapper", e)
            return null
        }
    }
    
    /**
     * Finds the start of content after HTTP/IPP headers
     */
    private fun findContentStart(data: ByteArray): Int {
        // Look for double CRLF which separates headers from content
        val doubleCRLF = "\r\n\r\n".toByteArray()
        val position = findPattern(data, doubleCRLF)
        if (position >= 0) {
            return position + doubleCRLF.size
        }
        
        // Look for double LF
        val doubleLF = "\n\n".toByteArray()
        val position2 = findPattern(data, doubleLF)
        if (position2 >= 0) {
            return position2 + doubleLF.size
        }
        
        return -1
    }
    
    /**
     * Finds a pattern in byte array
     */
    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..(data.size - pattern.size)) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
    
    /**
     * Saves extracted document content with automatic decompression
     */
    private fun saveExtractedDocument(content: ByteArray, jobId: Long, originalFormat: String) {
        try {
            // Log first 16 bytes of original content for debugging
            val originalFirstBytes = content.take(16).joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "Original content first 16 bytes: $originalFirstBytes")
            
            // Try decompression and detect format
            val (documentType, actualBytes) = com.example.printer.utils.DocumentTypeUtils.detectDocumentTypeWithDecompression(content)
            
            // Generate filename with correct extension
            val filename = com.example.printer.utils.DocumentTypeUtils.generateFilename(jobId, documentType)
            val file = File(printJobsDirectory, filename)
            
            Log.d(TAG, "Saving ${documentType.name} document (${actualBytes.size} bytes) to: ${file.absolutePath}")
            
            // Save the (possibly decompressed) document
            FileOutputStream(file).use { it.write(actualBytes) }
            
            if (file.exists() && file.length() > 0) {
                // Notify that a new job was received
                val intent = android.content.Intent("com.example.printer.NEW_PRINT_JOB")
                intent.putExtra("job_path", file.absolutePath)
                intent.putExtra("job_size", actualBytes.size)
                intent.putExtra("job_id", jobId)
                intent.putExtra("document_format", documentType.mimeType)
                intent.putExtra("detected_format", documentType.extension)
                Log.d(TAG, "Broadcasting extracted document notification: ${intent.action}")
                context.sendBroadcast(intent)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving extracted document", e)
        }
    }
    
    
    // Plugin Management Methods
    
    /**
     * Load a plugin by ID
     */
    suspend fun loadPlugin(pluginId: String): Boolean {
        return try {
            val result = pluginFramework.loadPlugin(pluginId)
            if (result) {
                logger.i(LogCategory.SYSTEM, TAG, "Plugin loaded: $pluginId")
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Failed to load plugin: $pluginId")
            }
            result
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error loading plugin: $pluginId", e)
            false
        }
    }
    
    /**
     * Unload a plugin by ID
     */
    suspend fun unloadPlugin(pluginId: String): Boolean {
        return try {
            val result = pluginFramework.unloadPlugin(pluginId)
            if (result) {
                logger.i(LogCategory.SYSTEM, TAG, "Plugin unloaded: $pluginId")
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Failed to unload plugin: $pluginId")
            }
            result
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error unloading plugin: $pluginId", e)
            false
        }
    }
    
    /**
     * Update plugin configuration
     */
    suspend fun updatePluginConfiguration(pluginId: String, config: Map<String, Any>): Boolean {
        return try {
            val result = pluginFramework.updatePluginConfiguration(pluginId, config)
            if (result) {
                logger.i(LogCategory.SYSTEM, TAG, "Plugin configuration updated: $pluginId")
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Failed to update plugin configuration: $pluginId")
            }
            result
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error updating plugin configuration: $pluginId", e)
            false
        }
    }
    
    /**
     * Get plugin configuration
     */
    fun getPluginConfiguration(pluginId: String): Map<String, Any>? {
        return pluginFramework.getPluginConfiguration(pluginId)
    }
    
    /**
     * Test error injection plugin with multiple sample jobs
     */
    suspend fun testErrorInjectionPlugin(): String {
        return try {
            // Load the error injection plugin if not already loaded
            val loaded = loadPlugin("error_injection")
            if (!loaded) {
                return "Failed to load error injection plugin"
            }
            
            val results = mutableListOf<String>()
            var successCount = 0
            var errorCount = 0
            val totalAttempts = 10
            
            repeat(totalAttempts) { attempt ->
                try {
                    // Create a test job
                    val currentTime = System.currentTimeMillis()
                    val testJob = PrintJob(
                        id = currentTime + attempt,
                        name = "Error Test Job #${attempt + 1}",
                        filePath = "test_error.pdf",
                        documentFormat = "application/pdf",
                        size = 1024L,
                        submissionTime = currentTime,
                        state = PrintJobState.PENDING,
                        metadata = mapOf("test" to true, "attempt" to attempt + 1)
                    )
                    
                    // Try to process the job - error injection happens in beforeJobProcessing
                    val continueProcessing = pluginFramework.executeBeforeJobProcessing(testJob)
                    
                    if (continueProcessing) {
                        // Get metadata about the plugin
                        val result = pluginFramework.executeJobProcessing(testJob, byteArrayOf())
                        val lastError = result?.customMetadata?.get("last_injected_error") as? String ?: "none"
                        
                        successCount++
                        results.add("✅ Job #${attempt + 1}: Success (last error: $lastError)")
                    }
                    
                } catch (e: Exception) {
                    errorCount++
                    val errorType = when (e) {
                        is java.net.ConnectException -> "Network"
                        is IllegalArgumentException -> "Format"
                        is SecurityException -> "Authorization"
                        is IllegalStateException -> "Queue"
                        else -> when {
                            e.message?.contains("Memory Error") == true -> "Memory"
                            else -> "Runtime"
                        }
                    }
                    results.add("❌ Job #${attempt + 1}: $errorType Error - ${e.message}")
                }
            }
            
            val summary = "Error Injection Test Results:\n" +
                    "Total attempts: $totalAttempts\n" +
                    "Successful: $successCount\n" +
                    "Errors injected: $errorCount\n" +
                    "Error rate: ${(errorCount.toFloat() / totalAttempts * 100).toInt()}%\n\n" +
                    "Details:\n" + results.joinToString("\n")
            
            logger.i(LogCategory.SYSTEM, TAG, "Error injection test completed", 
                metadata = mapOf<String, Any>(
                    "total_attempts" to totalAttempts,
                    "successful_jobs" to successCount,
                    "injected_errors" to errorCount,
                    "error_rate_percent" to (errorCount.toFloat() / totalAttempts * 100).toInt()
                )
            )
            
            summary
            
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error testing error injection plugin", e)
            "Error testing error injection: ${e.message}"
        }
    }
    
    /**
     * Test delay simulator plugin with a sample job
     */
    suspend fun testDelaySimulatorPlugin(): String {
        return try {
            // Load the delay simulator plugin if not already loaded
            val loaded = loadPlugin("delay_simulator")
            if (!loaded) {
                return "Failed to load delay simulator plugin"
            }
            
            // Create a test job
            val currentTime = System.currentTimeMillis()
            val testJob = PrintJob(
                id = currentTime,
                name = "Delay Test Job",
                filePath = "test.pdf",
                documentFormat = "application/pdf",
                size = 1024L,
                submissionTime = currentTime,
                state = PrintJobState.PENDING,
                metadata = mapOf("test" to true)
            )
            
            val startTime = System.currentTimeMillis()
            
            // Execute the plugin before processing (where delay happens)
            val continueProcessing = pluginFramework.executeBeforeJobProcessing(testJob)
            
            val endTime = System.currentTimeMillis()
            val actualDelay = endTime - startTime
            
            // Also get metadata from processJob for expected delay info
            val result = pluginFramework.executeJobProcessing(testJob, byteArrayOf())
            val expectedDelay = result?.customMetadata?.get("simulated_delay_ms") as? Number
            
            logger.i(LogCategory.SYSTEM, TAG, "Delay simulator test completed", 
                metadata = mapOf<String, Any>(
                    "actual_delay_ms" to actualDelay,
                    "expected_delay_ms" to (expectedDelay ?: 0),
                    "test_job_id" to testJob.id
                )
            )
            
            "Delay simulator test completed! Actual delay: ${actualDelay}ms, Expected: ${expectedDelay}ms"
            
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error testing delay simulator plugin", e)
            "Error testing delay simulator: ${e.message}"
        }
    }
} 
