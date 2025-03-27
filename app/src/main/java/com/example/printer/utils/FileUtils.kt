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
     * Opens a file using an appropriate viewer app based on file type
     */
    fun openPdfFile(context: Context, file: File) {
        try {
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
            
            // Determine MIME type based on file extension
            val mimeType = when {
                file.name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                file.name.endsWith(".jpg", ignoreCase = true) || 
                file.name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                file.name.endsWith(".png", ignoreCase = true) -> "image/png"
                file.name.endsWith(".txt", ignoreCase = true) -> "text/plain"
                file.name.endsWith(".ps", ignoreCase = true) -> "application/postscript"
                file.name.endsWith(".data", ignoreCase = true) -> {
                    // For legacy .data files, try to determine content based on file header
                    determineDataFileMimeType(file) ?: "application/octet-stream"
                }
                else -> "application/octet-stream"
            }
            
            Log.d(TAG, "Opening file ${file.name} with MIME type: $mimeType")
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file", e)
        }
    }
    
    /**
     * Attempts to determine MIME type of a .data file by checking its header
     */
    private fun determineDataFileMimeType(file: File): String? {
        return try {
            val bytes = ByteArray(8)
            file.inputStream().use { 
                it.read(bytes, 0, bytes.size)
            }
            
            // Check for PDF signature
            if (bytes.size >= 4 && 
                bytes[0] == '%'.toByte() && 
                bytes[1] == 'P'.toByte() && 
                bytes[2] == 'D'.toByte() && 
                bytes[3] == 'F'.toByte()) {
                "application/pdf"
            }
            // Check for JPEG signature
            else if (bytes.size >= 3 && 
                bytes[0] == 0xFF.toByte() && 
                bytes[1] == 0xD8.toByte() && 
                bytes[2] == 0xFF.toByte()) {
                "image/jpeg"
            }
            // Check for PNG signature
            else if (bytes.size >= 8 && 
                bytes[0] == 137.toByte() && 
                bytes[1] == 80.toByte() && 
                bytes[2] == 78.toByte() && 
                bytes[3] == 71.toByte() && 
                bytes[4] == 13.toByte() && 
                bytes[5] == 10.toByte() && 
                bytes[6] == 26.toByte() && 
                bytes[7] == 10.toByte()) {
                "image/png"
            }
            else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining file type", e)
            null
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