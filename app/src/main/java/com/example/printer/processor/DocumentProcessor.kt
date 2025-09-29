package com.example.printer.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.printer.queue.PrintJob
import com.example.printer.queue.PrintJobQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.regex.Pattern

/**
 * Document processing result
 */
data class DocumentProcessingResult(
    val success: Boolean,
    val originalSize: Long,
    val processedSize: Long,
    val documentType: DocumentType,
    val pageCount: Int = 1,
    val metadata: DocumentMetadata,
    val thumbnailPath: String? = null,
    val errorMessage: String? = null
)

/**
 * Document types supported by the processor
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
 * Document metadata extracted during processing
 */
data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val creationDate: String? = null,
    val modificationDate: String? = null,
    val keywords: String? = null,
    val pageCount: Int = 1,
    val documentSize: Long = 0,
    val colorSpace: String? = null,
    val resolution: String? = null,
    val hasImages: Boolean = false,
    val hasText: Boolean = false,
    val encryptionLevel: String? = null,
    val customProperties: Map<String, String> = emptyMap()
)

/**
 * Enhanced document processor with viewer integration and advanced features
 */
class DocumentProcessor private constructor(private val context: Context) {
    companion object {
        private const val TAG = "DocumentProcessor"
        private const val THUMBNAIL_SIZE = 300
        
        @Volatile
        private var INSTANCE: DocumentProcessor? = null
        
        fun getInstance(context: Context): DocumentProcessor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocumentProcessor(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val printJobQueue = PrintJobQueue.getInstance(context)
    
    /**
     * Process a document with full metadata extraction and thumbnail generation
     */
    suspend fun processDocument(
        documentBytes: ByteArray,
        jobId: Long,
        documentFormat: String,
        userMetadata: Map<String, Any> = emptyMap()
    ): DocumentProcessingResult = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Processing document for job $jobId, format: $documentFormat, size: ${documentBytes.size} bytes")
        
        try {
            // Detect actual document type
            val detectedType = detectDocumentType(documentBytes)
            Log.d(TAG, "Detected document type: $detectedType")
            
            // Extract document data if it's embedded in IPP data
            val (cleanDocumentBytes, actualStartIndex) = extractDocumentData(documentBytes, detectedType)
            
            // Generate file paths
            val printJobsDir = File(context.filesDir, "print_jobs")
            if (!printJobsDir.exists()) {
                printJobsDir.mkdirs()
            }
            
            val filename = generateFilename(jobId, detectedType)
            val documentFile = File(printJobsDir, filename)
            val thumbnailFile = File(printJobsDir, "thumb_$filename.png")
            
            // Save the processed document
            FileOutputStream(documentFile).use { output ->
                output.write(cleanDocumentBytes)
            }
            
            // Extract metadata
            val metadata = extractDocumentMetadata(cleanDocumentBytes, detectedType, userMetadata)
            
            // Generate thumbnail
            val thumbnailPath = generateThumbnail(cleanDocumentBytes, detectedType, thumbnailFile)
            
            // Update job queue with processing info
            printJobQueue.updateJobMetadata(jobId, mapOf(
                "original_size" to documentBytes.size,
                "processed_size" to cleanDocumentBytes.size,
                "document_type" to detectedType.name,
                "page_count" to metadata.pageCount,
                "has_thumbnail" to (thumbnailPath != null),
                "processing_time" to System.currentTimeMillis()
            ))
            
            DocumentProcessingResult(
                success = true,
                originalSize = documentBytes.size.toLong(),
                processedSize = cleanDocumentBytes.size.toLong(),
                documentType = detectedType,
                pageCount = metadata.pageCount,
                metadata = metadata,
                thumbnailPath = thumbnailPath,
                errorMessage = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing document for job $jobId", e)
            
            // Save as raw data if processing fails
            val printJobsDir = File(context.filesDir, "print_jobs")
            if (!printJobsDir.exists()) {
                printJobsDir.mkdirs()
            }
            
            val fallbackFilename = "job_${jobId}_${System.currentTimeMillis()}.data"
            val fallbackFile = File(printJobsDir, fallbackFilename)
            
            try {
                FileOutputStream(fallbackFile).use { output ->
                    output.write(documentBytes)
                }
            } catch (saveError: Exception) {
                Log.e(TAG, "Failed to save fallback file", saveError)
            }
            
            DocumentProcessingResult(
                success = false,
                originalSize = documentBytes.size.toLong(),
                processedSize = 0L,
                documentType = DocumentType.UNKNOWN,
                pageCount = 1,
                metadata = DocumentMetadata(documentSize = documentBytes.size.toLong()),
                thumbnailPath = null,
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Detect the actual document type from binary data
     */
    private fun detectDocumentType(bytes: ByteArray): DocumentType {
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
     */
    private fun extractDocumentData(bytes: ByteArray, type: DocumentType): Pair<ByteArray, Int> {
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
     * Extract metadata from document
     */
    private suspend fun extractDocumentMetadata(
        bytes: ByteArray, 
        type: DocumentType,
        userMetadata: Map<String, Any>
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        
        val metadata = DocumentMetadata(
            documentSize = bytes.size.toLong(),
            customProperties = userMetadata.mapValues { it.value.toString() }
        )
        
        when (type) {
            DocumentType.PDF -> extractPdfMetadata(bytes, metadata)
            DocumentType.JPEG -> extractImageMetadata(bytes, metadata, "JPEG")
            DocumentType.PNG -> extractImageMetadata(bytes, metadata, "PNG")
            DocumentType.TEXT -> extractTextMetadata(bytes, metadata)
            else -> metadata
        }
    }
    
    /**
     * Extract PDF metadata (basic implementation)
     */
    private fun extractPdfMetadata(bytes: ByteArray, baseMetadata: DocumentMetadata): DocumentMetadata {
        try {
            // This is a simplified PDF metadata extraction
            // In a real implementation, you'd use a PDF library like Apache PDFBox
            
            val content = String(bytes, Charsets.ISO_8859_1)
            
            // Extract basic info from PDF dictionary
            val titleMatch = Pattern.compile("/Title\\s*\\(([^)]+)\\)").matcher(content)
            val authorMatch = Pattern.compile("/Author\\s*\\(([^)]+)\\)").matcher(content)
            val creatorMatch = Pattern.compile("/Creator\\s*\\(([^)]+)\\)").matcher(content)
            val producerMatch = Pattern.compile("/Producer\\s*\\(([^)]+)\\)").matcher(content)
            
            // Count pages (simple heuristic)
            val pageCount = maxOf(1, Pattern.compile("/Type\\s*/Page\\b").matcher(content).let { matcher ->
                var count = 0
                while (matcher.find()) count++
                count
            })
            
            return baseMetadata.copy(
                title = if (titleMatch.find()) titleMatch.group(1) else null,
                author = if (authorMatch.find()) authorMatch.group(1) else null,
                creator = if (creatorMatch.find()) creatorMatch.group(1) else null,
                producer = if (producerMatch.find()) producerMatch.group(1) else null,
                pageCount = pageCount,
                hasText = content.contains("/Font") || content.contains("BT "), // Basic text detection
                hasImages = content.contains("/Image") || content.contains("/XObject")
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting PDF metadata", e)
            return baseMetadata.copy(pageCount = 1)
        }
    }
    
    /**
     * Extract image metadata
     */
    private fun extractImageMetadata(bytes: ByteArray, baseMetadata: DocumentMetadata, format: String): DocumentMetadata {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            
            return baseMetadata.copy(
                resolution = "${options.outWidth}x${options.outHeight}",
                colorSpace = when (options.outConfig) {
                    Bitmap.Config.RGB_565 -> "RGB565"
                    Bitmap.Config.ARGB_8888 -> "ARGB8888"
                    Bitmap.Config.ALPHA_8 -> "ALPHA8"
                    else -> "Unknown"
                },
                hasImages = true,
                pageCount = 1
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting image metadata", e)
            return baseMetadata.copy(pageCount = 1, hasImages = true)
        }
    }
    
    /**
     * Extract text metadata
     */
    private fun extractTextMetadata(bytes: ByteArray, baseMetadata: DocumentMetadata): DocumentMetadata {
        try {
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.lines()
            val wordCount = text.split("\\s+".toRegex()).size
            
            return baseMetadata.copy(
                hasText = true,
                pageCount = maxOf(1, lines.size / 50), // Approximate pages
                customProperties = baseMetadata.customProperties + mapOf(
                    "line_count" to lines.size.toString(),
                    "word_count" to wordCount.toString(),
                    "character_count" to text.length.toString()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text metadata", e)
            return baseMetadata.copy(pageCount = 1, hasText = true)
        }
    }
    
    /**
     * Generate thumbnail for the document
     */
    private suspend fun generateThumbnail(
        bytes: ByteArray, 
        type: DocumentType, 
        thumbnailFile: File
    ): String? = withContext(Dispatchers.IO) {
        
        try {
            val bitmap = when (type) {
                DocumentType.PDF -> generatePdfThumbnail(bytes)
                DocumentType.JPEG, DocumentType.PNG -> generateImageThumbnail(bytes)
                DocumentType.TEXT -> generateTextThumbnail(bytes)
                else -> null
            }
            
            bitmap?.let {
                // Save thumbnail
                FileOutputStream(thumbnailFile).use { output ->
                    it.compress(Bitmap.CompressFormat.PNG, 90, output)
                }
                it.recycle()
                thumbnailFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail", e)
            null
        }
    }
    
    /**
     * Generate PDF thumbnail (simplified - would need proper PDF library in production)
     */
    private fun generatePdfThumbnail(bytes: ByteArray): Bitmap? {
        try {
            // Create a temporary file for PDF rendering
            val tempFile = File.createTempFile("pdf_temp", ".pdf", context.cacheDir)
            tempFile.writeBytes(bytes)
            
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                THUMBNAIL_SIZE, 
                                (THUMBNAIL_SIZE * page.height / page.width), 
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return bitmap
                        }
                    }
                }
            }
            
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating PDF thumbnail", e)
        }
        
        return null
    }
    
    /**
     * Generate image thumbnail
     */
    private fun generateImageThumbnail(bytes: ByteArray): Bitmap? {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            
            // Calculate scale
            val scale = maxOf(
                options.outWidth / THUMBNAIL_SIZE,
                options.outHeight / THUMBNAIL_SIZE
            )
            
            val scaledOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, scaledOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating image thumbnail", e)
            return null
        }
    }
    
    /**
     * Generate text thumbnail
     */
    private fun generateTextThumbnail(bytes: ByteArray): Bitmap? {
        try {
            val text = String(bytes, Charsets.UTF_8).take(200) // First 200 characters
            
            val bitmap = Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            
            val paint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 12f
                isAntiAlias = true
            }
            
            val lines = text.lines()
            var y = 20f
            
            for (line in lines.take(15)) { // Max 15 lines
                canvas.drawText(line.take(30), 10f, y, paint) // Max 30 chars per line
                y += 20f
                if (y > THUMBNAIL_SIZE - 20) break
            }
            
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error generating text thumbnail", e)
            return null
        }
    }
    
    /**
     * Generate appropriate filename for the document
     */
    private fun generateFilename(jobId: Long, type: DocumentType): String {
        val timestamp = System.currentTimeMillis()
        return "job_${jobId}_${timestamp}.${type.extension}"
    }
    
    /**
     * Get document preview information
     */
    suspend fun getDocumentPreview(filePath: String): DocumentPreview? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
            
            val bytes = file.readBytes()
            val type = detectDocumentType(bytes)
            val metadata = extractDocumentMetadata(bytes, type, emptyMap())
            
            DocumentPreview(
                filePath = filePath,
                documentType = type,
                metadata = metadata,
                fileSize = file.length(),
                lastModified = file.lastModified()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting document preview", e)
            null
        }
    }
}

/**
 * Document preview information
 */
data class DocumentPreview(
    val filePath: String,
    val documentType: DocumentType,
    val metadata: DocumentMetadata,
    val fileSize: Long,
    val lastModified: Long
)