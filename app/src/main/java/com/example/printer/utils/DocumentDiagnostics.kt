package com.example.printer.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Diagnostic tools for analyzing raw print job files and understanding conversion issues
 */
object DocumentDiagnostics {
    private const val TAG = "DocumentDiagnostics"
    
    /**
     * Comprehensive analysis of a print job file
     */
    fun analyzeDocument(file: File): DocumentAnalysis {
        val analysis = DocumentAnalysis(
            fileName = file.name,
            fileSize = file.length(),
            exists = file.exists()
        )
        
        if (!file.exists()) {
            return analysis.copy(error = "File does not exist")
        }
        
        try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(minOf(2048, file.length().toInt())) // Read first 2KB
                val bytesRead = stream.read(buffer)
                
                if (bytesRead > 0) {
                    val header = buffer.sliceArray(0 until bytesRead)
                    
                    // Basic file analysis
                    analysis.apply {
                        headerBytes = header.take(32).toByteArray()
                        headerHex = header.take(32).joinToString(" ") { "%02X".format(it) }
                        headerAscii = header.take(64).map { b ->
                            if (b >= 32 && b <= 126) b.toInt().toChar() else '.'
                        }.joinToString("")
                        
                        // Look for various document signatures
                        signatures = findDocumentSignatures(header)
                        
                        // Count different byte types
                        val printableBytes = header.count { b -> b >= 32 && b <= 126 }
                        val controlBytes = header.count { b -> b < 32 }
                        val highBytes = header.count { b -> b < 0 }
                        
                        byteDistribution = mapOf(
                            "printable" to printableBytes,
                            "control" to controlBytes,
                            "high" to highBytes,
                            "total" to bytesRead
                        )
                        
                        // Try to identify the content
                        contentType = identifyContentType(header)
                        
                        // Look for IPP wrapper
                        ippAnalysis = analyzeIPPWrapper(header)
                        
                        // Check if it's a valid document
                        isValidDocument = isValidDocumentFormat(header)
                        
                        // Extract any embedded documents
                        embeddedDocuments = findEmbeddedDocuments(file)
                    }
                }
                
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing document", e)
            return analysis.copy(error = "Analysis error: ${e.message}")
        }
        
        return analysis
    }
    
    /**
     * Finds all known document format signatures in the data
     */
    private fun findDocumentSignatures(data: ByteArray): List<DocumentSignature> {
        val signatures = mutableListOf<DocumentSignature>()
        
        // Define signatures to look for
        val knownSignatures = mapOf(
            "PDF" to byteArrayOf(0x25, 0x50, 0x44, 0x46), // %PDF
            "JPEG" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            "PNG" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            "GIF87a" to "GIF87a".toByteArray(),
            "GIF89a" to "GIF89a".toByteArray(),
            "TIFF_LE" to byteArrayOf(0x49, 0x49, 0x2A, 0x00),
            "TIFF_BE" to byteArrayOf(0x4D, 0x4D, 0x00, 0x2A),
            "PostScript" to byteArrayOf(0x25, 0x21, 0x50, 0x53), // %!PS
            "ZIP" to byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            "IPP" to "POST".toByteArray() // Common in IPP headers
        )
        
        for ((format, signature) in knownSignatures) {
            val positions = findAllPatternPositions(data, signature)
            positions.forEach { pos ->
                signatures.add(DocumentSignature(format, pos, signature.size))
            }
        }
        
        return signatures
    }
    
    /**
     * Finds all positions where a pattern occurs in the data
     */
    private fun findAllPatternPositions(data: ByteArray, pattern: ByteArray): List<Int> {
        val positions = mutableListOf<Int>()
        
        for (i in 0..(data.size - pattern.size)) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                positions.add(i)
            }
        }
        
        return positions
    }
    
    /**
     * Identifies the likely content type based on header analysis
     */
    private fun identifyContentType(header: ByteArray): String {
        if (header.size < 4) return "UNKNOWN"
        
        // Check for PDF
        if (header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && 
            header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) {
            return "PDF"
        }
        
        // Check for JPEG
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "JPEG"
        }
        
        // Check for PNG
        if (header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && 
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
            return "PNG"
        }
        
        // Check for text-like content
        val printableCount = header.count { b -> b >= 32 && b <= 126 }
        if (printableCount.toDouble() / header.size > 0.8) {
            return "TEXT"
        }
        
        // Check for IPP data
        val headerStr = String(header.take(64).toByteArray())
        if (headerStr.contains("POST") || headerStr.contains("HTTP") || headerStr.contains("IPP")) {
            return "IPP_WRAPPED"
        }
        
        return "BINARY"
    }
    
    /**
     * Analyzes IPP wrapper structure
     */
    private fun analyzeIPPWrapper(header: ByteArray): IPPAnalysis {
        val headerStr = String(header)
        
        return IPPAnalysis(
            hasIPPHeaders = headerStr.contains("POST") || headerStr.contains("HTTP"),
            hasContentType = headerStr.contains("Content-Type:", ignoreCase = true),
            hasContentLength = headerStr.contains("Content-Length:", ignoreCase = true),
            possibleDocumentStart = findPossibleDocumentStart(header)
        )
    }
    
    /**
     * Finds where the actual document might start within IPP data
     */
    private fun findPossibleDocumentStart(data: ByteArray): Int {
        // Look for double CRLF which usually separates headers from content
        val doubleCRLF = "\r\n\r\n".toByteArray()
        val position = findPatternPosition(data, doubleCRLF)
        if (position >= 0) {
            return position + doubleCRLF.size
        }
        
        // Look for double LF
        val doubleLF = "\n\n".toByteArray()
        val position2 = findPatternPosition(data, doubleLF)
        if (position2 >= 0) {
            return position2 + doubleLF.size
        }
        
        return -1
    }
    
    private fun findPatternPosition(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..(data.size - pattern.size)) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
    
    /**
     * Checks if the header indicates a valid document format
     */
    private fun isValidDocumentFormat(header: ByteArray): Boolean {
        val signatures = findDocumentSignatures(header)
        return signatures.any { it.format in listOf("PDF", "JPEG", "PNG", "GIF87a", "GIF89a", "TIFF_LE", "TIFF_BE") }
    }
    
    /**
     * Attempts to find and extract embedded documents
     */
    private fun findEmbeddedDocuments(file: File): List<EmbeddedDocument> {
        val embedded = mutableListOf<EmbeddedDocument>()
        
        try {
            val allBytes = file.readBytes()
            
            // Look for PDF documents
            val pdfSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
            val pdfPositions = findAllPatternPositions(allBytes, pdfSignature)
            pdfPositions.forEach { pos ->
                embedded.add(EmbeddedDocument("PDF", pos, estimateDocumentSize(allBytes, pos)))
            }
            
            // Look for JPEG documents
            val jpegSignature = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val jpegPositions = findAllPatternPositions(allBytes, jpegSignature)
            jpegPositions.forEach { pos ->
                embedded.add(EmbeddedDocument("JPEG", pos, estimateDocumentSize(allBytes, pos)))
            }
            
            // Look for PNG documents
            val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val pngPositions = findAllPatternPositions(allBytes, pngSignature)
            pngPositions.forEach { pos ->
                embedded.add(EmbeddedDocument("PNG", pos, estimateDocumentSize(allBytes, pos)))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error finding embedded documents", e)
        }
        
        return embedded
    }
    
    /**
     * Estimates the size of an embedded document
     */
    private fun estimateDocumentSize(data: ByteArray, startPos: Int): Int {
        // For now, just return remaining bytes
        // In a real implementation, we'd look for end markers
        return data.size - startPos
    }
    
    /**
     * Creates a detailed report for debugging
     */
    fun createDiagnosticReport(context: Context): String {
        val report = StringBuilder()
        report.appendLine("=== Document Diagnostics Report ===")
        report.appendLine("Generated: ${java.util.Date()}")
        report.appendLine()
        
        val printJobs = FileUtils.getSavedPrintJobs(context)
        report.appendLine("Found ${printJobs.size} print job files:")
        report.appendLine()
        
        printJobs.forEach { file ->
            val analysis = analyzeDocument(file)
            report.appendLine("--- ${file.name} ---")
            report.appendLine("Size: ${analysis.fileSize} bytes")
            report.appendLine("Content Type: ${analysis.contentType}")
            report.appendLine("Valid Document: ${analysis.isValidDocument}")
            report.appendLine("Header (Hex): ${analysis.headerHex}")
            report.appendLine("Header (ASCII): ${analysis.headerAscii}")
            
            if (analysis.signatures.isNotEmpty()) {
                report.appendLine("Found Signatures:")
                analysis.signatures.forEach { sig ->
                    report.appendLine("  - ${sig.format} at position ${sig.position}")
                }
            }
            
            if (analysis.embeddedDocuments.isNotEmpty()) {
                report.appendLine("Embedded Documents:")
                analysis.embeddedDocuments.forEach { doc ->
                    report.appendLine("  - ${doc.format} at position ${doc.position} (${doc.estimatedSize} bytes)")
                }
            }
            
            if (analysis.ippAnalysis.hasIPPHeaders) {
                report.appendLine("IPP Analysis:")
                report.appendLine("  - Has IPP Headers: ${analysis.ippAnalysis.hasIPPHeaders}")
                report.appendLine("  - Document Start: ${analysis.ippAnalysis.possibleDocumentStart}")
            }
            
            report.appendLine()
        }
        
        return report.toString()
    }
}

data class DocumentAnalysis(
    val fileName: String = "",
    val fileSize: Long = 0,
    val exists: Boolean = false,
    var headerBytes: ByteArray = byteArrayOf(),
    var headerHex: String = "",
    var headerAscii: String = "",
    var signatures: List<DocumentSignature> = emptyList(),
    var byteDistribution: Map<String, Int> = emptyMap(),
    var contentType: String = "UNKNOWN",
    var ippAnalysis: IPPAnalysis = IPPAnalysis(),
    var isValidDocument: Boolean = false,
    var embeddedDocuments: List<EmbeddedDocument> = emptyList(),
    var error: String? = null
)

data class DocumentSignature(
    val format: String,
    val position: Int,
    val size: Int
)

data class IPPAnalysis(
    val hasIPPHeaders: Boolean = false,
    val hasContentType: Boolean = false,
    val hasContentLength: Boolean = false,
    val possibleDocumentStart: Int = -1
)

data class EmbeddedDocument(
    val format: String,
    val position: Int,
    val estimatedSize: Int
)