package com.example.printer.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Utility class for opening and viewing documents
 * Addresses reviewer feedback about missing document opening functionality
 */
object DocumentViewerUtils {
    private const val TAG = "DocumentViewerUtils"
    
    /**
     * Open a document file with the appropriate system app
     * @param context Android context
     * @param file The document file to open
     * @param documentType The type of document (for MIME type)
     * @return true if successfully opened, false otherwise
     */
    fun openDocument(context: Context, file: File, documentType: DocumentType): Boolean {
        try {
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: ${file.absolutePath}")
                return false
            }
            
            // Create content URI using FileProvider for security
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating content URI", e)
                // Fallback to file URI (less secure but works)
                Uri.fromFile(file)
            }
            
            // Create intent to open the document
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, documentType.mimeType)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            // Check if there's an app that can handle this intent
            val packageManager = context.packageManager
            if (intent.resolveActivity(packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "Successfully opened document: ${file.name}")
                return true
            } else {
                Log.w(TAG, "No app found to open document type: ${documentType.mimeType}")
                // Try with generic intent
                return openWithGenericViewer(context, uri)
            }
            
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No app found to open document", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening document", e)
            return false
        }
    }
    
    /**
     * Try to open document with a generic viewer
     */
    private fun openWithGenericViewer(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened with generic viewer")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open with generic viewer", e)
            false
        }
    }
    
    /**
     * Share a document file
     * @param context Android context
     * @param file The document file to share
     * @param documentType The type of document
     * @return true if successfully shared, false otherwise
     */
    fun shareDocument(context: Context, file: File, documentType: DocumentType): Boolean {
        try {
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: ${file.absolutePath}")
                return false
            }
            
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating content URI for sharing", e)
                return false
            }
            
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = documentType.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Print Job: ${file.nameWithoutExtension}")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            val chooserIntent = Intent.createChooser(shareIntent, "Share Document")
            chooserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            context.startActivity(chooserIntent)
            Log.d(TAG, "Successfully shared document: ${file.name}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing document", e)
            return false
        }
    }
    
    /**
     * Check if a document can be opened on this device
     * @param context Android context
     * @param documentType The type of document
     * @return true if can be opened, false otherwise
     */
    fun canOpenDocument(context: Context, documentType: DocumentType): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = documentType.mimeType
        }
        
        val packageManager = context.packageManager
        return intent.resolveActivity(packageManager) != null
    }
    
    /**
     * Get available apps that can open this document type
     * @param context Android context
     * @param documentType The type of document
     * @return List of app names that can open this document type
     */
    fun getAvailableApps(context: Context, documentType: DocumentType): List<String> {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = documentType.mimeType
        }
        
        val packageManager = context.packageManager
        val activities = packageManager.queryIntentActivities(intent, 0)
        
        return activities.map { resolveInfo ->
            resolveInfo.loadLabel(packageManager).toString()
        }
    }
    
    /**
     * Delete a document file safely
     * @param file The file to delete
     * @return true if successfully deleted, false otherwise
     */
    fun deleteDocument(file: File): Boolean {
        return try {
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    Log.d(TAG, "Successfully deleted document: ${file.name}")
                } else {
                    Log.w(TAG, "Failed to delete document: ${file.name}")
                }
                deleted
            } else {
                Log.w(TAG, "Document does not exist: ${file.name}")
                true // Consider non-existent file as "deleted"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting document: ${file.name}", e)
            false
        }
    }
}
