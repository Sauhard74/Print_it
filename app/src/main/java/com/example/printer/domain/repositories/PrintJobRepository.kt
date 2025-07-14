package com.example.printer.domain.repositories

import com.example.printer.domain.entities.PrintJob
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for print job management
 * Defines the contract for print job data access operations
 */
interface PrintJobRepository {
    
    /**
     * Observes all print jobs with real-time updates
     * @return Flow of print job list
     */
    fun observePrintJobs(): Flow<List<PrintJob>>
    
    /**
     * Gets all print jobs synchronously
     * @return List of all print jobs
     */
    suspend fun getAllPrintJobs(): List<PrintJob>
    
    /**
     * Gets a specific print job by ID
     * @param id The print job ID
     * @return PrintJob if found, null otherwise
     */
    suspend fun getPrintJobById(id: Long): PrintJob?
    
    /**
     * Saves a new print job
     * @param printJob The print job to save
     * @return The saved print job with generated ID
     */
    suspend fun savePrintJob(printJob: PrintJob): PrintJob
    
    /**
     * Updates an existing print job
     * @param printJob The print job to update
     * @return True if updated successfully, false otherwise
     */
    suspend fun updatePrintJob(printJob: PrintJob): Boolean
    
    /**
     * Deletes a print job by ID
     * @param id The print job ID to delete
     * @return True if deleted successfully, false otherwise
     */
    suspend fun deletePrintJob(id: Long): Boolean
    
    /**
     * Deletes all print jobs
     * @return Number of print jobs deleted
     */
    suspend fun deleteAllPrintJobs(): Int
    
    /**
     * Gets print jobs statistics
     * @return Statistics about stored print jobs
     */
    suspend fun getPrintJobsStatistics(): PrintJobStatistics
    
    /**
     * Searches print jobs by filename or content
     * @param query Search query
     * @return List of matching print jobs
     */
    suspend fun searchPrintJobs(query: String): List<PrintJob>
    
    /**
     * Gets print jobs within a date range
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return List of print jobs in the date range
     */
    suspend fun getPrintJobsByDateRange(
        startDate: java.time.LocalDateTime,
        endDate: java.time.LocalDateTime
    ): List<PrintJob>
    
    /**
     * Statistics about print jobs
     */
    data class PrintJobStatistics(
        val totalJobs: Int,
        val totalSizeBytes: Long,
        val pdfJobs: Int,
        val imageJobs: Int,
        val otherJobs: Int,
        val averageSizeBytes: Long,
        val oldestJobDate: java.time.LocalDateTime?,
        val newestJobDate: java.time.LocalDateTime?
    )
} 