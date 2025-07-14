package com.example.printer.data.repositories

import android.content.Context
import com.example.printer.domain.entities.PrintJob
import com.example.printer.domain.repositories.PrintJobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import timber.log.Timber

/**
 * Implementation of PrintJobRepository
 * Manages print job storage, metadata, and provides export capabilities for QA teams
 */
class PrintJobRepositoryImpl(
    private val context: Context
) : PrintJobRepository {

    private val printJobsFlow = MutableStateFlow<List<PrintJob>>(emptyList())
    private val mutex = Mutex()
    
    private val printJobsDirectory: File by lazy {
        File(context.filesDir, "print_jobs").apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val metadataDirectory: File by lazy {
        File(context.filesDir, "job_metadata").apply {
            if (!exists()) mkdirs()
        }
    }
    
    private val exportDirectory: File by lazy {
        File(context.filesDir, "exports").apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        // Load existing print jobs on initialization
        loadExistingPrintJobs()
    }

    override fun observePrintJobs(): Flow<List<PrintJob>> {
        return printJobsFlow.asStateFlow()
    }

    override suspend fun getAllPrintJobs(): List<PrintJob> {
        return printJobsFlow.value
    }

    override suspend fun getPrintJobById(id: Long): PrintJob? {
        return printJobsFlow.value.find { it.id == id }
    }

    override suspend fun savePrintJob(printJob: PrintJob): PrintJob {
        mutex.withLock {
            try {
                val jobWithId = if (printJob.id == 0L) {
                    printJob.copy(id = generateJobId())
                } else {
                    printJob
                }
                
                // Save metadata
                saveJobMetadata(jobWithId)
                
                // Update the flow
                val currentJobs = printJobsFlow.value.toMutableList()
                val existingIndex = currentJobs.indexOfFirst { it.id == jobWithId.id }
                
                if (existingIndex >= 0) {
                    currentJobs[existingIndex] = jobWithId
                } else {
                    currentJobs.add(jobWithId)
                }
                
                printJobsFlow.value = currentJobs.sortedByDescending { it.receivedAt }
                
                Timber.d("Saved print job: ${jobWithId.fileName} (ID: ${jobWithId.id})")
                return jobWithId
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to save print job: ${printJob.fileName}")
                throw e
            }
        }
    }

    override suspend fun updatePrintJob(printJob: PrintJob): Boolean {
        mutex.withLock {
            try {
                val currentJobs = printJobsFlow.value.toMutableList()
                val index = currentJobs.indexOfFirst { it.id == printJob.id }
                
                if (index >= 0) {
                    currentJobs[index] = printJob
                    printJobsFlow.value = currentJobs.sortedByDescending { it.receivedAt }
                    
                    // Update metadata
                    saveJobMetadata(printJob)
                    
                    Timber.d("Updated print job: ${printJob.fileName} (ID: ${printJob.id})")
                    return true
                } else {
                    Timber.w("Print job not found for update: ID ${printJob.id}")
                    return false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to update print job: ${printJob.fileName}")
                return false
            }
        }
    }

    override suspend fun deletePrintJob(id: Long): Boolean {
        mutex.withLock {
            try {
                val currentJobs = printJobsFlow.value.toMutableList()
                val jobToDelete = currentJobs.find { it.id == id }
                
                if (jobToDelete != null) {
                    // Delete the actual file
                    val file = File(jobToDelete.filePath)
                    if (file.exists()) {
                        file.delete()
                        Timber.d("Deleted file: ${file.absolutePath}")
                    }
                    
                    // Delete metadata
                    deleteJobMetadata(id)
                    
                    // Remove from list
                    currentJobs.removeAll { it.id == id }
                    printJobsFlow.value = currentJobs
                    
                    Timber.d("Deleted print job: ${jobToDelete.fileName} (ID: $id)")
                    return true
                } else {
                    Timber.w("Print job not found for deletion: ID $id")
                    return false
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete print job: ID $id")
                return false
            }
        }
    }

    override suspend fun deleteAllPrintJobs(): Int {
        mutex.withLock {
            try {
                val currentJobs = printJobsFlow.value
                val deletedCount = currentJobs.size
                
                // Delete all files
                currentJobs.forEach { job ->
                    val file = File(job.filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                    deleteJobMetadata(job.id)
                }
                
                // Clear the directory
                printJobsDirectory.listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
                
                metadataDirectory.listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
                
                printJobsFlow.value = emptyList()
                
                Timber.d("Deleted all print jobs: $deletedCount jobs")
                return deletedCount
                
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete all print jobs")
                return 0
            }
        }
    }

    override suspend fun getPrintJobsStatistics(): PrintJobRepository.PrintJobStatistics {
        val jobs = printJobsFlow.value
        
        return PrintJobRepository.PrintJobStatistics(
            totalJobs = jobs.size,
            totalSizeBytes = jobs.sumOf { it.sizeBytes },
            pdfJobs = jobs.count { it.isPdf() },
            imageJobs = jobs.count { it.isImage() },
            otherJobs = jobs.count { !it.isPdf() && !it.isImage() },
            averageSizeBytes = if (jobs.isNotEmpty()) jobs.sumOf { it.sizeBytes } / jobs.size else 0L,
            oldestJobDate = jobs.minByOrNull { it.receivedAt }?.receivedAt,
            newestJobDate = jobs.maxByOrNull { it.receivedAt }?.receivedAt
        )
    }

    override suspend fun searchPrintJobs(query: String): List<PrintJob> {
        return if (query.isBlank()) {
            printJobsFlow.value
        } else {
            printJobsFlow.value.filter { job ->
                job.fileName.contains(query, ignoreCase = true) ||
                job.originalFormat.contains(query, ignoreCase = true) ||
                job.detectedFormat.contains(query, ignoreCase = true) ||
                job.clientInfo.ipAddress.contains(query, ignoreCase = true) ||
                job.documentInfo.title?.contains(query, ignoreCase = true) == true
            }
        }
    }

    override suspend fun getPrintJobsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<PrintJob> {
        return printJobsFlow.value.filter { job ->
            job.receivedAt >= startDate && job.receivedAt <= endDate
        }
    }

    /**
     * Exports print job logs for Chromium QA analysis
     * Creates comprehensive export with metadata and debugging information
     */
    suspend fun exportPrintJobLogs(
        startDate: LocalDateTime? = null,
        endDate: LocalDateTime? = null,
        includeFileContents: Boolean = false
    ): File {
        val jobs = if (startDate != null && endDate != null) {
            getPrintJobsByDateRange(startDate, endDate)
        } else {
            getAllPrintJobs()
        }
        
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val exportFile = File(exportDirectory, "print_job_export_$timestamp.json")
        
        val exportData = createExportData(jobs, includeFileContents)
        
        exportFile.writeText(exportData)
        
        Timber.i("Exported ${jobs.size} print jobs to: ${exportFile.absolutePath}")
        return exportFile
    }

    private fun loadExistingPrintJobs() {
        try {
            val jobs = mutableListOf<PrintJob>()
            
            // Load from metadata files
            metadataDirectory.listFiles { file ->
                file.extension == "json"
            }?.forEach { metadataFile ->
                try {
                    val jobData = loadJobMetadata(metadataFile)
                    if (jobData != null) {
                        jobs.add(jobData)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load metadata from: ${metadataFile.name}")
                }
            }
            
            printJobsFlow.value = jobs.sortedByDescending { it.receivedAt }
            Timber.d("Loaded ${jobs.size} existing print jobs")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load existing print jobs")
        }
    }

    private fun generateJobId(): Long {
        return System.currentTimeMillis()
    }

    private fun saveJobMetadata(printJob: PrintJob) {
        try {
            val metadataFile = File(metadataDirectory, "job_${printJob.id}.json")
            val jsonData = serializePrintJob(printJob)
            metadataFile.writeText(jsonData)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save metadata for job: ${printJob.id}")
        }
    }

    private fun deleteJobMetadata(jobId: Long) {
        try {
            val metadataFile = File(metadataDirectory, "job_$jobId.json")
            if (metadataFile.exists()) {
                metadataFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete metadata for job: $jobId")
        }
    }

    private fun loadJobMetadata(metadataFile: File): PrintJob? {
        return try {
            val jsonData = metadataFile.readText()
            deserializePrintJob(jsonData)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load job metadata from: ${metadataFile.name}")
            null
        }
    }

    private fun createExportData(jobs: List<PrintJob>, includeFileContents: Boolean): String {
        // Create comprehensive export for QA analysis
        return """
        {
            "export_info": {
                "timestamp": "${LocalDateTime.now()}",
                "job_count": ${jobs.size},
                "include_file_contents": $includeFileContents,
                "version": "1.0.0"
            },
            "statistics": ${serializeStatistics(jobs)},
            "jobs": [
                ${jobs.joinToString(",\n") { job ->
                    serializePrintJobForExport(job, includeFileContents)
                }}
            ]
        }
        """.trimIndent()
    }

    private fun serializePrintJob(printJob: PrintJob): String {
        // TODO: Implement proper JSON serialization
        // For now, return a placeholder
        return "{\"id\": ${printJob.id}, \"fileName\": \"${printJob.fileName}\"}"
    }

    private fun deserializePrintJob(jsonData: String): PrintJob {
        // TODO: Implement proper JSON deserialization
        // For now, return a placeholder
        return PrintJob(
            id = 1L,
            fileName = "placeholder.pdf",
            filePath = "/placeholder",
            originalFormat = "application/pdf",
            detectedFormat = "application/pdf",
            sizeBytes = 1024L,
            receivedAt = LocalDateTime.now(),
            clientInfo = PrintJob.ClientInfo("127.0.0.1", null, null, null),
            documentInfo = PrintJob.DocumentInfo(null, null, PrintJob.ColorMode.UNKNOWN, null, null, null),
            status = PrintJob.PrintJobStatus.COMPLETED
        )
    }

    private fun serializeStatistics(jobs: List<PrintJob>): String {
        val stats = PrintJobRepository.PrintJobStatistics(
            totalJobs = jobs.size,
            totalSizeBytes = jobs.sumOf { it.sizeBytes },
            pdfJobs = jobs.count { it.isPdf() },
            imageJobs = jobs.count { it.isImage() },
            otherJobs = jobs.count { !it.isPdf() && !it.isImage() },
            averageSizeBytes = if (jobs.isNotEmpty()) jobs.sumOf { it.sizeBytes } / jobs.size else 0L,
            oldestJobDate = jobs.minByOrNull { it.receivedAt }?.receivedAt,
            newestJobDate = jobs.maxByOrNull { it.receivedAt }?.receivedAt
        )
        
        return """
        {
            "total_jobs": ${stats.totalJobs},
            "total_size_bytes": ${stats.totalSizeBytes},
            "pdf_jobs": ${stats.pdfJobs},
            "image_jobs": ${stats.imageJobs},
            "other_jobs": ${stats.otherJobs},
            "average_size_bytes": ${stats.averageSizeBytes}
        }
        """.trimIndent()
    }

    private fun serializePrintJobForExport(printJob: PrintJob, includeFileContents: Boolean): String {
        // TODO: Implement comprehensive export serialization
        return """
        {
            "id": ${printJob.id},
            "fileName": "${printJob.fileName}",
            "receivedAt": "${printJob.receivedAt}",
            "sizeBytes": ${printJob.sizeBytes},
            "originalFormat": "${printJob.originalFormat}",
            "detectedFormat": "${printJob.detectedFormat}",
            "status": "${printJob.status}",
            "clientInfo": {
                "ipAddress": "${printJob.clientInfo.ipAddress}",
                "userAgent": "${printJob.clientInfo.userAgent ?: ""}",
                "operatingSystem": "${printJob.clientInfo.operatingSystem ?: ""}",
                "applicationName": "${printJob.clientInfo.applicationName ?: ""}"
            }
        }
        """.trimIndent()
    }
} 