package com.example.printer.settings

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.printer.printer.PrinterService
import com.example.printer.utils.FileUtils
import com.example.printer.utils.IppAttributesUtils
import com.example.printer.utils.PreferenceUtils
import com.example.printer.utils.PrinterDiscoveryUtils
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.Tag
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    printerService: PrinterService,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var printJobs by remember { mutableStateOf(FileUtils.getSavedPrintJobs(context).size) }
    var printerName by remember { mutableStateOf(PreferenceUtils.getCustomPrinterName(context)) }
    var isEditingName by remember { mutableStateOf(false) }
    var tempPrinterName by remember { mutableStateOf(printerName) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    var selectedAttributesFile by remember { mutableStateOf<String?>(null) }
    var availableAttributeFiles by remember { mutableStateOf(IppAttributesUtils.getAvailableIppAttributeFiles(context)) }
    
    // Printer discovery states
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredPrinters by remember { mutableStateOf<List<PrinterDiscoveryUtils.NetworkPrinter>>(emptyList()) }
    var selectedPrinter by remember { mutableStateOf<PrinterDiscoveryUtils.NetworkPrinter?>(null) }
    var showPrinterQueryingDialog by remember { mutableStateOf(false) }
    var showPrinterSaveDialog by remember { mutableStateOf(false) }
    
    // File picker launcher for importing attributes
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImportAttributes(context, it, printerService) }
    }
    
    // File picker launcher for exporting attributes
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { handleExportAttributes(context, it, printerService) }
    }
    
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
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
            }
            
            item {
                // IPP Attributes Card
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
                            text = "IPP Attributes",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Current attributes file selection
                        if (availableAttributeFiles.isNotEmpty()) {
                            Text("Current Attributes File:", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            availableAttributeFiles.forEach { filename ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        RadioButton(
                                            selected = filename == selectedAttributesFile,
                                            onClick = {
                                                selectedAttributesFile = filename
                                                IppAttributesUtils.loadIppAttributes(context, filename)?.let { attributes ->
                                                    printerService.setCustomIppAttributes(attributes)
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(filename)
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            IppAttributesUtils.deleteIppAttributes(context, filename)
                                            availableAttributeFiles = IppAttributesUtils.getAvailableIppAttributeFiles(context)
                                            if (selectedAttributesFile == filename) {
                                                selectedAttributesFile = null
                                                printerService.setCustomIppAttributes(null)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete"
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "No custom IPP attributes configured",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Import/Export/Discover buttons
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Import/Export row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showImportDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text("Import")
                                }
                                
                                Button(
                                    onClick = { showExportDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text("Export")
                                }
                            }
                            
                            // Discover printers button
                            Button(
                                onClick = { showDiscoveryDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text("Discover Network Printers")
                            }
                        }
                    }
                }
            }
            
            item {
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
                            onClick = { showDeleteAllDialog = true },
                            enabled = printJobs > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear All Print Jobs")
                        }
                    }
                }
            }
            
            item {
                // Error Simulation Card
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
                            text = "Error Simulation",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        var isSimulatingError by remember { 
                            val status = printerService.getErrorSimulationStatus()
                            mutableStateOf(status.first) 
                        }
                        var selectedErrorType by remember { 
                            val status = printerService.getErrorSimulationStatus()
                            mutableStateOf(status.second) 
                        }
                        
                        // Toggle switch for error simulation
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Simulate errors",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = isSimulatingError,
                                onCheckedChange = { checked ->
                                    isSimulatingError = checked
                                    printerService.configureErrorSimulation(checked, selectedErrorType)
                                }
                            )
                        }
                        
                        if (isSimulatingError) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Error type",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Error type radio buttons
                            Column {
                                val errorTypes = listOf(
                                    "server-error" to "Server Error",
                                    "client-error" to "Client Error",
                                    "aborted" to "Aborted Job",
                                    "unsupported-format" to "Unsupported Format"
                                )
                                
                                errorTypes.forEach { (type, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        RadioButton(
                                            selected = selectedErrorType == type,
                                            onClick = {
                                                selectedErrorType = type
                                                printerService.configureErrorSimulation(isSimulatingError, type)
                                            }
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Note: Error simulation will affect all incoming print jobs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            
            item {
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
            
            // Add extra space at the bottom to ensure all content is scrollable
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Dialogs
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Print Jobs") },
            text = { Text("Are you sure you want to delete all print jobs? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileUtils.deleteAllPrintJobs(context)
                        printJobs = 0
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
    
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import IPP Attributes") },
            text = { Text("Select a JSON file containing IPP attributes to import.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importLauncher.launch("application/json")
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export IPP Attributes") },
            text = { Text("Save the current IPP attributes to a JSON file.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportLauncher.launch("ipp_attributes.json")
                    }
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Printer Discovery Dialog
    if (showDiscoveryDialog) {
        var showTestConnectivity by remember { mutableStateOf(false) }
        var connectivityResult by remember { mutableStateOf("") }
        var isTestingConnectivity by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { 
                if (!isDiscovering && !isTestingConnectivity) showDiscoveryDialog = false 
            },
            title = { Text("Discover Network Printers") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDiscovering) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Scanning for printers on your network...")
                    } else if (isTestingConnectivity) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Testing connectivity to ${selectedPrinter?.name}...")
                    } else {
                        if (discoveredPrinters.isEmpty()) {
                            Text("No printers found. Try scanning again or make sure your printers are powered on and connected to the network.")
                        } else {
                            Text("Select a printer to query its attributes:")
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // List of discovered printers
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                discoveredPrinters.forEach { printer ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedPrinter == printer,
                                            onClick = { 
                                                selectedPrinter = printer
                                                // Reset connectivity test result when printer selection changes
                                                showTestConnectivity = false
                                                connectivityResult = ""
                                            }
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(printer.name)
                                            Text(
                                                "${printer.address.hostAddress}:${printer.port}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Show test connectivity section if a printer is selected
                            if (selectedPrinter != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Test connectivity")
                                    Button(
                                        onClick = {
                                            isTestingConnectivity = true
                                            coroutineScope.launch {
                                                val result = selectedPrinter?.let { printer ->
                                                    PrinterDiscoveryUtils.testPrinterConnectivity(printer)
                                                } ?: "No printer selected"
                                                connectivityResult = result
                                                showTestConnectivity = true
                                                isTestingConnectivity = false
                                            }
                                        },
                                        enabled = !isTestingConnectivity
                                    ) {
                                        Text("Test")
                                    }
                                }
                                
                                if (showTestConnectivity && connectivityResult.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (connectivityResult.startsWith("Connected successfully")) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.errorContainer
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = connectivityResult,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isDiscovering || isTestingConnectivity) {
                    TextButton(onClick = { /* Do nothing, operation in progress */ }) {
                        Text(if (isDiscovering) "Scanning..." else "Testing...")
                    }
                } else if (discoveredPrinters.isNotEmpty() && selectedPrinter != null) {
                    TextButton(
                        onClick = {
                            showDiscoveryDialog = false
                            showPrinterQueryingDialog = true
                            
                            coroutineScope.launch {
                                // Use improved query method with alternatives
                                val attributes = selectedPrinter?.let { printer ->
                                    PrinterDiscoveryUtils.queryPrinterWithAlternatives(printer)
                                }
                                
                                showPrinterQueryingDialog = false
                                
                                if (attributes != null && attributes.isNotEmpty()) {
                                    showPrinterSaveDialog = true
                                } else {
                                    // Show improved error message
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to query printer attributes. Check if the printer supports IPP.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Text("Query Printer")
                    }
                } else {
                    TextButton(
                        onClick = {
                            isDiscovering = true
                            discoveredPrinters = emptyList()
                            selectedPrinter = null
                            connectivityResult = ""
                            showTestConnectivity = false
                            
                            coroutineScope.launch {
                                val printers = PrinterDiscoveryUtils.discoverPrinters(context)
                                discoveredPrinters = printers
                                isDiscovering = false
                            }
                        }
                    ) {
                        Text("Scan for Printers")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        if (!isDiscovering && !isTestingConnectivity) {
                            showDiscoveryDialog = false 
                        }
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Printer Querying Dialog
    if (showPrinterQueryingDialog) {
        AlertDialog(
            onDismissRequest = { /* Non-dismissible */ },
            title = { Text("Querying Printer") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Querying printer attributes from ${selectedPrinter?.name}...")
                    Text(
                        "This may take a moment. Trying multiple methods to connect...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { }
        )
    }
    
    // Save Printer Attributes Dialog
    if (showPrinterSaveDialog) {
        var saveFilename by remember { mutableStateOf("printer_${selectedPrinter?.name?.replace(" ", "_")?.lowercase() ?: "unknown"}_${System.currentTimeMillis()}.json") }
        var useDefaultAttributes by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { showPrinterSaveDialog = false },
            title = { Text("Save Printer Attributes") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Attributes successfully queried from ${selectedPrinter?.name}.")
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = saveFilename,
                        onValueChange = { saveFilename = it },
                        label = { Text("Filename") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    // Option to use default attributes if actual query failed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useDefaultAttributes,
                            onCheckedChange = { useDefaultAttributes = it }
                        )
                        Text(
                            text = "Use generic attributes if printer query failed",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            selectedPrinter?.let { printer ->
                                val attributes = if (useDefaultAttributes) {
                                    // Always use alternative method which creates defaults if needed
                                    PrinterDiscoveryUtils.queryPrinterWithAlternatives(printer)
                                } else {
                                    // Try regular query first
                                    PrinterDiscoveryUtils.queryPrinterAttributes(printer) 
                                        ?: PrinterDiscoveryUtils.createMinimalAttributes(printer)
                                }
                                
                                if (attributes != null) {
                                    val success = PrinterDiscoveryUtils.exportPrinterAttributesToFile(
                                        context, 
                                        attributes, 
                                        saveFilename
                                    )
                                    
                                    if (success) {
                                        // Refresh the list of available attribute files
                                        availableAttributeFiles = IppAttributesUtils.getAvailableIppAttributeFiles(context)
                                        
                                        android.widget.Toast.makeText(
                                            context,
                                            "Printer attributes saved successfully",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Failed to save printer attributes",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            showPrinterSaveDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPrinterSaveDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun handleImportAttributes(context: android.content.Context, uri: Uri, printerService: PrinterService) {
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = org.json.JSONArray(jsonString)
            val attributes = mutableListOf<AttributeGroup>()
            
            for (i in 0 until jsonArray.length()) {
                val groupObj = jsonArray.getJSONObject(i)
                val tagName = groupObj.getString("tag")
                val tag = try {
                    IppAttributesUtils.getTagByName(tagName) ?: Tag.printerAttributes
                } catch (e: Exception) {
                    Log.e("SettingsScreen", "Invalid tag: $tagName", e)
                    Tag.printerAttributes // Default to printer attributes
                }
                
                val attrsArray = groupObj.getJSONArray("attributes")
                val attrsList = mutableListOf<com.hp.jipp.encoding.Attribute<*>>()
                
                for (j in 0 until attrsArray.length()) {
                    try {
                        val attrObj = attrsArray.getJSONObject(j)
                        val name = attrObj.getString("name")
                        val value = attrObj.getString("value")
                        val type = attrObj.getString("type")
                        
                        // Load through IppAttributesUtils instead of direct creation
                        val attr = IppAttributesUtils.createAttribute(name, value, type)
                        if (attr != null) {
                            attrsList.add(attr)
                        }
                    } catch (e: Exception) {
                        Log.e("SettingsScreen", "Error processing attribute", e)
                    }
                }
                
                if (attrsList.isNotEmpty()) {
                    attributes.add(IppAttributesUtils.createAttributeGroup(tag, attrsList))
                }
            }
            
            if (IppAttributesUtils.validateIppAttributes(attributes)) {
                val filename = "ipp_attributes_${System.currentTimeMillis()}.json"
                if (IppAttributesUtils.saveIppAttributes(context, attributes, filename)) {
                    printerService.setCustomIppAttributes(attributes)
                    android.widget.Toast.makeText(
                        context,
                        "IPP attributes imported successfully",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Failed to save IPP attributes",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Invalid IPP attributes format",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("SettingsScreen", "Error importing IPP attributes", e)
        android.widget.Toast.makeText(
            context,
            "Error importing IPP attributes: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

private fun handleExportAttributes(context: android.content.Context, uri: Uri, printerService: PrinterService) {
    try {
        val attributes = printerService.getCustomIppAttributes()
        if (attributes != null) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                val jsonArray = org.json.JSONArray()
                
                attributes.forEach { group ->
                    val groupObj = org.json.JSONObject().apply {
                        put("tag", group.tag.name)
                        put("attributes", org.json.JSONArray().apply {
                            IppAttributesUtils.getAttributesFromGroup(group).forEach { attr ->
                                put(org.json.JSONObject().apply {
                                    put("name", attr.name)
                                    put("value", attr.toString())
                                    put("type", "STRING") // Default type
                                })
                            }
                        })
                    }
                    jsonArray.put(groupObj)
                }
                
                outputStream.write(jsonArray.toString().toByteArray())
                android.widget.Toast.makeText(
                    context,
                    "IPP attributes exported successfully",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "No IPP attributes to export",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Exception) {
        android.util.Log.e("SettingsScreen", "Error exporting IPP attributes", e)
        android.widget.Toast.makeText(
            context,
            "Error exporting IPP attributes: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
} 