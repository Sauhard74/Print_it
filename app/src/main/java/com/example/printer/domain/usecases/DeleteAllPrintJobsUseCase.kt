package com.example.printer.domain.usecases

import com.example.printer.domain.repositories.PrintJobRepository

/**
 * Use case for deleting all print jobs
 */
class DeleteAllPrintJobsUseCase(
    private val printJobRepository: PrintJobRepository
) {
    /**
     * Deletes all print jobs
     */
    suspend operator fun invoke(): Result<Unit> {
        return try {
            printJobRepository.deleteAllPrintJobs()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 