package com.example.printer.domain.usecases

import com.example.printer.domain.entities.PrintJob
import com.example.printer.domain.repositories.PrintJobRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

/**
 * Use case for retrieving and filtering print jobs
 * Provides comprehensive job management and querying capabilities
 */
class GetPrintJobsUseCase(
    private val printJobRepository: PrintJobRepository
) {
    
    /**
     * Observes all print jobs with optional filtering and sorting
     * @param filter Optional filter criteria
     * @param sortBy Sorting criteria
     * @return Flow of filtered and sorted print jobs
     */
    fun observePrintJobs(
        filter: JobFilter = JobFilter.All,
        sortBy: SortCriteria = SortCriteria.DateDescending
    ): Flow<List<PrintJob>> {
        return printJobRepository.observePrintJobs()
            .map { jobs ->
                val filteredJobs = applyFilter(jobs, filter)
                applySorting(filteredJobs, sortBy)
            }
    }
    
    /**
     * Gets print jobs for a specific date range
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @param sortBy Sorting criteria
     * @return Flow of print jobs in the date range
     */
    fun getPrintJobsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        sortBy: SortCriteria = SortCriteria.DateDescending
    ): Flow<List<PrintJob>> {
        return printJobRepository.observePrintJobs()
            .map { jobs ->
                val dateFilteredJobs = jobs.filter { job ->
                    job.receivedAt >= startDate && job.receivedAt <= endDate
                }
                applySorting(dateFilteredJobs, sortBy)
            }
    }
    
    /**
     * Searches print jobs by query string
     * @param query Search query (searches filename, format, client info)
     * @param sortBy Sorting criteria
     * @return Flow of matching print jobs
     */
    fun searchPrintJobs(
        query: String,
        sortBy: SortCriteria = SortCriteria.DateDescending
    ): Flow<List<PrintJob>> {
        return printJobRepository.observePrintJobs()
            .map { jobs ->
                val searchResults = if (query.isBlank()) {
                    jobs
                } else {
                    jobs.filter { job ->
                        job.fileName.contains(query, ignoreCase = true) ||
                        job.originalFormat.contains(query, ignoreCase = true) ||
                        job.detectedFormat.contains(query, ignoreCase = true) ||
                        job.clientInfo.ipAddress.contains(query, ignoreCase = true) ||
                        job.documentInfo.title?.contains(query, ignoreCase = true) == true
                    }
                }
                applySorting(searchResults, sortBy)
            }
    }
    
    /**
     * Gets print job statistics
     * @return Flow of current statistics
     */
    fun getStatistics(): Flow<PrintJobRepository.PrintJobStatistics> {
        return printJobRepository.observePrintJobs()
            .map { jobs ->
                calculateStatistics(jobs)
            }
    }
    
    /**
     * Gets print jobs grouped by format
     * @return Flow of jobs grouped by detected format
     */
    fun getPrintJobsByFormat(): Flow<Map<String, List<PrintJob>>> {
        return printJobRepository.observePrintJobs()
            .map { jobs ->
                jobs.groupBy { it.detectedFormat }
                    .mapValues { (_, jobs) -> 
                        applySorting(jobs, SortCriteria.DateDescending)
                    }
            }
    }
    
    /**
     * Gets print jobs grouped by client
     * @return Flow of jobs grouped by client IP address
     */
    fun getPrintJobsByClient(): Flow<Map<String, List<PrintJob>>> {
        return printJobRepository.observePrintJobs()
            .map { jobs ->
                jobs.groupBy { it.clientInfo.ipAddress }
                    .mapValues { (_, jobs) -> 
                        applySorting(jobs, SortCriteria.DateDescending)
                    }
            }
    }
    
    /**
     * Gets recent print jobs (last 24 hours)
     * @return Flow of recent print jobs
     */
    fun getRecentPrintJobs(): Flow<List<PrintJob>> {
        val yesterday = LocalDateTime.now().minusDays(1)
        return getPrintJobsByDateRange(yesterday, LocalDateTime.now())
    }
    
    private fun applyFilter(jobs: List<PrintJob>, filter: JobFilter): List<PrintJob> {
        return when (filter) {
            is JobFilter.All -> jobs
            is JobFilter.ByStatus -> jobs.filter { it.status == filter.status }
            is JobFilter.ByFormat -> jobs.filter { it.detectedFormat.equals(filter.format, ignoreCase = true) }
            is JobFilter.ByClient -> jobs.filter { it.clientInfo.ipAddress == filter.ipAddress }
            is JobFilter.BySize -> jobs.filter { it.sizeBytes >= filter.minSize && it.sizeBytes <= filter.maxSize }
            is JobFilter.PdfOnly -> jobs.filter { it.isPdf() }
            is JobFilter.ImagesOnly -> jobs.filter { it.isImage() }
            is JobFilter.ErrorsOnly -> jobs.filter { it.status == PrintJob.PrintJobStatus.ERROR }
        }
    }
    
    private fun applySorting(jobs: List<PrintJob>, sortBy: SortCriteria): List<PrintJob> {
        return when (sortBy) {
            SortCriteria.DateAscending -> jobs.sortedBy { it.receivedAt }
            SortCriteria.DateDescending -> jobs.sortedByDescending { it.receivedAt }
            SortCriteria.SizeAscending -> jobs.sortedBy { it.sizeBytes }
            SortCriteria.SizeDescending -> jobs.sortedByDescending { it.sizeBytes }
            SortCriteria.NameAscending -> jobs.sortedBy { it.fileName.lowercase() }
            SortCriteria.NameDescending -> jobs.sortedByDescending { it.fileName.lowercase() }
            SortCriteria.FormatAscending -> jobs.sortedBy { it.detectedFormat }
            SortCriteria.FormatDescending -> jobs.sortedByDescending { it.detectedFormat }
            SortCriteria.StatusAscending -> jobs.sortedBy { it.status.ordinal }
            SortCriteria.StatusDescending -> jobs.sortedByDescending { it.status.ordinal }
        }
    }
    
    private fun calculateStatistics(jobs: List<PrintJob>): PrintJobRepository.PrintJobStatistics {
        val totalJobs = jobs.size
        val totalSizeBytes = jobs.sumOf { it.sizeBytes }
        val pdfJobs = jobs.count { it.isPdf() }
        val imageJobs = jobs.count { it.isImage() }
        val otherJobs = totalJobs - pdfJobs - imageJobs
        val averageSizeBytes = if (totalJobs > 0) totalSizeBytes / totalJobs else 0L
        val oldestJobDate = jobs.minByOrNull { it.receivedAt }?.receivedAt
        val newestJobDate = jobs.maxByOrNull { it.receivedAt }?.receivedAt
        
        return PrintJobRepository.PrintJobStatistics(
            totalJobs = totalJobs,
            totalSizeBytes = totalSizeBytes,
            pdfJobs = pdfJobs,
            imageJobs = imageJobs,
            otherJobs = otherJobs,
            averageSizeBytes = averageSizeBytes,
            oldestJobDate = oldestJobDate,
            newestJobDate = newestJobDate
        )
    }
    
    /**
     * Filter criteria for print jobs
     */
    sealed class JobFilter {
        object All : JobFilter()
        data class ByStatus(val status: PrintJob.PrintJobStatus) : JobFilter()
        data class ByFormat(val format: String) : JobFilter()
        data class ByClient(val ipAddress: String) : JobFilter()
        data class BySize(val minSize: Long, val maxSize: Long) : JobFilter()
        object PdfOnly : JobFilter()
        object ImagesOnly : JobFilter()
        object ErrorsOnly : JobFilter()
    }
    
    /**
     * Sorting criteria for print jobs
     */
    enum class SortCriteria {
        DateAscending,
        DateDescending,
        SizeAscending,
        SizeDescending,
        NameAscending,
        NameDescending,
        FormatAscending,
        FormatDescending,
        StatusAscending,
        StatusDescending
    }
} 