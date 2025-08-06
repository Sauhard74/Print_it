package com.example.printer.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Advanced document converter for handling raw binary print job data
 * and converting it to viewable formats
 */
object DocumentConverter {
    private const val TAG = "DocumentConverter"
    
    data class DocumentInfo(
        val format: String,
        val mimeType: String,
        val extension: String,
        val isValid: Boolean,
        val size: Long,
        val hasHeader: Boolean
    )
    
    /**
     * Enhanced document format detection with comprehensive binary analysis
     */
    fun detectDocumentFormat(file: File): DocumentInfo {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(32) // Read more bytes for better detection
                val bytesRead = stream.read(header)
                
                if (bytesRead < 4) {
                    return DocumentInfo("UNKNOWN", "application/octet-stream", ".bin", false, file.length(), false)
                }
                
                // PDF Detection (Enhanced)
                if (detectPDF(header, bytesRead)) {
                    return DocumentInfo("PDF", "application/pdf", ".pdf", true, file.length(), true)
                }
                
                // JPEG Detection (Enhanced)
                if (detectJPEG(header, bytesRead)) {
                    return DocumentInfo("JPEG", "image/jpeg", ".jpg", true, file.length(), true)
                }
                
                // PNG Detection (Enhanced)
                if (detectPNG(header, bytesRead)) {
                    return DocumentInfo("PNG", "image/png", ".png", true, file.length(), true)
                }
                
                // GIF Detection
                if (detectGIF(header, bytesRead)) {
                    return DocumentInfo("GIF", "image/gif", ".gif", true, file.length(), true)
                }
                
                // TIFF Detection
                if (detectTIFF(header, bytesRead)) {
                    return DocumentInfo("TIFF", "image/tiff", ".tiff", true, file.length(), true)
                }
                
                // PostScript Detection
                if (detectPostScript(header, bytesRead)) {
                    return DocumentInfo("PostScript", "application/postscript", ".ps", true, file.length(), true)
                }
                
                // EMF Detection
                if (detectEMF(header, bytesRead)) {
                    return DocumentInfo("EMF", "image/x-emf", ".emf", true, file.length(), true)
                }
                
                // Plain Text Detection
                if (detectPlainText(header, bytesRead)) {
                    return DocumentInfo("TEXT", "text/plain", ".txt", true, file.length(), true)
                }
                
                // HTML Detection
                if (detectHTML(header, bytesRead)) {
                    return DocumentInfo("HTML", "text/html", ".html", true, file.length(), true)
                }
                
                // Raw Binary with possible IPP wrapper
                val cleanedFormat = attemptIPPUnwrapping(file)
                if (cleanedFormat != null) {
                    return cleanedFormat
                }
                
                // Fallback - treat as raw binary
                DocumentInfo("RAW", "application/octet-stream", ".bin", false, file.length(), false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting document format", e)
            DocumentInfo("ERROR", "application/octet-stream", ".bin", false, file.length(), false)
        }
    }
    
    private fun detectPDF(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 4) return false
        return header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && 
               header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
    }
    
    private fun detectJPEG(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 3) return false
        return header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    }
    
    private fun detectPNG(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 8) return false
        return header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && 
               header[3] == 0x47.toByte() && header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() && 
               header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()
    }
    
    private fun detectGIF(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 6) return false
        val gif87a = "GIF87a".toByteArray()
        val gif89a = "GIF89a".toByteArray()
        return header.take(6).toByteArray().contentEquals(gif87a) || 
               header.take(6).toByteArray().contentEquals(gif89a)
    }
    
    private fun detectTIFF(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 4) return false
        return (header[0] == 0x49.toByte() && header[1] == 0x49.toByte() && 
                header[2] == 0x2A.toByte() && header[3] == 0x00.toByte()) ||
               (header[0] == 0x4D.toByte() && header[1] == 0x4D.toByte() && 
                header[2] == 0x00.toByte() && header[3] == 0x2A.toByte())
    }
    
    private fun detectPostScript(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 4) return false
        return header[0] == '%'.toByte() && header[1] == '!'.toByte() && 
               header[2] == 'P'.toByte() && header[3] == 'S'.toByte()
    }
    
    private fun detectEMF(header: ByteArray, bytesRead: Int): Boolean {
        if (bytesRead < 4) return false
        return header[0] == 0x24.toByte() && header[1] == 0x00.toByte() &&
               header[2] == 0x00.toByte() && header[3] == 0x00.toByte()
    }
    
    private fun detectPlainText(header: ByteArray, bytesRead: Int): Boolean {
        // Check if most bytes are printable ASCII
        val printableCount = header.take(bytesRead).count { byte ->
            (byte >= 0x20.toByte() && byte <= 0x7E.toByte()) || byte == 0x09.toByte() || byte == 0x0A.toByte() || byte == 0x0D.toByte()
        }
        return printableCount.toDouble() / bytesRead > 0.8
    }
    
    private fun detectHTML(header: ByteArray, bytesRead: Int): Boolean {
        val headerString = String(header, 0, minOf(bytesRead, 32)).lowercase()
        return headerString.contains("<html") || headerString.contains("<!doctype html")
    }
    
    /**
     * Attempts to unwrap IPP-wrapped documents by finding and extracting the actual document content
     */
    private fun attemptIPPUnwrapping(file: File): DocumentInfo? {
        return try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(1024)
                val allBytes = mutableListOf<Byte>()
                
                var totalRead = 0
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1 && totalRead < 8192) {
                    allBytes.addAll(buffer.take(bytesRead))
                    totalRead += bytesRead
                }
                
                val fullHeader = allBytes.toByteArray()
                
                // Look for PDF header in the data
                val pdfStart = findPattern(fullHeader, byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte())) // %PDF
                if (pdfStart >= 0) {
                    Log.d(TAG, "Found PDF header at offset $pdfStart in IPP wrapped data")
                    return DocumentInfo("PDF", "application/pdf", ".pdf", true, file.length(), true)
                }
                
                // Look for JPEG header in the data
                val jpegStart = findPattern(fullHeader, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                if (jpegStart >= 0) {
                    Log.d(TAG, "Found JPEG header at offset $jpegStart in IPP wrapped data")
                    return DocumentInfo("JPEG", "image/jpeg", ".jpg", true, file.length(), true)
                }
                
                // Look for PNG header in the data
                val pngHeader = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
                val pngStart = findPattern(fullHeader, pngHeader)
                if (pngStart >= 0) {
                    Log.d(TAG, "Found PNG header at offset $pngStart in IPP wrapped data")
                    return DocumentInfo("PNG", "image/png", ".png", true, file.length(), true)
                }
                
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unwrapping IPP document", e)
            null
        }
    }
    
    /**
     * Finds a byte pattern within a byte array
     */
    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..(data.size - pattern.size)) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
    
    /**
     * Converts raw binary print job to a viewable file
     */
    fun convertToViewableFile(context: Context, originalFile: File): File? {
        return try {
            val docInfo = detectDocumentFormat(originalFile)
            Log.d(TAG, "Converting file ${originalFile.name}: Format=${docInfo.format}, Valid=${docInfo.isValid}")
            
            if (!docInfo.isValid && docInfo.format != "RAW") {
                Log.w(TAG, "Cannot convert invalid document format: ${docInfo.format}")
                return null
            }
            
            val tempDir = File(context.cacheDir, "viewable_docs")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            val timestamp = System.currentTimeMillis()
            val outputFile = File(tempDir, "document_${timestamp}${docInfo.extension}")
            
            when (docInfo.format) {
                "PDF", "JPEG", "PNG", "GIF", "TIFF", "EMF" -> {
                    // Direct copy for already valid formats
                    originalFile.copyTo(outputFile, overwrite = true)
                    Log.d(TAG, "Direct copy successful: ${outputFile.name}")
                }
                
                "RAW" -> {
                    // Try to extract document from IPP wrapper
                    val extractedFile = extractFromIPPWrapper(originalFile, outputFile)
                    if (extractedFile == null) {
                        // Fallback: copy as-is and let the system handle it
                        originalFile.copyTo(outputFile, overwrite = true)
                        Log.d(TAG, "Fallback copy for RAW data: ${outputFile.name}")
                    }
                }
                
                "PostScript" -> {
                    // Copy PostScript file with proper extension
                    val psFile = File(tempDir, "document_${timestamp}.ps")
                    originalFile.copyTo(psFile, overwrite = true)
                    return psFile
                }
                
                "TEXT", "HTML" -> {
                    // Copy text files directly
                    originalFile.copyTo(outputFile, overwrite = true)
                    Log.d(TAG, "Text file copy successful: ${outputFile.name}")
                }
                
                else -> {
                    Log.w(TAG, "Unsupported format for conversion: ${docInfo.format}")
                    return null
                }
            }
            
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error converting file to viewable format", e)
            null
        }
    }
    
    /**
     * Extracts actual document content from IPP wrapper
     */
    private fun extractFromIPPWrapper(inputFile: File, outputFile: File): File? {
        return try {
            inputFile.inputStream().use { input ->
                val allBytes = input.readBytes()
                
                // Look for document headers within the IPP data
                val pdfStart = findPattern(allBytes, byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte())) // %PDF
                if (pdfStart >= 0) {
                    FileOutputStream(File(outputFile.parent, "document_${System.currentTimeMillis()}.pdf")).use { output ->
                        output.write(allBytes.sliceArray(pdfStart until allBytes.size))
                    }
                    Log.d(TAG, "Extracted PDF from IPP wrapper")
                    return File(outputFile.parent, "document_${System.currentTimeMillis()}.pdf")
                }
                
                val jpegStart = findPattern(allBytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                if (jpegStart >= 0) {
                    FileOutputStream(File(outputFile.parent, "document_${System.currentTimeMillis()}.jpg")).use { output ->
                        output.write(allBytes.sliceArray(jpegStart until allBytes.size))
                    }
                    Log.d(TAG, "Extracted JPEG from IPP wrapper")
                    return File(outputFile.parent, "document_${System.currentTimeMillis()}.jpg")
                }
                
                val pngHeader = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
                val pngStart = findPattern(allBytes, pngHeader)
                if (pngStart >= 0) {
                    FileOutputStream(File(outputFile.parent, "document_${System.currentTimeMillis()}.png")).use { output ->
                        output.write(allBytes.sliceArray(pngStart until allBytes.size))
                    }
                    Log.d(TAG, "Extracted PNG from IPP wrapper")
                    return File(outputFile.parent, "document_${System.currentTimeMillis()}.png")
                }
                
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting from IPP wrapper", e)
            null
        }
    }
    
    /**
     * Opens a document with the appropriate viewer app
     */
    fun openDocument(context: Context, file: File): Boolean {
        return try {
            val convertedFile = convertToViewableFile(context, file)
            if (convertedFile == null) {
                Log.e(TAG, "Failed to convert document to viewable format")
                android.widget.Toast.makeText(
                    context,
                    "Unable to convert document to viewable format",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return false
            }
            
            android.widget.Toast.makeText(
                context,
                "Document converted successfully!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            val docInfo = detectDocumentFormat(convertedFile)
            Log.d(TAG, "Opening converted document: ${convertedFile.name}, MIME: ${docInfo.mimeType}")
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    convertedFile
                )
            } else {
                Uri.fromFile(convertedFile)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, docInfo.mimeType)
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_GRANT_READ_URI_PERMISSION
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "Successfully opened document with ${docInfo.mimeType}")
                android.widget.Toast.makeText(
                    context,
                    "Opening ${docInfo.format} document...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return true
            } else {
                // Fallback with generic MIME type
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                
                try {
                    context.startActivity(fallbackIntent)
                    Log.d(TAG, "Opened document with fallback intent")
                    android.widget.Toast.makeText(
                        context,
                        "Opening document with system viewer...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening document with fallback intent", e)
                    android.widget.Toast.makeText(
                        context,
                        "No compatible app found to open this document type",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening document", e)
            android.widget.Toast.makeText(
                context,
                "Error opening document: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            false
        }
    }
    
    /**
     * Gets detailed information about a document
     */
    fun getDocumentDetails(file: File): Map<String, String> {
        val docInfo = detectDocumentFormat(file)
        return mapOf(
            "format" to docInfo.format,
            "mimeType" to docInfo.mimeType,
            "extension" to docInfo.extension,
            "isValid" to docInfo.isValid.toString(),
            "size" to "${docInfo.size / 1024} KB",
            "hasHeader" to docInfo.hasHeader.toString(),
            "lastModified" to FileUtils.formatTimestamp(file.lastModified())
        )
    }
}