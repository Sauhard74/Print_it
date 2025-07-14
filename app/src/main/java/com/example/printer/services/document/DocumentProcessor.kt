package com.example.printer.services.document

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Professional document processor for Chromium QA Virtual Printer
 * Handles format detection, PDF processing, and metadata extraction
 */
class DocumentProcessor(
    private val context: Context
) {
    
    private val printJobsDirectory: File by lazy {
        File(context.filesDir, "print_jobs").apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * Document processing result
     */
    data class ProcessingResult(
        val success: Boolean,
        val filePath: String?,
        val detectedFormat: String,
        val originalSize: Long,
        val processedSize: Long,
        val metadata: DocumentMetadata,
        val errorMessage: String? = null
    )
    
    /**
     * Document metadata extracted during processing
     */
    data class DocumentMetadata(
        val title: String?,
        val pageCount: Int?,
        val colorMode: ColorMode,
        val mediaSize: String?,
        val resolution: String?,
        val compression: String?,
        val creator: String?,
        val producer: String?,
        val creationDate: String?,
        val modificationDate: String?,
        val hasImages: Boolean,
        val hasText: Boolean,
        val estimatedPrintTime: Long?, // milliseconds
        val printComplexity: PrintComplexity
    )
    
    enum class ColorMode {
        MONOCHROME,
        COLOR,
        GRAYSCALE,
        UNKNOWN
    }
    
    enum class PrintComplexity {
        SIMPLE,     // Basic text/simple graphics
        MODERATE,   // Mixed content with images
        COMPLEX,    // Complex layouts, many images, vector graphics
        UNKNOWN
    }
    
    /**
     * Processes document data with comprehensive format detection and conversion
     * @param documentData Raw document bytes
     * @param originalFormat Format as declared by client
     * @param jobId Unique job identifier
     * @param preserveOriginal Whether to keep original format alongside processed version
     * @return Processing result with file path and metadata
     */
    suspend fun processDocument(
        documentData: ByteArray,
        originalFormat: String,
        jobId: Long,
        preserveOriginal: Boolean = true
    ): ProcessingResult {
        
        if (documentData.isEmpty()) {
            return ProcessingResult(
                success = false,
                filePath = null,
                detectedFormat = "unknown",
                originalSize = 0,
                processedSize = 0,
                metadata = createEmptyMetadata(),
                errorMessage = "Document data is empty"
            )
        }
        
        try {
            // Step 1: Detect actual format from binary data
            val detectedFormat = detectDocumentFormat(documentData)
            
            // Step 2: Extract metadata before processing
            val metadata = extractMetadata(documentData, detectedFormat)
            
            // Step 3: Process based on detected format
            val processedData = when (detectedFormat) {
                "application/pdf" -> processPdfDocument(documentData)
                "application/postscript" -> convertPostScriptToPdf(documentData)
                "image/jpeg", "image/png", "image/gif", "image/bmp" -> convertImageToPdf(documentData, detectedFormat)
                "text/plain" -> convertTextToPdf(documentData)
                else -> wrapInPdf(documentData, detectedFormat)
            }
            
            // Step 4: Save processed document
            val fileName = generateFileName(jobId, detectedFormat)
            val filePath = saveProcessedDocument(processedData, fileName)
            
            // Step 5: Save original if requested
            if (preserveOriginal && detectedFormat != originalFormat) {
                val originalFileName = generateFileNameWithSuffix(jobId, "original", getExtensionForFormat(originalFormat))
                saveProcessedDocument(documentData, originalFileName)
            }
            
            return ProcessingResult(
                success = true,
                filePath = filePath,
                detectedFormat = detectedFormat,
                originalSize = documentData.size.toLong(),
                processedSize = processedData.size.toLong(),
                metadata = metadata
            )
            
        } catch (e: Exception) {
            return ProcessingResult(
                success = false,
                filePath = null,
                detectedFormat = "unknown",
                originalSize = documentData.size.toLong(),
                processedSize = 0,
                metadata = createEmptyMetadata(),
                errorMessage = "Processing failed: ${e.message}"
            )
        }
    }
    
    /**
     * Detects document format from binary signature analysis
     */
    private fun detectDocumentFormat(data: ByteArray): String {
        if (data.size < 4) return "application/octet-stream"
        
        // PDF signature
        if (data.size >= 4 && 
            data[0] == '%'.toByte() && 
            data[1] == 'P'.toByte() && 
            data[2] == 'D'.toByte() && 
            data[3] == 'F'.toByte()) {
            return "application/pdf"
        }
        
        // PostScript signature
        if (data.size >= 2 &&
            data[0] == '%'.toByte() && 
            data[1] == '!'.toByte()) {
            return "application/postscript"
        }
        
        // JPEG signature
        if (data.size >= 3 &&
            data[0] == 0xFF.toByte() && 
            data[1] == 0xD8.toByte() && 
            data[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        
        // PNG signature
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() && 
            data[1] == 0x50.toByte() && 
            data[2] == 0x4E.toByte() && 
            data[3] == 0x47.toByte() &&
            data[4] == 0x0D.toByte() && 
            data[5] == 0x0A.toByte() && 
            data[6] == 0x1A.toByte() && 
            data[7] == 0x0A.toByte()) {
            return "image/png"
        }
        
        // GIF signature
        if (data.size >= 6 &&
            data[0] == 'G'.toByte() && 
            data[1] == 'I'.toByte() && 
            data[2] == 'F'.toByte() && 
            data[3] == '8'.toByte() &&
            (data[4] == '7'.toByte() || data[4] == '9'.toByte()) &&
            data[5] == 'a'.toByte()) {
            return "image/gif"
        }
        
        // BMP signature
        if (data.size >= 2 &&
            data[0] == 'B'.toByte() && 
            data[1] == 'M'.toByte()) {
            return "image/bmp"
        }
        
        // Check for text content (heuristic)
        if (isTextContent(data)) {
            return "text/plain"
        }
        
        return "application/octet-stream"
    }
    
    /**
     * Extracts comprehensive metadata from document
     */
    private fun extractMetadata(data: ByteArray, format: String): DocumentMetadata {
        return when (format) {
            "application/pdf" -> extractPdfMetadata(data)
            "image/jpeg", "image/png", "image/gif", "image/bmp" -> extractImageMetadata(data, format)
            "text/plain" -> extractTextMetadata(data)
            else -> createEmptyMetadata()
        }
    }
    
    /**
     * Processes PDF documents - validates and optimizes if needed
     */
    private fun processPdfDocument(data: ByteArray): ByteArray {
        // For now, return as-is. In full implementation:
        // - Validate PDF structure
        // - Optimize for printing
        // - Extract/embed print-specific metadata
        // - Ensure compatibility with various PDF viewers
        return data
    }
    
    /**
     * Converts PostScript to PDF
     */
    private fun convertPostScriptToPdf(data: ByteArray): ByteArray {
        // TODO: Implement PostScript to PDF conversion
        // For now, wrap in minimal PDF structure
        return wrapInPdf(data, "application/postscript")
    }
    
    /**
     * Converts images to PDF format
     */
    private fun convertImageToPdf(data: ByteArray, imageFormat: String): ByteArray {
        // TODO: Implement proper image to PDF conversion
        // For now, create minimal PDF with embedded image
        return createPdfWithImage(data, imageFormat)
    }
    
    /**
     * Converts plain text to PDF
     */
    private fun convertTextToPdf(data: ByteArray): ByteArray {
        // TODO: Implement text to PDF conversion with proper formatting
        val text = String(data, Charsets.UTF_8)
        return createPdfWithText(text)
    }
    
    /**
     * Wraps unknown content in minimal PDF structure
     */
    private fun wrapInPdf(data: ByteArray, originalFormat: String): ByteArray {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        
        // Create minimal PDF with metadata about original format
        val pdfContent = """
            %PDF-1.7
            %Binary data wrapper
            
            1 0 obj
            <<
                /Type /Catalog
                /Pages 2 0 R
                /Metadata 3 0 R
            >>
            endobj
            
            2 0 obj
            <<
                /Type /Pages
                /Kids [4 0 R]
                /Count 1
            >>
            endobj
            
            3 0 obj
            <<
                /Type /Metadata
                /Subtype /XML
                /Length 200
            >>
            stream
            <?xml version="1.0"?>
            <metadata>
                <original_format>$originalFormat</original_format>
                <conversion_timestamp>$timestamp</conversion_timestamp>
                <data_size>${data.size}</data_size>
            </metadata>
            endstream
            endobj
            
            4 0 obj
            <<
                /Type /Page
                /Parent 2 0 R
                /MediaBox [0 0 612 792]
                /Contents 5 0 R
                /Resources << /Font << /F1 6 0 R >> >>
            >>
            endobj
            
            5 0 obj
            <<
                /Length 150
            >>
            stream
            BT
            /F1 12 Tf
            72 720 Td
            (Document Format: $originalFormat) Tj
            0 -20 Td
            (Size: ${data.size} bytes) Tj
            0 -20 Td
            (Converted: $timestamp) Tj
            ET
            endstream
            endobj
            
            6 0 obj
            <<
                /Type /Font
                /Subtype /Type1
                /BaseFont /Helvetica
            >>
            endobj
            
            xref
            0 7
            0000000000 65535 f 
            0000000015 00000 n 
            0000000074 00000 n 
            0000000120 00000 n 
            0000000426 00000 n 
            0000000550 00000 n 
            0000000750 00000 n 
            trailer
            <<
                /Size 7
                /Root 1 0 R
            >>
            startxref
            830
            %%EOF
        """.trimIndent()
        
        return pdfContent.toByteArray()
    }
    
    // Helper methods for metadata extraction
    
    private fun extractPdfMetadata(data: ByteArray): DocumentMetadata {
        // TODO: Implement comprehensive PDF metadata extraction
        // For now, return basic metadata
        return DocumentMetadata(
            title = null,
            pageCount = estimatePageCount(data),
            colorMode = ColorMode.UNKNOWN,
            mediaSize = "A4",
            resolution = "600dpi",
            compression = null,
            creator = null,
            producer = null,
            creationDate = null,
            modificationDate = null,
            hasImages = data.toString().contains("/Image"),
            hasText = data.toString().contains("/Font"),
            estimatedPrintTime = estimatePrintTime(data.size),
            printComplexity = estimatePrintComplexity(data)
        )
    }
    
    private fun extractImageMetadata(data: ByteArray, format: String): DocumentMetadata {
        return DocumentMetadata(
            title = null,
            pageCount = 1,
            colorMode = ColorMode.COLOR, // Assume color for images
            mediaSize = "A4",
            resolution = "300dpi", // Default for images
            compression = getCompressionType(format),
            creator = null,
            producer = "Android Virtual Printer",
            creationDate = null,
            modificationDate = null,
            hasImages = true,
            hasText = false,
            estimatedPrintTime = estimatePrintTime(data.size),
            printComplexity = PrintComplexity.MODERATE
        )
    }
    
    private fun extractTextMetadata(data: ByteArray): DocumentMetadata {
        val text = String(data, Charsets.UTF_8)
        val lineCount = text.count { it == '\n' } + 1
        val wordCount = text.split("\\s+".toRegex()).size
        
        return DocumentMetadata(
            title = null,
            pageCount = (lineCount / 50) + 1, // Estimate pages
            colorMode = ColorMode.MONOCHROME,
            mediaSize = "A4",
            resolution = "600dpi",
            compression = "none",
            creator = null,
            producer = "Android Virtual Printer",
            creationDate = null,
            modificationDate = null,
            hasImages = false,
            hasText = true,
            estimatedPrintTime = estimatePrintTime(data.size),
            printComplexity = if (wordCount > 1000) PrintComplexity.MODERATE else PrintComplexity.SIMPLE
        )
    }
    
    // Utility methods
    
    private fun isTextContent(data: ByteArray): Boolean {
        // Simple heuristic: check if most bytes are printable ASCII
        val printableCount = data.count { byte ->
            byte in 32..126 || byte == 9.toByte() || byte == 10.toByte() || byte == 13.toByte()
        }
        return data.isNotEmpty() && printableCount.toDouble() / data.size > 0.7
    }
    
    private fun estimatePageCount(data: ByteArray): Int {
        // Simple estimation based on data size and format
        return when {
            data.size < 50000 -> 1
            data.size < 500000 -> (data.size / 100000) + 1
            else -> (data.size / 200000) + 1
        }
    }
    
    private fun estimatePrintTime(dataSize: Int): Long {
        // Estimate in milliseconds based on complexity and size
        val baseTime = 5000L // 5 seconds base
        val sizeMultiplier = (dataSize / 100000) * 1000L // 1 second per 100KB
        return baseTime + sizeMultiplier
    }
    
    private fun estimatePrintComplexity(data: ByteArray): PrintComplexity {
        val dataString = String(data, Charsets.ISO_8859_1)
        return when {
            dataString.contains("/Image") && dataString.contains("/Font") -> PrintComplexity.COMPLEX
            dataString.contains("/Image") || dataString.contains("/Font") -> PrintComplexity.MODERATE
            else -> PrintComplexity.SIMPLE
        }
    }
    
    private fun getCompressionType(format: String): String {
        return when (format) {
            "image/jpeg" -> "JPEG"
            "image/png" -> "PNG"
            "image/gif" -> "LZW"
            else -> "none"
        }
    }
    
    private fun generateFileName(jobId: Long, format: String, extension: String? = null): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val ext = extension ?: getExtensionForFormat(format)
        return "print_job_${jobId}_${timestamp}.$ext"
    }
    
    private fun generateFileNameWithSuffix(jobId: Long, suffix: String, extension: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "print_job_${jobId}_${suffix}_${timestamp}.$extension"
    }
    
    private fun getExtensionForFormat(format: String): String {
        return when (format) {
            "application/pdf" -> "pdf"
            "application/postscript" -> "ps"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "text/plain" -> "txt"
            else -> "dat"
        }
    }
    
    private fun saveProcessedDocument(data: ByteArray, fileName: String): String {
        val file = File(printJobsDirectory, fileName)
        FileOutputStream(file).use { it.write(data) }
        return file.absolutePath
    }
    
    private fun createEmptyMetadata(): DocumentMetadata {
        return DocumentMetadata(
            title = null,
            pageCount = null,
            colorMode = ColorMode.UNKNOWN,
            mediaSize = null,
            resolution = null,
            compression = null,
            creator = null,
            producer = null,
            creationDate = null,
            modificationDate = null,
            hasImages = false,
            hasText = false,
            estimatedPrintTime = null,
            printComplexity = PrintComplexity.UNKNOWN
        )
    }
    
    private fun createPdfWithImage(imageData: ByteArray, imageFormat: String): ByteArray {
        // TODO: Implement proper PDF creation with embedded image
        // For now, create minimal PDF structure
        return wrapInPdf(imageData, imageFormat)
    }
    
    private fun createPdfWithText(text: String): ByteArray {
        // TODO: Implement proper PDF creation with formatted text
        // For now, create minimal PDF structure
        return wrapInPdf(text.toByteArray(), "text/plain")
    }
} 