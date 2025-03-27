package com.example.printer.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.printer.printer.PrinterService
import com.example.printer.utils.FileUtils
import com.example.printer.utils.PreferenceUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    printerService: PrinterService,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var printJobs by remember { mutableStateOf(FileUtils.getSavedPrintJobs(context).size) }
    var printerName by remember { mutableStateOf(PreferenceUtils.getCustomPrinterName(context)) }
    var isEditingName by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE")
    var tempPrinterName by remember { mutableStateOf(printerName) }
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
            // Printer Information Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
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
                    
                    if (isEditingName) {
                        OutlinedTextField(
                            value = tempPrinterName,
                            onValueChange = { tempPrinterName = it },
                            label = { Text("Printer Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (tempPrinterName.isNotBlank()) {
                                        printerName = tempPrinterName
                                        PreferenceUtils.saveCustomPrinterName(context, tempPrinterName)
                                        isEditingName = false
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Save"
                                    )
                                }
                            }
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Name: $printerName")
                            TextButton(onClick = {
                                tempPrinterName = printerName
                                isEditingName = true
                            }) {
                                Text("Edit")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Status: Running") 
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Port: ${printerService.getPort()}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Saved print jobs: $printJobs")
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Note: Printer name changes will take effect after restarting the app",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Storage Management Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
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
                    
                    Button(
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
            
            // About Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Version 1.0")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("A virtual printer application that captures print jobs as PDF files")
                }
            }
        }
    }
} 