package com.example.printer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.printer.printer.PrinterService
import com.example.printer.utils.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    printerService: PrinterService,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var printerName by remember { mutableStateOf(printerService.getPrinterName()) }
    var printJobs by remember { mutableStateOf(FileUtils.getSavedPrintJobs(context).size) }
    
    // Refresh job count when settings screen is shown
    LaunchedEffect(Unit) {
        printJobs = FileUtils.getSavedPrintJobs(context).size
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Printer Information",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Name: $printerName")
                    Text("Status: Running") 
                    Text("Port: ${printerService.getPort()}")
                    Text("Saved print jobs: $printJobs")
                }
            }
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Storage Management",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = {
                            FileUtils.deleteAllPrintJobs(context)
                            printJobs = 0
                        },
                        enabled = printJobs > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear All Print Jobs")
                    }
                }
            }
            
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Android Virtual Printer")
                    Text("Version 1.0")
                    Text("A virtual printer application that captures print jobs as PDF files")
                }
            }
        }
    }
} 