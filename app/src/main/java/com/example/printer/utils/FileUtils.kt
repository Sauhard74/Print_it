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
                file.name.endsWith(".raw", ignoreCase = true) -> {
                    // For raw files, try to open with general document viewer
                    "application/octet-stream"
                }
                else -> "application/octet-stream"
            }
            
            Log.d(TAG, "Opening file ${file.name} with MIME type: $mimeType")
            
            try {
                // Try to open with the specific MIME type first
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or 
                           Intent.FLAG_GRANT_READ_URI_PERMISSION
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // If that fails, try a generic viewer
                Log.e(TAG, "Error opening with specific mime type, trying generic opener", e)
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or 
                           Intent.FLAG_GRANT_READ_URI_PERMISSION
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                context.startActivity(genericIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file", e)
            
            // Show a toast to the user
            android.widget.Toast.makeText(
                context,
                "Unable to open file. No compatible app found for this file type.",
                android.widget.Toast.LENGTH_LONG
            ).show()
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