package com.example.printer.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {
    private const val TAG = "FileUtils"
    
    /**
     * Gets all print jobs saved in the app's files directory
     */
    fun getSavedPrintJobs(context: Context): List<File> {
        val printJobsDir = File(context.filesDir, "print_jobs")
        Log.d(TAG, "Looking for print jobs in: ${printJobsDir.absolutePath}")
        
        if (!printJobsDir.exists()) {
            Log.d(TAG, "Print jobs directory doesn't exist, creating it")
            printJobsDir.mkdirs()
            return emptyList()
        }
        
        val files = printJobsDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        Log.d(TAG, "Found ${files.size} print jobs")
        files.forEach { file ->
            Log.d(TAG, "Print job: ${file.name}, size: ${file.length()} bytes, last modified: ${formatTimestamp(file.lastModified())}")
        }
        
        return files
    }
    
    /**
     * Opens a file using appropriate viewer app based on file extension
     */
    fun openPdfFile(context: Context, file: File) {
        try {
            // Check if file exists
            if (!file.exists() || file.length() <= 0) {
                Log.e(TAG, "File does not exist or is empty: ${file.absolutePath}")
                android.widget.Toast.makeText(
                    context,
                    "File does not exist or is empty",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            // Always try to detect if it's actually a PDF regardless of extension
            val isPdf = try {
                file.inputStream().use { stream ->
                    val bytes = ByteArray(4)
                    val bytesRead = stream.read(bytes, 0, bytes.size)
                    bytesRead >= 4 && 
                    bytes[0] == '%'.toByte() && 
                    bytes[1] == 'P'.toByte() && 
                    bytes[2] == 'D'.toByte() && 
                    bytes[3] == 'F'.toByte()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking PDF signature", e)
                false
            }
            
            Log.d(TAG, "File ${file.name} is PDF based on content signature: $isPdf")
            
            // Determine file type for display
            val fileType = if (isPdf) {
                "PDF"
            } else {
                detectFileType(file)
            }
            Log.d(TAG, "Detected file type: $fileType for ${file.name}")
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7.0+ (API level 24+), we need to use FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                // For older Android versions
                Uri.fromFile(file)
            }
            
            // Determine MIME type based on detected content
            val mimeType = when {
                isPdf -> "application/pdf"
                fileType == "JPEG" -> "image/jpeg"
                fileType == "PNG" -> "image/png"
                else -> "application/pdf" // Default to PDF
            }
            
            Log.d(TAG, "Opening file ${file.name} with MIME type: $mimeType")
            
            // Create an intent to view the file
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or 
                       Intent.FLAG_GRANT_READ_URI_PERMISSION
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            
            // Try to start an activity to view the file
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Fallback - try with generic MIME type
                Log.d(TAG, "No app to handle $mimeType, trying generic viewer")
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or 
                           Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                
                try {
                    context.startActivity(fallbackIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening file with fallback intent", e)
                    android.widget.Toast.makeText(
                        context,
                        "Unable to open file. No compatible app found.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file", e)
            
            // Show a toast to the user
            android.widget.Toast.makeText(
                context,
                "Unable to open file. Error: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * Detects the file type by reading its signature/magic number
     * @return String representing the file type (PDF, JPEG, PNG, or UNKNOWN)
     */
    private fun detectFileType(file: File): String {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(8)  // Most signatures are within the first 8 bytes
                val bytesRead = stream.read(header, 0, header.size)
                
                if (bytesRead >= 4) {
                    // Check for PDF signature (%PDF)
                    if (header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && 
                        header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) {
                        return "PDF"
                    }
                    
                    // Check for JPEG signature (JFIF, Exif)
                    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && 
                        header[2] == 0xFF.toByte()) {
                        return "JPEG"
                    }
                    
                    // Check for PNG signature
                    if (bytesRead >= 8 && 
                        header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && 
                        header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() && 
                        header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() && 
                        header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()) {
                        return "PNG"
                    }
                }
                
                // If no known signature is found, try to determine by extension
                when {
                    file.name.endsWith(".pdf", ignoreCase = true) -> "PDF"
                    file.name.endsWith(".jpg", ignoreCase = true) || 
                        file.name.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
                    file.name.endsWith(".png", ignoreCase = true) -> "PNG"
                    file.name.endsWith(".raw", ignoreCase = true) -> "RAW"
                    else -> "UNKNOWN"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting file type", e)
            "UNKNOWN"
        }
    }
    
    /**
     * Deletes a print job file
     * @return true if deletion was successful
     */
    fun deletePrintJob(file: File): Boolean {
        return try {
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Delete print job: ${file.name}, success=$deleted")
                deleted
            } else {
                Log.w(TAG, "File does not exist: ${file.name}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: ${file.name}", e)
            false
        }
    }
    
    /**
     * Deletes all print jobs
     * @return number of files successfully deleted
     */
    fun deleteAllPrintJobs(context: Context): Int {
        val files = getSavedPrintJobs(context)
        var deletedCount = 0
        
        files.forEach { file ->
            if (deletePrintJob(file)) {
                deletedCount++
            }
        }
        
        Log.d(TAG, "Deleted $deletedCount print jobs out of ${files.size}")
        return deletedCount
    }
    
    /**
     * Converts a timestamp to a readable date format
     */
    fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return formatter.format(date)
    }
    
    /**
     * Extracts a readable name from a file name
     */
    fun getReadableName(file: File): String {
        val fileName = file.name
        
        // Check if it matches our new naming pattern
        if (fileName.startsWith("print_job_")) {
            try {
                // Extract timestamp and extension
                val regex = "print_job_(\\d+)\\.(\\w+)".toRegex()
                val matchResult = regex.find(fileName)
                
                if (matchResult != null) {
                    val (timestamp, extension) = matchResult.destructured
                    val formattedTime = formatTimestamp(timestamp.toLong())
                    return "Print Job ($formattedTime).$extension"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing filename: $fileName", e)
            }
        }
        
        // Legacy naming pattern
        if (fileName.startsWith("print-job-")) {
            try {
                val timestamp = fileName.substringAfter("print-job-").substringBefore(".")
                return "Print Job (${formatTimestamp(timestamp.toLong())})"
            } catch (e: Exception) {
                // Fall back to filename
            }
        }
        
        // Legacy data files with format in the name
        if (fileName.contains("application_") && fileName.endsWith(".data")) {
            try {
                val timestamp = fileName.substringAfter("print_job_").substringBefore("_application")
                return "Print Job (${formatTimestamp(timestamp.toLong())})"
            } catch (e: Exception) {
                // Fall back to filename
            }
        }
        
        return fileName
    }
} 