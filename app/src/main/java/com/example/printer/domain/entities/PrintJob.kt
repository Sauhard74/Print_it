package com.example.printer.domain.entities

import java.time.LocalDateTime

/**
 * Domain entity representing a print job
 * Contains all metadata and information about a captured print job
 */
data class PrintJob(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val originalFormat: String,
    val detectedFormat: String,
    val sizeBytes: Long,
    val receivedAt: LocalDateTime,
    val clientInfo: ClientInfo,
    val documentInfo: DocumentInfo,
    val status: PrintJobStatus
) {
    /**
     * Information about the client that sent the print job
     */
    data class ClientInfo(
        val ipAddress: String,
        val userAgent: String?,
        val operatingSystem: String?,
        val applicationName: String?
    )
    
    /**
     * Document-specific information
     */
    data class DocumentInfo(
        val title: String?,
        val pageCount: Int?,
        val colorMode: ColorMode,
        val mediaSize: String?,
        val resolution: String?,
        val compression: String?
    )
    
    /**
     * Color mode enumeration
     */
    enum class ColorMode {
        MONOCHROME,
        COLOR,
        UNKNOWN
    }
    
    /**
     * Print job processing status
     */
    enum class PrintJobStatus {
        RECEIVED,
        PROCESSING,
        COMPLETED,
        ERROR,
        DELETED
    }
    
    /**
     * Formats a human-readable file size
     */
    fun getFormattedSize(): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.2f MB".format(mb)
            kb >= 1.0 -> "%.2f KB".format(kb)
            else -> "$sizeBytes bytes"
        }
    }
    
    /**
     * Gets the file extension from the file name
     */
    fun getFileExtension(): String {
        return fileName.substringAfterLast(".", "")
    }
    
    /**
     * Determines if the print job is a PDF document
     */
    fun isPdf(): Boolean {
        return detectedFormat.equals("application/pdf", ignoreCase = true) ||
               getFileExtension().equals("pdf", ignoreCase = true)
    }
    
    /**
     * Determines if the print job is an image
     */
    fun isImage(): Boolean {
        return detectedFormat.startsWith("image/", ignoreCase = true) ||
               getFileExtension().lowercase() in listOf("jpg", "jpeg", "png", "gif", "bmp")
    }
    
    /**
     * Gets a display name for the print job
     */
    fun getDisplayName(): String {
        return documentInfo.title?.takeIf { it.isNotBlank() } ?: fileName
    }
} 