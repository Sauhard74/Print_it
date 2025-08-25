package com.example.printer.simulator

import android.content.Context
import android.util.Log
import com.example.printer.queue.PrintJob
import com.example.printer.queue.PrintJobQueue
import com.example.printer.queue.PrintJobState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Simulated printer error types
 */
enum class SimulatedError(
    val code: String,
    val description: String,
    val ippStateReason: String,
    val recoverable: Boolean = true
) {
    PAPER_JAM("paper-jam", "Paper jam detected", "media-jam", true),
    OUT_OF_PAPER("out-of-paper", "Paper tray empty", "media-empty-error", true),
    OUT_OF_TONER("out-of-toner", "Toner cartridge empty", "toner-empty", true),
    LOW_TONER("low-toner", "Toner level low", "toner-low-warning", false),
    COVER_OPEN("cover-open", "Printer cover is open", "cover-open", true),
    PAPER_MISFEED("paper-misfeed", "Paper misfeed error", "media-jam", true),
    NETWORK_ERROR("network-error", "Network communication error", "printer-unreachable", true),
    MEMORY_FULL("memory-full", "Printer memory full", "printer-memory-full", true),
    INVALID_FORMAT("invalid-format", "Unsupported document format", "document-format-error", false),
    AUTHENTICATION_FAILED("auth-failed", "Authentication failed", "authentication-info-required", true),
    QUOTA_EXCEEDED("quota-exceeded", "Print quota exceeded", "account-limit-error", false),
    MAINTENANCE_REQUIRED("maintenance", "Printer maintenance required", "developer-empty-warning", true),
    OVERHEATING("overheating", "Printer overheating", "printer-warming-up", true),
    PAPER_SIZE_MISMATCH("paper-size", "Paper size mismatch", "media-needed", true),
    DUPLEX_ERROR("duplex-error", "Duplex unit malfunction", "finisher-failure", true);
    
    companion object {
        fun getRandomError(): SimulatedError {
            return values().random()
        }
        
        fun getCommonErrors(): List<SimulatedError> {
            return listOf(PAPER_JAM, OUT_OF_PAPER, OUT_OF_TONER, COVER_OPEN)
        }
    }
}

/**
 * Error simulation configuration
 */
data class ErrorSimulationConfig(
    val enabled: Boolean = false,
    val errorProbability: Float = 0.1f, // 10% chance of error
    val specificErrors: List<SimulatedError> = emptyList(),
    val randomErrors: Boolean = true,
    val errorDuration: Long = 5000L, // 5 seconds
    val autoRecover: Boolean = true,
    val cascadingErrors: Boolean = false // One error can trigger another
)

/**
 * Simulation state tracking
 */
data class SimulationState(
    val isSimulating: Boolean = false,
    val activeErrors: Map<String, SimulatedError> = emptyMap(),
    val errorHistory: List<ErrorEvent> = emptyList(),
    val totalJobsSimulated: Int = 0,
    val totalErrorsTriggered: Int = 0,
    val averageErrorDuration: Long = 0L
)

/**
 * Error event for logging and analysis
 */
data class ErrorEvent(
    val id: String,
    val error: SimulatedError,
    val jobId: Long?,
    val startTime: Long,
    val endTime: Long? = null,
    val triggerReason: String,
    val resolution: String? = null,
    val userAction: String? = null
) {
    val duration: Long get() = endTime?.let { it - startTime } ?: 0L
    val isActive: Boolean get() = endTime == null
}

/**
 * Print job simulator for comprehensive error testing
 */
class PrintJobSimulator private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PrintJobSimulator"
        
        @Volatile
        private var INSTANCE: PrintJobSimulator? = null
        
        fun getInstance(context: Context): PrintJobSimulator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrintJobSimulator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val printJobQueue = PrintJobQueue.getInstance(context)
    private val simulatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var config = ErrorSimulationConfig()
    private val activeErrorJobs = ConcurrentHashMap<String, Job>()
    
    private val _simulationState = MutableStateFlow(SimulationState())
    val simulationState: StateFlow<SimulationState> = _simulationState.asStateFlow()
    
    private val errorHistory = mutableListOf<ErrorEvent>()
    
    /**
     * Configure error simulation
     */
    fun configureSimulation(newConfig: ErrorSimulationConfig) {
        config = newConfig
        Log.d(TAG, "Simulation configured: enabled=${config.enabled}, probability=${config.errorProbability}")
        
        if (!config.enabled) {
            stopAllSimulations()
        }
        
        updateSimulationState()
    }
    
    /**
     * Get current simulation configuration
     */
    fun getSimulationConfig(): ErrorSimulationConfig = config
    
    /**
     * Simulate a print job with potential errors
     */
    suspend fun simulatePrintJob(jobId: Long): SimulationResult {
        if (!config.enabled) {
            return SimulationResult.success(jobId, "Simulation disabled")
        }
        
        val job = printJobQueue.getJob(jobId)
        if (job == null) {
            Log.w(TAG, "Job $jobId not found for simulation")
            return SimulationResult.failure(jobId, "Job not found")
        }
        
        Log.d(TAG, "Starting simulation for job $jobId")
        
        return withContext(Dispatchers.Default) {
            try {
                // Update statistics
                val currentState = _simulationState.value
                _simulationState.value = currentState.copy(
                    totalJobsSimulated = currentState.totalJobsSimulated + 1
                )
                
                // Check if error should be triggered
                val shouldTriggerError = shouldTriggerErrorForJob(job)
                
                if (shouldTriggerError) {
                    val error = selectErrorForJob(job)
                    triggerError(error, jobId)
                    
                    SimulationResult.error(jobId, error, "Error simulation triggered")
                } else {
                    SimulationResult.success(jobId, "Job processed successfully")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during job simulation", e)
                SimulationResult.failure(jobId, "Simulation error: ${e.message}")
            }
        }
    }
    
    /**
     * Manually trigger a specific error
     */
    suspend fun triggerError(error: SimulatedError, jobId: Long? = null): String {
        val errorId = "error_${System.currentTimeMillis()}_${Random.nextInt(1000)}"
        
        Log.d(TAG, "Triggering error: ${error.code} for job $jobId")
        
        val errorEvent = ErrorEvent(
            id = errorId,
            error = error,
            jobId = jobId,
            startTime = System.currentTimeMillis(),
            triggerReason = "Manual trigger",
            resolution = null
        )
        
        synchronized(errorHistory) {
            errorHistory.add(errorEvent)
        }
        
        // Update job state if specific job is affected
        jobId?.let { id ->
            when (error) {
                SimulatedError.PAPER_JAM, 
                SimulatedError.OUT_OF_PAPER,
                SimulatedError.COVER_OPEN -> {
                    printJobQueue.abortJob(id, error.ippStateReason)
                }
                SimulatedError.NETWORK_ERROR,
                SimulatedError.MEMORY_FULL -> {
                    printJobQueue.holdJob(id)
                }
                else -> {
                    // For other errors, just log but continue processing
                    printJobQueue.updateJobMetadata(id, mapOf(
                        "simulation_error" to error.code,
                        "error_time" to System.currentTimeMillis()
                    ))
                }
            }
        }
        
        // Update active errors
        val currentState = _simulationState.value
        val newActiveErrors = currentState.activeErrors + (errorId to error)
        _simulationState.value = currentState.copy(
            activeErrors = newActiveErrors,
            totalErrorsTriggered = currentState.totalErrorsTriggered + 1
        )
        
        // Schedule error resolution if auto-recovery is enabled
        if (config.autoRecover && error.recoverable) {
            val recoveryJob = simulatorScope.launch {
                delay(config.errorDuration)
                resolveError(errorId, "Auto-recovery")
            }
            activeErrorJobs[errorId] = recoveryJob
        }
        
        // Broadcast error event
        broadcastErrorEvent(errorEvent)
        
        return errorId
    }
    
    /**
     * Resolve a specific error
     */
    suspend fun resolveError(errorId: String, resolution: String = "Manual resolution"): Boolean {
        Log.d(TAG, "Resolving error: $errorId")
        
        // Cancel any active recovery job
        activeErrorJobs[errorId]?.cancel()
        activeErrorJobs.remove(errorId)
        
        // Update error history
        synchronized(errorHistory) {
            val errorIndex = errorHistory.indexOfFirst { it.id == errorId }
            if (errorIndex >= 0) {
                val error = errorHistory[errorIndex]
                errorHistory[errorIndex] = error.copy(
                    endTime = System.currentTimeMillis(),
                    resolution = resolution
                )
                
                // Release held jobs if this was a blocking error
                error.jobId?.let { jobId ->
                    val job = printJobQueue.getJob(jobId)
                    if (job?.state == PrintJobState.HELD) {
                        printJobQueue.releaseJob(jobId)
                    }
                }
                
                // Update simulation state
                val currentState = _simulationState.value
                val newActiveErrors = currentState.activeErrors - errorId
                _simulationState.value = currentState.copy(
                    activeErrors = newActiveErrors
                )
                
                broadcastErrorResolution(errorHistory[errorIndex])
                return true
            }
        }
        
        return false
    }
    
    /**
     * Get all active errors
     */
    fun getActiveErrors(): List<ErrorEvent> {
        synchronized(errorHistory) {
            return errorHistory.filter { it.isActive }
        }
    }
    
    /**
     * Get error history with filtering options
     */
    fun getErrorHistory(
        limit: Int = 100,
        errorType: SimulatedError? = null,
        jobId: Long? = null,
        since: Long? = null
    ): List<ErrorEvent> {
        synchronized(errorHistory) {
            return errorHistory
                .asSequence()
                .let { sequence ->
                    errorType?.let { type ->
                        sequence.filter { it.error == type }
                    } ?: sequence
                }
                .let { sequence ->
                    jobId?.let { id ->
                        sequence.filter { it.jobId == id }
                    } ?: sequence
                }
                .let { sequence ->
                    since?.let { timestamp ->
                        sequence.filter { it.startTime >= timestamp }
                    } ?: sequence
                }
                .sortedByDescending { it.startTime }
                .take(limit)
                .toList()
        }
    }
    
    /**
     * Generate comprehensive error report
     */
    fun generateErrorReport(): ErrorReport {
        synchronized(errorHistory) {
            val totalErrors = errorHistory.size
            val activeErrors = errorHistory.count { it.isActive }
            val resolvedErrors = errorHistory.count { !it.isActive }
            
            val errorsByType = errorHistory.groupBy { it.error }
                .mapValues { it.value.size }
            
            val averageDuration = errorHistory
                .filter { !it.isActive && it.duration > 0 }
                .map { it.duration }
                .takeIf { it.isNotEmpty() }
                ?.average()?.toLong() ?: 0L
            
            val mostCommonError = errorsByType.maxByOrNull { it.value }?.key
            
            val errorFrequency = if (_simulationState.value.totalJobsSimulated > 0) {
                totalErrors.toFloat() / _simulationState.value.totalJobsSimulated
            } else 0f
            
            return ErrorReport(
                totalErrors = totalErrors,
                activeErrors = activeErrors,
                resolvedErrors = resolvedErrors,
                errorsByType = errorsByType,
                averageErrorDuration = averageDuration,
                mostCommonError = mostCommonError,
                errorFrequency = errorFrequency,
                totalJobsSimulated = _simulationState.value.totalJobsSimulated,
                reportGeneratedAt = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Clear error history
     */
    fun clearErrorHistory() {
        synchronized(errorHistory) {
            // Only clear resolved errors, keep active ones
            val activeErrors = errorHistory.filter { it.isActive }
            errorHistory.clear()
            errorHistory.addAll(activeErrors)
        }
        
        val currentState = _simulationState.value
        val activeErrorsCount = getActiveErrors().size
        _simulationState.value = currentState.copy(
            errorHistory = getErrorHistory(),
            totalErrorsTriggered = activeErrorsCount,
            totalJobsSimulated = 0
        )
        
        Log.d(TAG, "Error history cleared, kept ${getActiveErrors().size} active errors")
    }
    
    /**
     * Stop all active simulations
     */
    fun stopAllSimulations() {
        // Cancel all active error jobs
        activeErrorJobs.values.forEach { it.cancel() }
        activeErrorJobs.clear()
        
        // Resolve all active errors
        val activeErrorsToResolve = synchronized(errorHistory) {
            errorHistory.filter { it.isActive }
        }
        
        synchronized(errorHistory) {
            activeErrorsToResolve.forEach { error ->
                val index = errorHistory.indexOf(error)
                if (index >= 0) {
                    errorHistory[index] = error.copy(
                        endTime = System.currentTimeMillis(),
                        resolution = "Simulation stopped"
                    )
                }
            }
        }
        
        _simulationState.value = _simulationState.value.copy(
            isSimulating = false,
            activeErrors = emptyMap()
        )
        
        Log.d(TAG, "All simulations stopped")
    }
    
    // Private helper methods
    
    private fun shouldTriggerErrorForJob(@Suppress("UNUSED_PARAMETER") job: PrintJob): Boolean {
        if (!config.enabled) return false
        
        // Check probability
        if (Random.nextFloat() > config.errorProbability) return false
        
        // Check if there are already too many active errors
        if (_simulationState.value.activeErrors.size >= 3) return false
        
        // Check document format specific errors
        if (job.documentFormat == "application/unknown" && 
            config.specificErrors.contains(SimulatedError.INVALID_FORMAT)) {
            return true
        }
        
        return true
    }
    
    private fun selectErrorForJob(job: PrintJob): SimulatedError {
        return when {
            config.specificErrors.isNotEmpty() -> config.specificErrors.random()
            config.randomErrors -> SimulatedError.getRandomError()
            else -> SimulatedError.getCommonErrors().random()
        }
    }
    
    private fun updateSimulationState() {
        val newState = _simulationState.value.copy(
            isSimulating = config.enabled,
            errorHistory = getErrorHistory()
        )
        _simulationState.value = newState
    }
    
    private fun broadcastErrorEvent(errorEvent: ErrorEvent) {
        val intent = android.content.Intent("com.example.printer.ERROR_SIMULATED")
        intent.putExtra("error_id", errorEvent.id)
        intent.putExtra("error_code", errorEvent.error.code)
        intent.putExtra("error_description", errorEvent.error.description)
        intent.putExtra("job_id", errorEvent.jobId ?: -1L)
        intent.putExtra("start_time", errorEvent.startTime)
        
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting error event", e)
        }
    }
    
    private fun broadcastErrorResolution(errorEvent: ErrorEvent) {
        val intent = android.content.Intent("com.example.printer.ERROR_RESOLVED")
        intent.putExtra("error_id", errorEvent.id)
        intent.putExtra("error_code", errorEvent.error.code)
        intent.putExtra("resolution", errorEvent.resolution ?: "Unknown")
        intent.putExtra("duration", errorEvent.duration)
        
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting error resolution", e)
        }
    }
}

/**
 * Simulation result
 */
sealed class SimulationResult(
    val jobId: Long,
    val success: Boolean,
    val message: String
) {
    class Success(jobId: Long, message: String) : SimulationResult(jobId, true, message)
    class Error(jobId: Long, val error: SimulatedError, message: String) : SimulationResult(jobId, false, message)
    class Failure(jobId: Long, message: String) : SimulationResult(jobId, false, message)
    
    companion object {
        fun success(jobId: Long, message: String) = Success(jobId, message)
        fun error(jobId: Long, error: SimulatedError, message: String) = Error(jobId, error, message)
        fun failure(jobId: Long, message: String) = Failure(jobId, message)
    }
}

/**
 * Comprehensive error report
 */
data class ErrorReport(
    val totalErrors: Int,
    val activeErrors: Int,
    val resolvedErrors: Int,
    val errorsByType: Map<SimulatedError, Int>,
    val averageErrorDuration: Long,
    val mostCommonError: SimulatedError?,
    val errorFrequency: Float,
    val totalJobsSimulated: Int,
    val reportGeneratedAt: Long
) {
    fun getFormattedReport(): String {
        return buildString {
            appendLine("=== PRINT JOB SIMULATION REPORT ===")
            appendLine()
            appendLine("Summary:")
            appendLine("  Total jobs simulated: $totalJobsSimulated")
            appendLine("  Total errors triggered: $totalErrors")
            appendLine("  Active errors: $activeErrors")
            appendLine("  Resolved errors: $resolvedErrors")
            appendLine("  Error frequency: ${String.format("%.2f", errorFrequency * 100)}%")
            appendLine("  Average error duration: ${averageErrorDuration}ms")
            appendLine()
            
            if (mostCommonError != null) {
                appendLine("Most common error: ${mostCommonError.description}")
                appendLine()
            }
            
            if (errorsByType.isNotEmpty()) {
                appendLine("Errors by type:")
                errorsByType.toList()
                    .sortedByDescending { it.second }
                    .forEach { (error, count) ->
                        appendLine("  ${error.description}: $count")
                    }
                appendLine()
            }
            
            appendLine("Report generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(reportGeneratedAt))}")
        }
    }
}