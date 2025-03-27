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
        if (!printJobsDir.exists()) {
            printJobsDir.mkdirs()
            return emptyList()
        }
        
        return printJobsDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Opens a PDF file using an external PDF viewer app
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
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening PDF file", e)
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
        // Remove timestamp part and extension if possible
        return when {
            fileName.startsWith("print-job-") -> {
                val timestamp = fileName.substringAfter("print-job-").substringBefore(".pdf")
                try {
                    "Print Job (${formatTimestamp(timestamp.toLong())})"
                } catch (e: Exception) {
                    fileName
                }
            }
            else -> fileName
        }
    }
} 