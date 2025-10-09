package com.example.printer.utils

import android.util.Log

/**
 * Document types supported by the application
 */
enum class DocumentType(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    POSTSCRIPT("ps", "application/postscript"),
    RAW("raw", "application/octet-stream"),
    TEXT("txt", "text/plain"),
    UNKNOWN("data", "application/octet-stream");
    
    companion object {
        fun fromMimeType(mimeType: String): DocumentType {
            return values().find { it.mimeType == mimeType } ?: UNKNOWN
        }
        
        fun fromExtension(extension: String): DocumentType {
            return values().find { it.extension.equals(extension, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Utility class for document type detection and format operations
 * Consolidates repeated logic from DocumentProcessor and other classes
 */
object DocumentTypeUtils {
    private const val TAG = "DocumentTypeUtils"
    
    /**
     * Detect the actual document type from binary data
     * Consolidated logic from DocumentProcessor.detectDocumentType()
     */
    fun detectDocumentType(bytes: ByteArray): DocumentType {
        if (bytes.isEmpty()) return DocumentType.UNKNOWN
        
        // Check for PDF signature
        if (bytes.size >= 4) {
            val pdfSignature = byteArrayOf('%'.toByte(), 'P'.toByte(), 'D'.toByte(), 'F'.toByte())
            for (i in 0..minOf(bytes.size - 4, 1024)) {
                if (bytes.sliceArray(i until i + 4).contentEquals(pdfSignature)) {
                    return DocumentType.PDF
                }
            }
        }
        
        // Check for JPEG signature
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return DocumentType.JPEG
        }
        
        // Check for PNG signature
        if (bytes.size >= 8) {
            val pngSignature = byteArrayOf(0x89.toByte(), 'P'.toByte(), 'N'.toByte(), 'G'.toByte(), 
                                          0x0D, 0x0A, 0x1A, 0x0A)
            if (bytes.sliceArray(0 until 8).contentEquals(pngSignature)) {
                return DocumentType.PNG
            }
        }
        
        // Check for PostScript signature
        if (bytes.size >= 2 && bytes[0] == '%'.toByte() && bytes[1] == '!'.toByte()) {
            return DocumentType.POSTSCRIPT
        }
        
        // Check if it's text (simple heuristic)
        val textBytes = bytes.take(1024)
        val printableCount = textBytes.count { byte ->
            val char = byte.toInt()
            char in 32..126 || char in arrayOf(9, 10, 13) // printable ASCII + tab, LF, CR
        }
        
        if (printableCount > textBytes.size * 0.8) {
            return DocumentType.TEXT
        }
        
        return DocumentType.UNKNOWN
    }
    
    /**
     * Extract clean document data from IPP wrapper
     * Consolidated logic from DocumentProcessor.extractDocumentData()
     */
    fun extractDocumentData(bytes: ByteArray, type: DocumentType): Pair<ByteArray, Int> {
        when (type) {
            DocumentType.PDF -> {
                // Find PDF start
                for (i in 0 until bytes.size - 4) {
                    if (bytes[i] == '%'.toByte() && 
                        bytes[i + 1] == 'P'.toByte() && 
                        bytes[i + 2] == 'D'.toByte() && 
                        bytes[i + 3] == 'F'.toByte()) {
                        return Pair(bytes.sliceArray(i until bytes.size), i)
                    }
                }
            }
            DocumentType.JPEG -> {
                // Find JPEG start
                for (i in 0 until bytes.size - 2) {
                    if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()) {
                        return Pair(bytes.sliceArray(i until bytes.size), i)
                    }
                }
            }
            DocumentType.PNG -> {
                // Find PNG start
                val pngSignature = byteArrayOf(0x89.toByte(), 'P'.toByte(), 'N'.toByte(), 'G'.toByte())
                for (i in 0 until bytes.size - 4) {
                    if (bytes.sliceArray(i until i + 4).contentEquals(pngSignature)) {
                        return Pair(bytes.sliceArray(i until bytes.size), i)
                    }
                }
            }
            else -> {
                // For other types, return as-is
                return Pair(bytes, 0)
            }
        }
        
        // If no specific format found, return original
        return Pair(bytes, 0)
    }
    
    /**
     * Generate appropriate filename for the document
     */
    fun generateFilename(jobId: Long, type: DocumentType): String {
        return "print_job_${jobId}.${type.extension}"
    }
    
    /**
     * Check if document type supports thumbnail generation
     */
    fun supportsThumbnail(type: DocumentType): Boolean {
        return when (type) {
            DocumentType.PDF, DocumentType.JPEG, DocumentType.PNG, DocumentType.TEXT -> true
            else -> false
        }
    }
    
    /**
     * Check if document type can be opened/viewed
     */
    fun canOpen(type: DocumentType): Boolean {
        return when (type) {
            DocumentType.PDF, DocumentType.JPEG, DocumentType.PNG, DocumentType.TEXT -> true
            else -> false
        }
    }
    
    /**
     * Get human-readable description of document type
     */
    fun getDescription(type: DocumentType): String {
        return when (type) {
            DocumentType.PDF -> "PDF Document"
            DocumentType.JPEG -> "JPEG Image"
            DocumentType.PNG -> "PNG Image"
            DocumentType.POSTSCRIPT -> "PostScript Document"
            DocumentType.TEXT -> "Text Document"
            DocumentType.RAW -> "Raw Data"
            DocumentType.UNKNOWN -> "Unknown Format"
        }
    }
}
