package com.example.printer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import com.example.printer.settings.SettingsScreen
import com.example.printer.ui.theme.PrinterTheme
import com.example.printer.utils.FileUtils
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var printerService: PrinterService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the printer service
        printerService = PrinterService(this)
        
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
    
    // Function to refresh the list of saved files
    val refreshSavedFiles = {
        savedFiles = FileUtils.getSavedPrintJobs(context)
    }
    
    // Periodically refresh the list of saved files every 3 seconds
    LaunchedEffect(refreshTrigger) {
        while (true) {
            refreshSavedFiles()
            delay(3000) // 3 seconds
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
            TopAppBar(
                title = { Text("Android Virtual Printer") },
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
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
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
                Text(
                    text = "Received Print Jobs",
                    style = MaterialTheme.typography.titleMedium
                )
                
                if (savedFiles.isNotEmpty()) {
                    IconButton(
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No print jobs received yet.\nFiles will appear here when someone prints to this printer.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f)
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
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = FileUtils.getReadableName(file),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Size: ${file.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Row {
                Button(
                    onClick = {
                        FileUtils.openPdfFile(context, file)
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("View")
                }
                
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Job"
                    )
                }
            }
        }
    }
} 