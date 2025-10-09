package com.example.printer.utils

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.Inflater

/**
 * Utility for detecting and decompressing various compression formats
 */
object CompressionUtils {
    private const val TAG = "CompressionUtils"
    
    /**
     * Compression type detected
     */
    enum class CompressionType {
        NONE,
        GZIP,
        DEFLATE,
        ZLIB,
        UNKNOWN
    }
    
    /**
     * Result of compression detection and decompression
     */
    data class DecompressionResult(
        val compressionType: CompressionType,
        val decompressedData: ByteArray?,
        val originalSize: Int,
        val decompressedSize: Int,
        val success: Boolean,
        val errorMessage: String? = null
    )
    
    /**
     * Detect compression type from byte signature
     */
    fun detectCompressionType(bytes: ByteArray): CompressionType {
        if (bytes.isEmpty()) return CompressionType.NONE
        
        return when {
            // GZIP signature: 1F 8B
            bytes.size >= 2 && 
            bytes[0] == 0x1F.toByte() && 
            bytes[1] == 0x8B.toByte() -> {
                Log.d(TAG, "Detected GZIP compression")
                CompressionType.GZIP
            }
            
            // ZLIB signature: 78 9C (default compression)
            // or 78 01 (no compression)
            // or 78 DA (best compression)
            bytes.size >= 2 && 
            bytes[0] == 0x78.toByte() && 
            (bytes[1] == 0x9C.toByte() || bytes[1] == 0x01.toByte() || bytes[1] == 0xDA.toByte()) -> {
                Log.d(TAG, "Detected ZLIB compression")
                CompressionType.ZLIB
            }
            
            // Try to detect raw DEFLATE by attempting decompression
            // DEFLATE has no magic number, so we try heuristics
            else -> {
                // Check if data looks compressed (low printable ratio, high entropy)
                val printableCount = bytes.take(256).count { byte ->
                    val char = byte.toInt()
                    char in 32..126 || char in arrayOf(9, 10, 13)
                }
                val printableRatio = printableCount.toFloat() / minOf(256, bytes.size)
                
                if (printableRatio < 0.5) {
                    // Might be compressed, but we're not sure
                    Log.d(TAG, "Data might be DEFLATE or other compression (printable ratio: $printableRatio)")
                    CompressionType.UNKNOWN
                } else {
                    Log.d(TAG, "No compression detected (printable ratio: $printableRatio)")
                    CompressionType.NONE
                }
            }
        }
    }
    
    /**
     * Attempt to decompress data
     */
    fun decompress(bytes: ByteArray): DecompressionResult {
        if (bytes.isEmpty()) {
            return DecompressionResult(
                compressionType = CompressionType.NONE,
                decompressedData = null,
                originalSize = 0,
                decompressedSize = 0,
                success = false,
                errorMessage = "Empty input"
            )
        }
        
        val compressionType = detectCompressionType(bytes)
        
        // If no compression detected, return original data
        if (compressionType == CompressionType.NONE) {
            Log.d(TAG, "No compression detected, returning original data")
            return DecompressionResult(
                compressionType = CompressionType.NONE,
                decompressedData = bytes,
                originalSize = bytes.size,
                decompressedSize = bytes.size,
                success = true
            )
        }
        
        // Try decompression based on detected type
        return when (compressionType) {
            CompressionType.GZIP -> decompressGzip(bytes)
            CompressionType.ZLIB -> decompressZlib(bytes)
            CompressionType.UNKNOWN -> tryAllDecompressionMethods(bytes)
            else -> DecompressionResult(
                compressionType = compressionType,
                decompressedData = bytes,
                originalSize = bytes.size,
                decompressedSize = bytes.size,
                success = true
            )
        }
    }
    
    /**
     * Decompress GZIP data
     */
    private fun decompressGzip(bytes: ByteArray): DecompressionResult {
        return try {
            val inputStream = ByteArrayInputStream(bytes)
            val gzipStream = GZIPInputStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (gzipStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            gzipStream.close()
            val decompressed = outputStream.toByteArray()
            
            Log.d(TAG, "GZIP decompression successful: ${bytes.size} bytes → ${decompressed.size} bytes")
            
            DecompressionResult(
                compressionType = CompressionType.GZIP,
                decompressedData = decompressed,
                originalSize = bytes.size,
                decompressedSize = decompressed.size,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "GZIP decompression failed", e)
            DecompressionResult(
                compressionType = CompressionType.GZIP,
                decompressedData = null,
                originalSize = bytes.size,
                decompressedSize = 0,
                success = false,
                errorMessage = "GZIP decompression failed: ${e.message}"
            )
        }
    }
    
    /**
     * Decompress ZLIB/Deflate data
     */
    private fun decompressZlib(bytes: ByteArray): DecompressionResult {
        return try {
            val inputStream = ByteArrayInputStream(bytes)
            val inflaterStream = InflaterInputStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inflaterStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            inflaterStream.close()
            val decompressed = outputStream.toByteArray()
            
            Log.d(TAG, "ZLIB decompression successful: ${bytes.size} bytes → ${decompressed.size} bytes")
            
            DecompressionResult(
                compressionType = CompressionType.ZLIB,
                decompressedData = decompressed,
                originalSize = bytes.size,
                decompressedSize = decompressed.size,
                success = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "ZLIB decompression failed, trying raw DEFLATE", e)
            // Try raw DEFLATE (no wrapper)
            decompressRawDeflate(bytes)
        }
    }
    
    /**
     * Decompress raw DEFLATE data (no ZLIB wrapper)
     */
    private fun decompressRawDeflate(bytes: ByteArray): DecompressionResult {
        return try {
            val inflater = Inflater(true) // true = no ZLIB wrapper (raw DEFLATE)
            val outputStream = ByteArrayOutputStream()
            
            inflater.setInput(bytes)
            val buffer = ByteArray(8192)
            
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                outputStream.write(buffer, 0, count)
            }
            
            inflater.end()
            val decompressed = outputStream.toByteArray()
            
            if (decompressed.isNotEmpty()) {
                Log.d(TAG, "Raw DEFLATE decompression successful: ${bytes.size} bytes → ${decompressed.size} bytes")
                DecompressionResult(
                    compressionType = CompressionType.DEFLATE,
                    decompressedData = decompressed,
                    originalSize = bytes.size,
                    decompressedSize = decompressed.size,
                    success = true
                )
            } else {
                Log.w(TAG, "Raw DEFLATE decompression returned empty data")
                DecompressionResult(
                    compressionType = CompressionType.DEFLATE,
                    decompressedData = null,
                    originalSize = bytes.size,
                    decompressedSize = 0,
                    success = false,
                    errorMessage = "Raw DEFLATE decompression returned empty data"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Raw DEFLATE decompression failed", e)
            DecompressionResult(
                compressionType = CompressionType.DEFLATE,
                decompressedData = null,
                originalSize = bytes.size,
                decompressedSize = 0,
                success = false,
                errorMessage = "Raw DEFLATE decompression failed: ${e.message}"
            )
        }
    }
    
    /**
     * Try all decompression methods when type is unknown
     */
    private fun tryAllDecompressionMethods(bytes: ByteArray): DecompressionResult {
        Log.d(TAG, "Trying all decompression methods...")
        
        // Try GZIP first
        val gzipResult = decompressGzip(bytes)
        if (gzipResult.success && gzipResult.decompressedData != null) {
            Log.d(TAG, "Successfully decompressed as GZIP")
            return gzipResult
        }
        
        // Try ZLIB/Deflate
        val zlibResult = decompressZlib(bytes)
        if (zlibResult.success && zlibResult.decompressedData != null) {
            Log.d(TAG, "Successfully decompressed as ZLIB/DEFLATE")
            return zlibResult
        }
        
        // Try raw DEFLATE
        val deflateResult = decompressRawDeflate(bytes)
        if (deflateResult.success && deflateResult.decompressedData != null) {
            Log.d(TAG, "Successfully decompressed as raw DEFLATE")
            return deflateResult
        }
        
        // All methods failed, return original data
        Log.w(TAG, "All decompression methods failed, returning original data")
        return DecompressionResult(
            compressionType = CompressionType.UNKNOWN,
            decompressedData = bytes,
            originalSize = bytes.size,
            decompressedSize = bytes.size,
            success = false,
            errorMessage = "All decompression methods failed"
        )
    }
    
    /**
     * Get human-readable compression info
     */
    fun getCompressionInfo(bytes: ByteArray): String {
        val compressionType = detectCompressionType(bytes)
        return when (compressionType) {
            CompressionType.NONE -> "No compression"
            CompressionType.GZIP -> "GZIP compressed"
            CompressionType.ZLIB -> "ZLIB compressed"
            CompressionType.DEFLATE -> "DEFLATE compressed"
            CompressionType.UNKNOWN -> "Unknown compression or encrypted"
        }
    }
}

