package com.example.printer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
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
import com.example.printer.utils.PreferenceUtils
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

@Composable
fun MainNavigation(printerService: PrinterService) {
    var currentScreen by remember { mutableStateOf("main") }
    
    when (currentScreen) {
        "main" -> PrinterApp(
            printerService = printerService,
            onSettingsClick = { currentScreen = "settings" }
        )
        "settings" -> SettingsScreen(
            printerService = printerService,
            onBackClick = { currentScreen = "main" }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterApp(
    printerService: PrinterService,
    onSettingsClick: () -> Unit
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

    DisposableEffect(printerService) {
        isServiceRunning = true
        statusMessage = "Starting printer service..."
        
        printerService.startPrinterService(
            onSuccess = {
                statusMessage = "Printer service running\nPrinter name: ${printerService.getPrinterName()}"
                refreshSavedFiles()
            },
            onError = { error ->
                statusMessage = "Error: $error"
                isServiceRunning = false
            }
        )
        
        onDispose {
            if (isServiceRunning) {
                printerService.stopPrinterService()
            }
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
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
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
    
    // Choose icon based on file format
    val fileIcon = Icons.Default.Info
    
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
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
                    Row {
                        Text(
                            text = "$fileFormat • ${file.length() / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            
            Row {
                FilledTonalButton(
                    onClick = {
                        FileUtils.openPdfFile(context, file)
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("View")
                }
                
                FilledTonalIconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Job"
                    )
                }
            }
        }
    }
} 