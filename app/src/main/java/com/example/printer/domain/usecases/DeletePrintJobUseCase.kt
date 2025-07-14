package com.example.printer.domain.usecases

import com.example.printer.domain.repositories.PrintJobRepository

/**
 * Use case for deleting a specific print job
 */
class DeletePrintJobUseCase(
    private val printJobRepository: PrintJobRepository
) {
    /**
     * Deletes a print job by ID
     */
    suspend operator fun invoke(jobId: Long): Result<Unit> {
        return try {
            printJobRepository.deletePrintJob(jobId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
} 