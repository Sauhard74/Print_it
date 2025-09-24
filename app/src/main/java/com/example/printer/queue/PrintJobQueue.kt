package com.example.printer.queue

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.printer.utils.FileUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Print job states according to IPP specification
 */
enum class PrintJobState(val code: Int) {
    PENDING(3),           // Job is queued and waiting
    HELD(4),             // Job is held by user or system
    PROCESSING(5),       // Job is being processed
    STOPPED(6),          // Job has been stopped
    CANCELED(7),         // Job has been canceled
    ABORTED(8),          // Job was aborted due to error
    COMPLETED(9)         // Job completed successfully
}

/**
 * Represents a print job in the queue
 */
data class PrintJob(
    val id: Long,
    val name: String,
    val filePath: String,
    val documentFormat: String,
    val size: Long,
    val submissionTime: Long,
    val state: PrintJobState = PrintJobState.PENDING,
    val stateReasons: List<String> = listOf("none"),
    val jobOriginatingUserName: String = "anonymous",
    val impressionsCompleted: Int = 0,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun getFile(): File = File(filePath)
    
    fun getReadableName(): String {
        return if (name.isNotBlank()) name else getFile().nameWithoutExtension
    }
    
    fun getStateDescription(): String {
        return when (state) {
            PrintJobState.PENDING -> "Waiting in queue"
            PrintJobState.HELD -> "On hold"
            PrintJobState.PROCESSING -> "Processing"
            PrintJobState.STOPPED -> "Stopped"
            PrintJobState.CANCELED -> "Canceled"
            PrintJobState.ABORTED -> "Aborted"
            PrintJobState.COMPLETED -> "Completed"
        }
    }
}

/**
 * Job queue controller with advanced management capabilities
 */
class PrintJobQueue private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PrintJobQueue"
        
        @Volatile
        private var INSTANCE: PrintJobQueue? = null
        
        fun getInstance(context: Context): PrintJobQueue {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrintJobQueue(context.applicationContext).also { 
                    INSTANCE = it
                    it.loadExistingJobs() // Load existing jobs on first creation
                }
            }
        }
    }
    
    private val jobIdCounter = AtomicLong(System.currentTimeMillis())
    private val jobs = ConcurrentHashMap<Long, PrintJob>()
    
    private val _jobsFlow = MutableStateFlow<List<PrintJob>>(emptyList())
    val jobsFlow: StateFlow<List<PrintJob>> = _jobsFlow.asStateFlow()
    
    private val _queueStatistics = MutableStateFlow(QueueStatistics())
    val queueStatistics: StateFlow<QueueStatistics> = _queueStatistics.asStateFlow()
    
    /**
     * Queue statistics for monitoring
     */
    data class QueueStatistics(
        val totalJobs: Int = 0,
        val pendingJobs: Int = 0,
        val heldJobs: Int = 0,
        val processingJobs: Int = 0,
        val completedJobs: Int = 0,
        val canceledJobs: Int = 0,
        val abortedJobs: Int = 0,
        val averageProcessingTime: Long = 0L,
        val queueSize: Long = 0L
    )
    
    /**
     * Add a new print job to the queue
     */
    fun addJob(
        name: String,
        filePath: String,
        documentFormat: String,
        size: Long,
        userInfo: Map<String, Any> = emptyMap()
    ): Long {
        val jobId = jobIdCounter.incrementAndGet()
        val job = PrintJob(
            id = jobId,
            name = name,
            filePath = filePath,
            documentFormat = documentFormat,
            size = size,
            submissionTime = System.currentTimeMillis(),
            state = PrintJobState.PENDING,
            stateReasons = listOf("none"),
            jobOriginatingUserName = userInfo["user"] as? String ?: "anonymous",
            metadata = userInfo
        )
        
        jobs[jobId] = job
        updateFlows()
        
        Log.d(TAG, "Added print job: $jobId - $name")
        
        // Broadcast job added
        broadcastJobStateChange(job, "job-created")
        
        return jobId
    }
    
    /**
     * Get a specific job by ID
     */
    fun getJob(jobId: Long): PrintJob? {
        return jobs[jobId]
    }
    
    /**
     * Get all jobs
     */
    fun getAllJobs(): List<PrintJob> {
        return jobs.values.sortedByDescending { it.submissionTime }
    }
    
    /**
     * Get jobs by state
     */
    fun getJobsByState(state: PrintJobState): List<PrintJob> {
        return jobs.values.filter { it.state == state }
            .sortedByDescending { it.submissionTime }
    }
    
    /**
     * Hold a job (prevent processing)
     */
    fun holdJob(jobId: Long): Boolean {
        val job = jobs[jobId] ?: return false
        
        if (job.state == PrintJobState.PENDING || job.state == PrintJobState.PROCESSING) {
            val updatedJob = job.copy(
                state = PrintJobState.HELD,
                stateReasons = listOf("job-hold-until-specified")
            )
            jobs[jobId] = updatedJob
            updateFlows()
            
            Log.d(TAG, "Held job: $jobId")
            broadcastJobStateChange(updatedJob, "job-held")
            return true
        }
        return false
    }
    
    /**
     * Release a held job
     */
    fun releaseJob(jobId: Long): Boolean {
        val job = jobs[jobId] ?: return false
        
        if (job.state == PrintJobState.HELD) {
            val updatedJob = job.copy(
                state = PrintJobState.PENDING,
                stateReasons = listOf("none")
            )
            jobs[jobId] = updatedJob
            updateFlows()
            
            Log.d(TAG, "Released job: $jobId")
            broadcastJobStateChange(updatedJob, "job-released")
            return true
        }
        return false
    }
    
    /**
     * Cancel a job
     */
    fun cancelJob(jobId: Long): Boolean {
        val job = jobs[jobId] ?: return false
        
        if (job.state != PrintJobState.COMPLETED && job.state != PrintJobState.CANCELED && job.state != PrintJobState.ABORTED) {
            val updatedJob = job.copy(
                state = PrintJobState.CANCELED,
                stateReasons = listOf("job-canceled-by-user")
            )
            jobs[jobId] = updatedJob
            updateFlows()
            
            Log.d(TAG, "Canceled job: $jobId")
            broadcastJobStateChange(updatedJob, "job-canceled")
            return true
        }
        return false
    }
    
    /**
     * Mark job as processing
     */
    fun startProcessingJob(jobId: Long): Boolean {
        val job = jobs[jobId] ?: return false
        
        if (job.state == PrintJobState.PENDING) {
            val updatedJob = job.copy(
                state = PrintJobState.PROCESSING,
                stateReasons = listOf("job-processing")
            )
            jobs[jobId] = updatedJob
            updateFlows()
            
            Log.d(TAG, "Started processing job: $jobId")
            broadcastJobStateChange(updatedJob, "job-processing")
            return true
        }
        return false
    }
    
    /**
     * Mark job as completed
     */
    fun completeJob(jobId: Long): Boolean {
        val job = jobs[jobId] ?: return false
        
        if (job.state == PrintJobState.PROCESSING) {
            val updatedJob = job.copy(
                state = PrintJobState.COMPLETED,
                stateReasons = listOf("job-completed-successfully"),
                impressionsCompleted = 1
            )
            jobs[jobId] = updatedJob
            updateFlows()
            
            Log.d(TAG, "Completed job: $jobId")
            broadcastJobStateChange(updatedJob, "job-completed")
            return true
        }
        return false
    }
    
    /**
     * Abort a job due to error
     */
    fun abortJob(jobId: Long, reason: String = "job-aborted-by-system"): Boolean {
        val job = jobs[jobId] ?: return false
        
        if (job.state == PrintJobState.PROCESSING || job.state == PrintJobState.PENDING) {
            val updatedJob = job.copy(
                state = PrintJobState.ABORTED,
                stateReasons = listOf(reason)
            )
            jobs[jobId] = updatedJob
            updateFlows()
            
            Log.d(TAG, "Aborted job: $jobId - $reason")
            broadcastJobStateChange(updatedJob, "job-aborted")
            return true
        }
        return false
    }
    
    /**
     * Delete a job from the queue and filesystem
     */
    fun deleteJob(jobId: Long): Boolean {
        val job = jobs[jobId] ?: return false
        
        try {
            // Delete the file
            val file = File(job.filePath)
            if (file.exists()) {
                file.delete()
            }
            
            // Remove from queue
            jobs.remove(jobId)
            updateFlows()
            
            Log.d(TAG, "Deleted job: $jobId")
            broadcastJobStateChange(job, "job-deleted")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting job: $jobId", e)
            return false
        }
    }
    
    /**
     * Clear all completed/canceled jobs
     */
    fun clearFinishedJobs(): Int {
        val finishedStates = setOf(PrintJobState.COMPLETED, PrintJobState.CANCELED, PrintJobState.ABORTED)
        val finishedJobs = jobs.values.filter { it.state in finishedStates }
        
        finishedJobs.forEach { job ->
            deleteJob(job.id)
        }
        
        Log.d(TAG, "Cleared ${finishedJobs.size} finished jobs")
        return finishedJobs.size
    }
    
    /**
     * Get the next pending job for processing
     */
    fun getNextPendingJob(): PrintJob? {
        return jobs.values
            .filter { it.state == PrintJobState.PENDING }
            .minByOrNull { it.submissionTime }
    }
    
    /**
     * Update job metadata
     */
    fun updateJobMetadata(jobId: Long, metadata: Map<String, Any>): Boolean {
        val job = jobs[jobId] ?: return false
        
        val updatedJob = job.copy(metadata = job.metadata + metadata)
        jobs[jobId] = updatedJob
        updateFlows()
        
        return true
    }
    
    /**
     * Get queue position for a pending job
     */
    fun getJobPosition(jobId: Long): Int {
        val job = jobs[jobId] ?: return -1
        if (job.state != PrintJobState.PENDING) return -1
        
        val pendingJobs = getJobsByState(PrintJobState.PENDING)
        return pendingJobs.indexOfFirst { it.id == jobId } + 1
    }
    
    private fun updateFlows() {
        val allJobs = getAllJobs()
        _jobsFlow.value = allJobs
        
        val stats = calculateStatistics(allJobs)
        _queueStatistics.value = stats
    }
    
    private fun calculateStatistics(allJobs: List<PrintJob>): QueueStatistics {
        val totalJobs = allJobs.size
        val pendingJobs = allJobs.count { it.state == PrintJobState.PENDING }
        val heldJobs = allJobs.count { it.state == PrintJobState.HELD }
        val processingJobs = allJobs.count { it.state == PrintJobState.PROCESSING }
        val completedJobs = allJobs.count { it.state == PrintJobState.COMPLETED }
        val canceledJobs = allJobs.count { it.state == PrintJobState.CANCELED }
        val abortedJobs = allJobs.count { it.state == PrintJobState.ABORTED }
        val queueSize = allJobs.sumOf { it.size }
        
        // Calculate average processing time for completed jobs
        val completedJobsWithTime = allJobs.filter { it.state == PrintJobState.COMPLETED }
        val averageProcessingTime = if (completedJobsWithTime.isNotEmpty()) {
            completedJobsWithTime.map { 
                // Estimate processing time as 5 seconds (could be enhanced with actual timing)
                5000L 
            }.average().toLong()
        } else 0L
        
        return QueueStatistics(
            totalJobs = totalJobs,
            pendingJobs = pendingJobs,
            heldJobs = heldJobs,
            processingJobs = processingJobs,
            completedJobs = completedJobs,
            canceledJobs = canceledJobs,
            abortedJobs = abortedJobs,
            averageProcessingTime = averageProcessingTime,
            queueSize = queueSize
        )
    }
    
    private fun broadcastJobStateChange(job: PrintJob, reason: String) {
        val intent = android.content.Intent("com.example.printer.JOB_STATE_CHANGED")
        intent.putExtra("job_id", job.id)
        intent.putExtra("job_state", job.state.name)
        intent.putExtra("job_state_code", job.state.code)
        intent.putExtra("state_reason", reason)
        intent.putExtra("job_name", job.name)
        
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting job state change", e)
        }
    }
    
    /**
     * Load existing print jobs from file system into the queue
     */
    private fun loadExistingJobs() {
        try {
            val existingFiles = FileUtils.getSavedPrintJobs(context)
            Log.d(TAG, "Loading ${existingFiles.size} existing print jobs from file system")
            
            existingFiles.forEach { file ->
                // Extract job ID from filename if it follows our pattern
                val jobId = extractJobIdFromFilename(file.name) ?: jobIdCounter.incrementAndGet()
                
                // Determine document format from file extension
                val documentFormat = when (file.extension.lowercase()) {
                    "pdf" -> "application/pdf"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "txt" -> "text/plain"
                    else -> "application/octet-stream"
                }
                
                val job = PrintJob(
                    id = jobId,
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    documentFormat = documentFormat,
                    size = file.length(),
                    submissionTime = file.lastModified(),
                    state = PrintJobState.COMPLETED, // Existing files are considered completed
                    stateReasons = listOf("completed-successfully"),
                    jobOriginatingUserName = "system",
                    impressionsCompleted = 1,
                    metadata = mapOf(
                        "loaded_from_file" to true,
                        "file_last_modified" to file.lastModified()
                    )
                )
                
                jobs[jobId] = job
                Log.d(TAG, "Loaded existing job: ${job.id} - ${job.name}")
            }
            
            updateFlows()
            Log.d(TAG, "Successfully loaded ${existingFiles.size} existing jobs into queue")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading existing jobs", e)
        }
    }
    
    /**
     * Extract job ID from filename pattern like "print_job_1234567890.pdf"
     */
    private fun extractJobIdFromFilename(filename: String): Long? {
        return try {
            val pattern = Regex("print_job_(\\d+)\\.")
            val matchResult = pattern.find(filename)
            matchResult?.groupValues?.get(1)?.toLong()
        } catch (e: Exception) {
            null
        }
    }
}