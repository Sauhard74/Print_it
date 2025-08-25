package com.example.printer.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.LinkedHashMap

/**
 * Log levels for filtering and severity
 */
enum class LogLevel(val priority: Int, val tag: String) {
    VERBOSE(2, "V"),
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E"),
    CRITICAL(7, "C");
    
    companion object {
        fun fromPriority(priority: Int): LogLevel {
            return values().find { it.priority == priority } ?: DEBUG
        }
    }
}

/**
 * Log categories for organizing different types of events
 */
enum class LogCategory(val displayName: String) {
    IPP_PROTOCOL("IPP Protocol"),
    PRINT_JOB("Print Job"),
    NETWORK("Network"),
    FILE_OPERATION("File Operation"),
    ERROR_SIMULATION("Error Simulation"),
    AUTHENTICATION("Authentication"),
    DOCUMENT_PROCESSING("Document Processing"),
    QUEUE_MANAGEMENT("Queue Management"),
    SYSTEM("System"),
    USER_ACTION("User Action"),
    PERFORMANCE("Performance");
}

/**
 * Structured log entry with comprehensive metadata
 */
data class LogEntry(
    val id: String,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val tag: String,
    val threadName: String,
    val jobId: Long? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val stackTrace: String? = null,
    val duration: Long? = null // For performance tracking
) {
    fun getFormattedTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(timestamp))
    }
    
    fun getFormattedMessage(): String {
        return buildString {
            append("${getFormattedTimestamp()} ")
            append("${level.tag}/${tag}: ")
            append(message)
            
            if (jobId != null) {
                append(" [Job: $jobId]")
            }
            if (userId != null) {
                append(" [User: $userId]")
            }
            if (duration != null) {
                append(" [Duration: ${duration}ms]")
            }
            if (metadata.isNotEmpty()) {
                append(" [Meta: ${metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }}]")
            }
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("level", level.name)
            put("category", category.name)
            put("message", message)
            put("tag", tag)
            put("threadName", threadName)
            jobId?.let { put("jobId", it) }
            userId?.let { put("userId", it) }
            sessionId?.let { put("sessionId", it) }
            duration?.let { put("duration", it) }
            if (metadata.isNotEmpty()) {
                put("metadata", JSONObject(metadata))
            }
            stackTrace?.let { put("stackTrace", it) }
        }
    }
    
    companion object {
        fun fromJson(json: JSONObject): LogEntry {
            return LogEntry(
                id = json.getString("id"),
                timestamp = json.getLong("timestamp"),
                level = LogLevel.valueOf(json.getString("level")),
                category = LogCategory.valueOf(json.getString("category")),
                message = json.getString("message"),
                tag = json.getString("tag"),
                threadName = json.getString("threadName"),
                jobId = json.optLong("jobId").takeIf { it != 0L },
                userId = json.optString("userId").takeIf { it.isNotEmpty() },
                sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() },
                metadata = json.optJSONObject("metadata")?.let { metaJson ->
                    metaJson.keys().asSequence().associate { key ->
                        key to metaJson.get(key)
                    }
                } ?: emptyMap(),
                stackTrace = json.optString("stackTrace").takeIf { it.isNotEmpty() },
                duration = json.optLong("duration").takeIf { it != 0L }
            )
        }
    }
}

/**
 * Log filtering criteria
 */
data class LogFilter(
    val minLevel: LogLevel = LogLevel.DEBUG,
    val categories: Set<LogCategory> = emptySet(),
    val tags: Set<String> = emptySet(),
    val jobId: Long? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val searchText: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null
)

/**
 * Log statistics for analysis
 */
data class LogStatistics(
    val totalEntries: Int,
    val entriesByLevel: Map<LogLevel, Int>,
    val entriesByCategory: Map<LogCategory, Int>,
    val entriesByTag: Map<String, Int>,
    val timeRange: Pair<Long, Long>?,
    val averageEntriesPerMinute: Double,
    val topErrors: List<Pair<String, Int>>,
    val performanceMetrics: Map<String, Double>
)

/**
 * Comprehensive logging system with trace analysis capabilities
 */
class PrinterLogger private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PrinterLogger"
        private const val MAX_LOG_ENTRIES = 10000
        private const val LOG_FILE_PREFIX = "printer_log_"
        private const val LOG_FILE_EXTENSION = ".json"
        
        @Volatile
        private var INSTANCE: PrinterLogger? = null
        
        fun getInstance(context: Context): PrinterLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrinterLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionId = generateSessionId()
    
    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()
    
    private val _logStatistics = MutableStateFlow<LogStatistics?>(null)
    val logStatistics: StateFlow<LogStatistics?> = _logStatistics.asStateFlow()
    
    // Performance tracking
    private val operationTimings = LinkedHashMap<String, MutableList<Long>>()
    
    init {
        // Start log processing coroutine
        logScope.launch {
            while (true) {
                processLogQueue()
                delay(100) // Process logs every 100ms
            }
        }
        
        // Load existing logs on startup
        logScope.launch {
            loadLogsFromDisk()
        }
        
        log(LogLevel.INFO, LogCategory.SYSTEM, TAG, "PrinterLogger initialized", 
            metadata = mapOf("sessionId" to sessionId))
    }
    
    /**
     * Log a message with full metadata support
     */
    fun log(
        level: LogLevel,
        category: LogCategory,
        tag: String,
        message: String,
        jobId: Long? = null,
        userId: String? = null,
        metadata: Map<String, Any> = emptyMap(),
        throwable: Throwable? = null,
        duration: Long? = null
    ) {
        val logEntry = LogEntry(
            id = generateLogId(),
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message,
            tag = tag,
            threadName = Thread.currentThread().name,
            jobId = jobId,
            userId = userId,
            sessionId = sessionId,
            metadata = metadata,
            stackTrace = throwable?.stackTraceToString(),
            duration = duration
        )
        
        // Add to queue for processing
        logQueue.offer(logEntry)
        
        // Also log to Android Log for debugging
        val logMessage = logEntry.getFormattedMessage()
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, logMessage, throwable)
            LogLevel.DEBUG -> Log.d(tag, logMessage, throwable)
            LogLevel.INFO -> Log.i(tag, logMessage, throwable)
            LogLevel.WARN -> Log.w(tag, logMessage, throwable)
            LogLevel.ERROR -> Log.e(tag, logMessage, throwable)
            LogLevel.CRITICAL -> Log.wtf(tag, logMessage, throwable)
        }
    }
    
    /**
     * Convenience methods for different log levels
     */
    fun v(category: LogCategory, tag: String, message: String, 
          jobId: Long? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.VERBOSE, category, tag, message, jobId = jobId, metadata = metadata)
    }
    
    fun d(category: LogCategory, tag: String, message: String, 
          jobId: Long? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.DEBUG, category, tag, message, jobId = jobId, metadata = metadata)
    }
    
    fun i(category: LogCategory, tag: String, message: String, 
          jobId: Long? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.INFO, category, tag, message, jobId = jobId, metadata = metadata)
    }
    
    fun w(category: LogCategory, tag: String, message: String, 
          jobId: Long? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.WARN, category, tag, message, jobId = jobId, metadata = metadata)
    }
    
    fun e(category: LogCategory, tag: String, message: String, 
          throwable: Throwable? = null, jobId: Long? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.ERROR, category, tag, message, jobId = jobId, metadata = metadata, throwable = throwable)
    }
    
    fun c(category: LogCategory, tag: String, message: String, 
          throwable: Throwable? = null, jobId: Long? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.CRITICAL, category, tag, message, jobId = jobId, metadata = metadata, throwable = throwable)
    }
    
    /**
     * Performance tracking methods
     */
    fun startTiming(operationName: String): String {
        val timingId = "${operationName}_${System.currentTimeMillis()}_${Thread.currentThread().id}"
        d(LogCategory.PERFORMANCE, TAG, "Started timing: $operationName", 
          metadata = mapOf("timingId" to timingId))
        return timingId
    }
    
    fun endTiming(timingId: String, operationName: String, jobId: Long? = null) {
        val parts = timingId.split("_")
        if (parts.size >= 2) {
            val startTime = parts[1].toLongOrNull() ?: return
            val duration = System.currentTimeMillis() - startTime
            
            // Record timing
            synchronized(operationTimings) {
                operationTimings.getOrPut(operationName) { mutableListOf() }.add(duration)
            }
            
            log(LogLevel.DEBUG, LogCategory.PERFORMANCE, TAG, 
                "Completed timing: $operationName", 
                jobId = jobId,
                duration = duration,
                metadata = mapOf("timingId" to timingId))
        }
    }
    
    /**
     * Get logs with filtering
     */
    fun getLogs(filter: LogFilter = LogFilter(), limit: Int = 1000): List<LogEntry> {
        val allLogs = _logEntries.value
        
        return allLogs.asSequence()
            .filter { entry ->
                // Level filter
                entry.level.priority >= filter.minLevel.priority
            }
            .filter { entry ->
                // Category filter
                filter.categories.isEmpty() || entry.category in filter.categories
            }
            .filter { entry ->
                // Tag filter
                filter.tags.isEmpty() || entry.tag in filter.tags
            }
            .filter { entry ->
                // Job ID filter
                filter.jobId == null || entry.jobId == filter.jobId
            }
            .filter { entry ->
                // User ID filter
                filter.userId == null || entry.userId == filter.userId
            }
            .filter { entry ->
                // Session ID filter
                filter.sessionId == null || entry.sessionId == filter.sessionId
            }
            .filter { entry ->
                // Text search filter
                filter.searchText == null || 
                entry.message.contains(filter.searchText, ignoreCase = true) ||
                entry.tag.contains(filter.searchText, ignoreCase = true)
            }
            .filter { entry ->
                // Time range filter
                (filter.startTime == null || entry.timestamp >= filter.startTime) &&
                (filter.endTime == null || entry.timestamp <= filter.endTime)
            }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .toList()
    }
    
    /**
     * Generate comprehensive log statistics
     */
    fun generateStatistics(filter: LogFilter = LogFilter()): LogStatistics {
        val logs = getLogs(filter, Int.MAX_VALUE)
        
        val entriesByLevel = logs.groupBy { it.level }.mapValues { it.value.size }
        val entriesByCategory = logs.groupBy { it.category }.mapValues { it.value.size }
        val entriesByTag = logs.groupBy { it.tag }.mapValues { it.value.size }
        
        val timeRange = if (logs.isNotEmpty()) {
            logs.minOf { it.timestamp } to logs.maxOf { it.timestamp }
        } else null
        
        val averageEntriesPerMinute = timeRange?.let { (start, end) ->
            val durationMinutes = (end - start) / (60 * 1000).toDouble()
            if (durationMinutes > 0) logs.size / durationMinutes else 0.0
        } ?: 0.0
        
        val topErrors = logs.filter { it.level == LogLevel.ERROR || it.level == LogLevel.CRITICAL }
            .groupBy { it.message }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(10)
        
        val performanceMetrics = synchronized(operationTimings) {
            operationTimings.mapValues { (_, timings) ->
                if (timings.isNotEmpty()) timings.average() else 0.0
            }
        }
        
        val statistics = LogStatistics(
            totalEntries = logs.size,
            entriesByLevel = entriesByLevel,
            entriesByCategory = entriesByCategory,
            entriesByTag = entriesByTag,
            timeRange = timeRange,
            averageEntriesPerMinute = averageEntriesPerMinute,
            topErrors = topErrors,
            performanceMetrics = performanceMetrics
        )
        
        _logStatistics.value = statistics
        return statistics
    }
    
    /**
     * Export logs to file
     */
    suspend fun exportLogs(
        filename: String? = null,
        filter: LogFilter = LogFilter(),
        format: ExportFormat = ExportFormat.JSON
    ): File? = withContext(Dispatchers.IO) {
        
        try {
            val logs = getLogs(filter, Int.MAX_VALUE)
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            
            val actualFilename = filename ?: "${LOG_FILE_PREFIX}${System.currentTimeMillis()}"
            val extension = when (format) {
                ExportFormat.JSON -> ".json"
                ExportFormat.CSV -> ".csv"
                ExportFormat.TEXT -> ".txt"
            }
            
            val file = File(logsDir, "$actualFilename$extension")
            
            when (format) {
                ExportFormat.JSON -> exportAsJson(file, logs)
                ExportFormat.CSV -> exportAsCsv(file, logs)
                ExportFormat.TEXT -> exportAsText(file, logs)
            }
            
            i(LogCategory.SYSTEM, TAG, "Exported ${logs.size} log entries to ${file.name}")
            file
            
        } catch (e: Exception) {
            e(LogCategory.SYSTEM, TAG, "Failed to export logs", e)
            null
        }
    }
    
    /**
     * Clear logs (keep only recent entries)
     */
    fun clearLogs(keepRecent: Int = 100) {
        val currentLogs = _logEntries.value
        val recentLogs = currentLogs.sortedByDescending { it.timestamp }.take(keepRecent)
        
        _logEntries.value = recentLogs
        
        // Clear performance timings
        synchronized(operationTimings) {
            operationTimings.clear()
        }
        
        i(LogCategory.SYSTEM, TAG, "Cleared logs, kept $keepRecent recent entries")
    }
    
    /**
     * Start a new session
     */
    fun startNewSession() {
        sessionId = generateSessionId()
        i(LogCategory.SYSTEM, TAG, "Started new session", 
          metadata = mapOf("sessionId" to sessionId))
    }
    
    // Private helper methods
    
    private suspend fun processLogQueue() {
        val batch = mutableListOf<LogEntry>()
        
        // Collect batch of log entries
        while (batch.size < 50 && logQueue.isNotEmpty()) {
            logQueue.poll()?.let { batch.add(it) }
        }
        
        if (batch.isNotEmpty()) {
            // Update flow with new entries
            val currentLogs = _logEntries.value.toMutableList()
            currentLogs.addAll(batch)
            
            // Trim if too many entries
            if (currentLogs.size > MAX_LOG_ENTRIES) {
                val trimmed = currentLogs.sortedByDescending { it.timestamp }
                    .take(MAX_LOG_ENTRIES)
                _logEntries.value = trimmed
            } else {
                _logEntries.value = currentLogs.sortedByDescending { it.timestamp }
            }
            
            // Persist to disk periodically
            if (currentLogs.size % 100 == 0) {
                saveLogsToDisk()
            }
        }
    }
    
    private suspend fun saveLogsToDisk() = withContext(Dispatchers.IO) {
        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            
            val sessionFile = File(logsDir, "session_${sessionId}.json")
            val recentLogs = _logEntries.value.take(1000) // Save recent 1000 entries
            
            exportAsJson(sessionFile, recentLogs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving logs to disk", e)
        }
    }
    
    private suspend fun loadLogsFromDisk() = withContext(Dispatchers.IO) {
        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) return@withContext
            
            val sessionFile = File(logsDir, "session_${sessionId}.json")
            if (sessionFile.exists()) {
                val logs = importFromJson(sessionFile)
                _logEntries.value = logs.sortedByDescending { it.timestamp }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logs from disk", e)
        }
    }
    
    private fun exportAsJson(file: File, logs: List<LogEntry>) {
        FileWriter(file).use { writer ->
            val jsonArray = JSONArray()
            logs.forEach { log ->
                jsonArray.put(log.toJson())
            }
            writer.write(jsonArray.toString(2))
        }
    }
    
    private fun exportAsCsv(file: File, logs: List<LogEntry>) {
        FileWriter(file).use { writer ->
            // Header
            writer.write("Timestamp,Level,Category,Tag,Message,JobId,UserId,Duration,Metadata\n")
            
            // Data
            logs.forEach { log ->
                writer.write("${log.getFormattedTimestamp()},")
                writer.write("${log.level.name},")
                writer.write("${log.category.name},")
                writer.write("${log.tag},")
                writer.write("\"${log.message.replace("\"", "\"\"")}\",")
                writer.write("${log.jobId ?: ""},")
                writer.write("${log.userId ?: ""},")
                writer.write("${log.duration ?: ""},")
                writer.write("\"${log.metadata.entries.joinToString(";") { "${it.key}=${it.value}" }}\"\n")
            }
        }
    }
    
    private fun exportAsText(file: File, logs: List<LogEntry>) {
        FileWriter(file).use { writer ->
            logs.forEach { log ->
                writer.write(log.getFormattedMessage())
                writer.write("\n")
                log.stackTrace?.let { stackTrace ->
                    writer.write("Stack trace:\n")
                    writer.write(stackTrace)
                    writer.write("\n")
                }
                writer.write("\n")
            }
        }
    }
    
    private fun importFromJson(file: File): List<LogEntry> {
        val content = file.readText()
        val jsonArray = JSONArray(content)
        val logs = mutableListOf<LogEntry>()
        
        for (i in 0 until jsonArray.length()) {
            try {
                val jsonObj = jsonArray.getJSONObject(i)
                logs.add(LogEntry.fromJson(jsonObj))
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing log entry at index $i", e)
            }
        }
        
        return logs
    }
    
    private fun generateLogId(): String {
        return "log_${System.currentTimeMillis()}_${Thread.currentThread().id}_${Random().nextInt(1000)}"
    }
    
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${Random().nextInt(10000)}"
    }
}

/**
 * Export format options
 */
enum class ExportFormat {
    JSON, CSV, TEXT
}