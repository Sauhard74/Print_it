package com.example.printer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.printer.printer.PrinterService
import com.example.printer.settings.SettingsScreen
import com.example.printer.ui.theme.PrinterTheme
import com.example.printer.utils.FileUtils
import com.example.printer.utils.DocumentConverter
import com.example.printer.utils.DocumentDiagnostics
import com.example.printer.utils.PreferenceUtils
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var printerService: PrinterService
    private val printJobReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            android.util.Log.d("MainActivity", "Received broadcast: ${intent.action}")
            if (intent.action == "com.example.printer.NEW_PRINT_JOB") {
                val jobPath = intent.getStringExtra("job_path") ?: "unknown"
                val jobSize = intent.getIntExtra("job_size", 0)
                android.util.Log.d("MainActivity", "Print job received: $jobPath, size: $jobSize bytes")
                
                // Immediately refresh
                refreshTriggerState?.value = (refreshTriggerState?.value ?: 0) + 1
                android.util.Log.d("MainActivity", "Triggered UI refresh: ${refreshTriggerState?.value}")
                
                // Also queue additional refreshes with delay
                (0..5).forEach { i ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        refreshTriggerState?.value = (refreshTriggerState?.value ?: 0) + 1
                        android.util.Log.d("MainActivity", "Delayed refresh #$i: ${refreshTriggerState?.value}")
                    }, (i+1) * 1000L)  // 1-6 seconds delay
                }
            }
        }
    }
    
    // Companion object to hold a static reference to the state
    companion object {
        var refreshTriggerState: androidx.compose.runtime.MutableState<Int>? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the printer service
        printerService = PrinterService(this)
        
        // Start the printer service (tied to Activity lifecycle, not composable)
        printerService.startPrinterService(
            onSuccess = {
                Log.d("MainActivity", "Printer service started successfully")
            },
            onError = { error ->
                Log.e("MainActivity", "Failed to start printer service: $error")
            }
        )
        
        // Register the broadcast receiver
        registerReceiver(
            printJobReceiver, 
            android.content.IntentFilter("com.example.printer.NEW_PRINT_JOB"),
            android.content.Context.RECEIVER_NOT_EXPORTED
        )
        
        // Try to fix existing data files by renaming them to PDF
        fixDataFiles()
        
        setContent {
            PrinterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(printerService)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        printerService.stopPrinterService()
        try {
            unregisterReceiver(printJobReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(printerService: PrinterService) {
    var currentScreen by remember { mutableStateOf("main") }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Printer") },
                    selected = currentScreen == "main",
                    onClick = { currentScreen = "main" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Jobs") },
                    selected = currentScreen == "jobs",
                    onClick = { currentScreen = "jobs" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("Logs") },
                    selected = currentScreen == "logs",
                    onClick = { currentScreen = "logs" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Plugins") },
                    selected = currentScreen == "plugins",
                    onClick = { currentScreen = "plugins" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = currentScreen == "settings",
                    onClick = { currentScreen = "settings" }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                "main" -> PrinterApp(printerService = printerService)
                "settings" -> SettingsScreen(
                    printerService = printerService,
                    onBackClick = { currentScreen = "main" }
                )
                "jobs" -> com.example.printer.ui.JobManagementScreen(
                    onBackClick = { currentScreen = "main" }
                )
                "logs" -> com.example.printer.ui.LogsScreen(
                    onBackClick = { currentScreen = "main" }
                )
                "plugins" -> com.example.printer.ui.PluginManagementScreen(
                    onBackClick = { currentScreen = "main" }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterApp(
    printerService: PrinterService
) {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Printer service not running") }
    var savedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    
    // Store reference to refresh trigger state in companion object for broadcast access
    MainActivity.refreshTriggerState = remember { mutableStateOf(0) }
    
    // Function to refresh the list of saved files
    val refreshSavedFiles = {
        savedFiles = FileUtils.getSavedPrintJobs(context)
    }
    
    // Periodically refresh the list of saved files and also when refreshTrigger changes
    LaunchedEffect(refreshTrigger, MainActivity.refreshTriggerState?.value) {
        while (true) {
            refreshSavedFiles()
            delay(1000) // 1 second refresh interval
        }
    }
    
    // Update UI based on actual service status (service lifecycle managed by MainActivity)
    LaunchedEffect(Unit) {
        while (true) {
            val status = printerService.getServiceStatus()
            isServiceRunning = status == PrinterService.ServiceStatus.RUNNING || 
                               status == PrinterService.ServiceStatus.ERROR_SIMULATION
            statusMessage = when (status) {
                PrinterService.ServiceStatus.RUNNING -> 
                    "Printer service running\nPrinter name: ${printerService.getPrinterName()}"
                PrinterService.ServiceStatus.STARTING -> 
                    "Starting printer service..."
                PrinterService.ServiceStatus.ERROR_SIMULATION -> 
                    "Printer service running (Error Mode)\nPrinter name: ${printerService.getPrinterName()}"
                PrinterService.ServiceStatus.STOPPED -> 
                    "Printer service not running"
            }
            delay(500) // Poll status every 0.5 seconds
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = PreferenceUtils.getCustomPrinterName(context),
                        style = MaterialTheme.typography.titleLarge
                    ) 
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Status card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Printer Status",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    
                    if (isServiceRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Waiting for print jobs...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            
            // Print jobs header with delete all button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Received Print Jobs",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
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
            }
            
            if (savedFiles.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(bottom = 16.dp)
                            )
                            Text(
                                text = "No print jobs received yet.\nFiles will appear here when someone prints to this printer.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedFiles) { file ->
                        PrintJobItem(
                            file = file,
                            onDeleteClick = {
                                FileUtils.deletePrintJob(file)
                                refreshSavedFiles()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrintJobItem(
    file: File,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    
    // Determine file format for display and icon
    val fileFormat = when {
        file.name.endsWith(".pdf", ignoreCase = true) -> "PDF"
        file.name.endsWith(".jpg", ignoreCase = true) || 
            file.name.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
        file.name.endsWith(".png", ignoreCase = true) -> "PNG"
        file.name.endsWith(".raw", ignoreCase = true) -> "RAW"
        else -> "DATA"
    }
    
    val fileIcon = Icons.Default.Info
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // File info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = fileIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 12.dp)
                )
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = FileUtils.getReadableName(file),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$fileFormat • ${file.length() / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        // First analyze the document for debugging
                        val analysis = DocumentDiagnostics.analyzeDocument(file)
                        Log.d("DocumentDebug", "File: ${file.name}")
                        Log.d("DocumentDebug", "Content Type: ${analysis.contentType}")
                        Log.d("DocumentDebug", "Valid Document: ${analysis.isValidDocument}")
                        Log.d("DocumentDebug", "Signatures: ${analysis.signatures}")
                        Log.d("DocumentDebug", "Embedded Docs: ${analysis.embeddedDocuments}")
                        Log.d("DocumentDebug", "Header (first 32 bytes): ${analysis.headerHex}")
                        
                        // Try advanced document converter
                        val success = DocumentConverter.openDocument(context, file)
                        if (!success) {
                            // Fallback to original method
                            FileUtils.openPdfFile(context, file)
                        }
                    },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("View")
                }
                
                Button(
                    onClick = onDeleteClick,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Delete")
                }
            }
        }
    }
} 