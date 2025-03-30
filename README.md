# Android Virtual Printer

An Android application that simulates an IPP (Internet Printing Protocol) printer, allowing you to capture print jobs from any device on your network and save them as PDF files for viewing, testing, and debugging.

**Current working of application , updated as of 31 st March 2024**




https://github.com/user-attachments/assets/fc9b4f11-7ad7-4ad4-8163-b26255c5e98c





## 🚀 Overview

Android Virtual Printer is a sophisticated solution for developers, testers, and anyone who needs to capture print jobs in a controlled environment. It advertises itself as a network printer via DNS-SD (Bonjour/mDNS), accepts print jobs through standard IPP protocols, and saves them as viewable documents on your Android device.

### Key Features

- **Network Printer Emulation**: Appears as a standard network printer to other devices
- **Cross-Platform Compatibility**: Works with Windows, macOS, Linux, iOS, and other Android devices
- **Print Job Storage**: Saves all received print jobs as viewable files
- **Format Detection**: Intelligently determines file formats from binary data
- **Customization**: Configure printer name and other settings
- **Modern UI**: Built with Jetpack Compose for a beautiful, responsive interface
-**Network Printer Connection**: Now you can connect with the on network Printers and attain its attributes
- **Improved File Management**: Enhanced file deletion options including batch operations

## 📋 Technical Details

### Architecture

The application follows a clean, modular architecture consisting of several key components:

#### Printer Service

The core of the application is the `PrinterService` class which:

1. **Network Discovery**: Registers the printer service via NSD (Network Service Discovery) making it discoverable by other devices
2. **IPP Server**: Runs an embedded Ktor server on a non-privileged port (8631) that implements the IPP protocol
3. **Print Job Handling**: Processes incoming IPP requests, extracts document data, and saves files
{
```kotlin
class PrinterService(private val context: Context) {
    // ... implementation details
    
    fun startPrinterService(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            startServer()
            registerService(onSuccess, onError)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start printer service", e)
            onError("Failed to start printer service: ${e.message}")
        }
    }
    
    // ... more methods
}
```
}

#### Document Processing

The application uses sophisticated techniques for handling various document formats:

1. **Binary Format Detection**: Examines file headers to determine actual content type
2. **PDF Extraction**: For documents containing embedded PDFs, extracts only the relevant portion
3. **PDF Wrapping**: For non-PDF documents, can create a valid PDF wrapper to ensure viewability
4. **Multi-format Support**: Handles PDF, JPEG, PNG, PostScript and raw data
5. **Auto-correction**: New feature to automatically fix corrupted data files
{
```kotlin
private fun saveDocument(docBytes: ByteArray, jobId: Long, documentFormat: String) {
    // Search for PDF header
    for (i in 0 until docBytes.size - 4) {
        if (docBytes[i] == '%'.toByte() && 
            docBytes[i + 1] == 'P'.toByte() && 
            docBytes[i + 2] == 'D'.toByte() && 
            docBytes[i + 3] == 'F'.toByte()) {
            isPdf = true
            pdfStartIndex = i
            // Extract and save PDF portion
            // ...
        }
    }
    
    // Handle non-PDF formats
    // ...
}
```
}

#### User Interface

The UI is built with Jetpack Compose featuring:

1. **Main Screen**: Displays printer status and received print jobs
2. **Settings Screen**: Allows customization of printer name and other options
3. **Print Job List**: Shows all received documents with type, size, and timestamp
4. **File Viewer Integration**: Opens files with appropriate system viewers
5. **Batch Operations**: New feature to delete all print jobs at once
{
```kotlin
@Composable
fun PrinterApp(
    printerService: PrinterService,
    onSettingsClick: () -> Unit
) {
    // UI implementation using Jetpack Compose
    // ...
    
    // New batch operations UI
    if (savedFiles.isNotEmpty()) {
        FilledTonalIconButton(
            onClick = { showDeleteAllDialog = true }
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete All"
            )
        }
    }
    
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Print Jobs") },
            text = { Text("Are you sure you want to delete all print jobs? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileUtils.deleteAllPrintJobs(context)
                        refreshSavedFiles()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
```
}

### IPP Protocol Implementation

The application implements key IPP operations including:

- **Get-Printer-Attributes**: Provides printer capabilities to clients
- **Print-Job**: Receives document data directly
- **Create-Job** and **Send-Document**: Supports multi-document jobs
- **Validate-Job**: Pre-flight validation
{
```kotlin
private fun processIppRequest(request: IppPacket, documentData: ByteArray, call: ApplicationCall): IppPacket {
    return when (request.code) {
        Operation.printJob.code -> { 
            // Handle Print-Job operation
        }
        Operation.createJob.code -> {
            // Handle Create-Job operation
        }
        Operation.sendDocument.code -> {
            // Handle Send-Document operation
        }
        // ... other operations
    }
}
```
}
### File Handling Utilities

The `FileUtils` class provides robust file management:

1. **Storage Management**: Organizes files in a dedicated directory
2. **Format Detection**: Uses file signatures to determine content type
3. **Viewer Integration**: Opens files with appropriate system applications
4. **Data Recovery**: New utility to fix corrupted .data files by detecting and renaming them
{
```kotlin
fun openPdfFile(context: Context, file: File) {
    try {
        // Check if file exists
        if (!file.exists() || file.length() <= 0) {
            Log.e(TAG, "File does not exist or is empty: ${file.absolutePath}")
            return
        }

        // Always try to detect if it's actually a PDF regardless of extension
        val isPdf = try {
            file.inputStream().use { stream ->
                val bytes = ByteArray(4)
                stream.read(bytes, 0, bytes.size)
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
        
        // Enhanced file type detection
        // ...
    } catch (e: Exception) {
        // Error handling
    }
}
```
}

#### New Data Recovery Utility

```kotlin
/**
 * Attempts to fix existing .data files by renaming them to proper extensions
 */
private fun fixDataFiles() {
    try {
        val printJobsDir = File(filesDir, "print_jobs")
        if (!printJobsDir.exists()) {
            printJobsDir.mkdirs()
            return
        }
        
        val dataFiles = printJobsDir.listFiles { file -> 
            file.name.endsWith(".data") 
        } ?: return
        
        android.util.Log.d("MainActivity", "Found ${dataFiles.size} .data files to fix")
        
        dataFiles.forEach { file ->
            try {
                // Check if it's a PDF by reading the first few bytes
                val isPdf = file.inputStream().use { stream ->
                    val bytes = ByteArray(4)
                    stream.read(bytes, 0, bytes.size)
                    bytes.size >= 4 && 
                    bytes[0] == '%'.toByte() && 
                    bytes[1] == 'P'.toByte() && 
                    bytes[2] == 'D'.toByte() && 
                    bytes[3] == 'F'.toByte()
                }
                
                // Create a new name replacing .data with .pdf
                val newName = if (isPdf) {
                    file.name.replace(".data", ".pdf")
                } else {
                    // Default to PDF if we can't determine the type
                    file.name.replace(".data", ".pdf")
                }
                
                val newFile = File(printJobsDir, newName)
                val success = file.renameTo(newFile)
                android.util.Log.d("MainActivity", "Renamed ${file.name} to ${newFile.name}: $success")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error renaming file ${file.name}", e)
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error fixing data files", e)
    }
}
```

## 🔍 Development Challenges & Solutions

### Challenge 1: Print Data Format Variations

**Problem**: Different operating systems and printer drivers send print jobs in various formats, sometimes with proprietary headers or metadata.

**Failed Approaches**:
- Initially attempted to save all files with extension matching declared MIME type
- Tried static mapping of MIME types to file extensions

**Solution**: Implemented a multi-layered detection system:
1. Scan binary data for known file signatures (PDF, JPEG, PNG, etc.)
2. Extract only the relevant portion when PDF signatures are found
3. Create synthetic PDF wrappers for printable data without signatures
4. Maintain original data as raw files when format cannot be determined

This approach ensures maximum compatibility across platforms.

### Challenge 2: IPP Protocol Complexity

**Problem**: The IPP protocol is complex with many operations, attributes, and variations across printer drivers.

**Failed Approaches**:
- Initially tried manual IPP packet parsing
- Attempted to support all IPP operations

**Solution**: Used the HP JIPP library for core IPP functionality and implemented a targeted subset of operations:
1. Focused on core operations needed for basic printing
2. Added special case handling for macOS and CUPS clients
3. Implemented detailed logging for troubleshooting
4. Created an extensible operation handling framework for future growth

### Challenge 3: Network Service Discoverability

**Problem**: Making the printer discoverable reliably across different networks and operating systems.

**Failed Approaches**:
- Using standard HTTP server for printer discovery
- Manual network broadcast messages

**Solution**: Leveraged Android's NSD (Network Service Discovery) system:
1. Registered service with proper DNS-SD service type (_ipp._tcp)
2. Added TXT records matching standard IPP printer attributes
3. Used a non-privileged port (8631) instead of the standard 631 to avoid permission issues
4. Implemented fallback mechanisms for older Android versions

### Challenge 4: UI Design for Print Management

**Problem**: Creating an intuitive interface for viewing and managing print jobs.

**Failed Approaches**:
- Initially used static layouts that didn't adapt well to different content
- Attempted to preview documents within the app itself

**Solution**: 
1. Implemented Jetpack Compose for a modern, responsive UI
2. Designed UI cards that adapt to different file types with appropriate icons
3. Integrated with system document viewers for maximum compatibility
4. Added format information and file details for better user experience

### Challenge 5: Handling Corrupted Data Files (New)

**Problem**: Some print jobs resulted in corrupted .data files that couldn't be properly viewed or processed.

**Failed Approaches**:
- Attempting to parse all file formats without checking content signatures
- Relying solely on file extensions for format detection

**Solution**: 
1. Implemented automatic data file recovery on application startup
2. Added binary signature detection to identify actual file formats regardless of extension
3. Created a smart renaming system to properly convert .data files to appropriate formats
4. Added enhanced error handling for corrupted files

## 🛠 Technologies Used

- **Kotlin**: Primary development language
- **Jetpack Compose**: Modern UI toolkit
- **Ktor**: Embedded web server for IPP implementation
- **JIPP Library**: Java IPP protocol implementation
- **Coroutines**: Asynchronous programming
- **Android Network Service Discovery (NSD)**: For printer advertisement
- **Android FileProvider**: For sharing files with other applications
- **Android BroadcastReceiver**: For real-time print job notifications (New)

## 📚 Lessons Learned

1. **Binary Protocol Handling**: Working with IPP deepened understanding of binary protocols and data parsing

2. **Cross-Platform Compatibility**: Engineering for compatibility across operating systems requires careful testing and fallback mechanisms

3. **Format Detection**: File format detection based on signatures is more reliable than relying on declared MIME types

4. **Network Service Architecture**: Discovery protocols like DNS-SD require precise implementation details to work reliably

5. **Android System Integration**: Integrating with Android's document system and content providers requires careful permission handling

6. **Asynchronous Notification**: Using BroadcastReceiver for real-time updates improves user experience (New)

7. **Data Recovery**: Implementing file recovery mechanisms increases application robustness (New)

## 🚀 Recent Updates

### March/April 2024 Update

1. **Batch File Management**: Added the ability to delete all print jobs at once
2. **Real-time Notifications**: Implemented BroadcastReceiver to provide instant UI updates when new print jobs arrive
3. **Data Recovery**: Added automatic detection and repair of corrupted .data files
4. **UI Refinements**: Improved status display and error handling in the interface
5. **Enhanced File Type Detection**: More robust detection of file types regardless of extensions

## 🔮 Future Enhancements

- **Print Queue Management**: Add the ability to hold, release, or cancel print jobs
- **Advanced IPP Features**: Implement more sophisticated IPP operations like job management
- **Print Simulation**: Add the option to "print" to a real printer from saved documents
- **Enhanced Document Viewing**: Built-in viewer for common document formats
- **Statistics and Logging**: Extended logging and analytics for print jobs
- **Cloud Integration**: Option to save print jobs to cloud storage

## 🧪 Testing

The application has been tested with various clients:

- **Windows 10/11**: Using both native printer dialogs and applications
- **macOS**: Using CUPS and various applications
- **iOS**: Using AirPrint from multiple applications
- **Linux**: Using CUPS and native applications
- **Android**: Using various printing frameworks

## 📱 Requirements

- Android 10.0 or higher
- Network connection (Wi-Fi)
- Storage permission for saving print jobs

*This is still under development*
