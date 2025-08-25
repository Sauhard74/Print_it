package com.example.printer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.printer.logging.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val logger = remember { PrinterLogger.getInstance(context) }
    
    // State
    val logEntries by logger.logEntries.collectAsStateWithLifecycle()
    val logStatistics by logger.logStatistics.collectAsStateWithLifecycle()
    
    var selectedFilter by remember { mutableStateOf(LogFilter()) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showStatistics by remember { mutableStateOf(false) }
    var selectedLogEntry by remember { mutableStateOf<LogEntry?>(null) }
    var showLogDetails by remember { mutableStateOf(false) }
    
    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { 
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        val logs = logger.getLogs(selectedFilter, Int.MAX_VALUE)
                        val jsonContent = buildString {
                            append("[\n")
                            logs.forEachIndexed { index, log ->
                                append("  ")
                                append(log.toJson().toString(2).replace("\n", "\n  "))
                                if (index < logs.size - 1) append(",")
                                append("\n")
                            }
                            append("]")
                        }
                        outputStream.write(jsonContent.toByteArray())
                    }
                } catch (e: Exception) {
                    logger.e(LogCategory.SYSTEM, "LogsScreen", "Export failed", e)
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("System Logs")
                        Text(
                            text = "${logEntries.size} entries",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showStatistics = true }) {
                        Icon(Icons.Default.Info, "Statistics")
                    }
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.Settings, "Filter")
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.Info, "Export")
                    }
                    
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear Logs") },
                            onClick = {
                                logger.clearLogs()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Clear, null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("New Session") },
                            onClick = {
                                logger.startNewSession()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, null)
                            }
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
        ) {
            // Filter Summary
            if (selectedFilter != LogFilter()) {
                FilterSummaryCard(
                    filter = selectedFilter,
                    onClearFilter = { selectedFilter = LogFilter() }
                )
            }
            
            // Log Entries
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                val filteredLogs = logger.getLogs(selectedFilter, 1000)
                
                if (filteredLogs.isEmpty()) {
                    item {
                        EmptyLogsCard()
                    }
                } else {
                    items(filteredLogs, key = { it.id }) { logEntry ->
                        LogEntryCard(
                            logEntry = logEntry,
                            onClick = {
                                selectedLogEntry = it
                                showLogDetails = true
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Filter Dialog
    if (showFilterDialog) {
        LogFilterDialog(
            currentFilter = selectedFilter,
            onFilterApplied = { 
                selectedFilter = it
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }
    
    // Export Dialog
    if (showExportDialog) {
        ExportLogsDialog(
            onExport = { format ->
                val filename = "printer_logs_${System.currentTimeMillis()}"
                when (format) {
                    ExportFormat.JSON -> exportLauncher.launch("$filename.json")
                    ExportFormat.CSV -> exportLauncher.launch("$filename.csv")
                    ExportFormat.TEXT -> exportLauncher.launch("$filename.txt")
                }
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false }
        )
    }
    
    // Statistics Dialog
    if (showStatistics && logStatistics != null) {
        LogStatisticsDialog(
            statistics = logStatistics!!,
            onDismiss = { showStatistics = false }
        )
    }
    
    // Log Details Dialog
    if (showLogDetails && selectedLogEntry != null) {
        LogDetailsDialog(
            logEntry = selectedLogEntry!!,
            onDismiss = { 
                showLogDetails = false
                selectedLogEntry = null
            }
        )
    }
}

@Composable
fun FilterSummaryCard(
    filter: LogFilter,
    onClearFilter: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Active Filters:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = buildString {
                        if (filter.minLevel != LogLevel.DEBUG) append("Level: ${filter.minLevel.name}")
                        if (filter.categories.isNotEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append("Categories: ${filter.categories.size}")
                        }
                        if (filter.searchText != null) {
                            if (isNotEmpty()) append(", ")
                            append("Search: ${filter.searchText}")
                        }
                        if (filter.jobId != null) {
                            if (isNotEmpty()) append(", ")
                            append("Job: ${filter.jobId}")
                        }
                        if (isEmpty()) append("No filters")
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            IconButton(onClick = onClearFilter) {
                Icon(Icons.Default.Clear, "Clear Filters")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogEntryCard(
    logEntry: LogEntry,
    onClick: (LogEntry) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = { onClick(logEntry) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Level indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = getLogLevelColor(logEntry.level),
                        shape = RoundedCornerShape(50)
                    )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${logEntry.level.tag}/${logEntry.tag}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = getLogLevelColor(logEntry.level)
                    )
                    
                    Text(
                        text = formatLogTimestamp(logEntry.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                Text(
                    text = logEntry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryChip(logEntry.category)
                    
                    if (logEntry.jobId != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Job ${logEntry.jobId}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    
                    if (logEntry.duration != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${logEntry.duration}ms",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(category: LogCategory) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = category.displayName,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun EmptyLogsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No log entries found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Try adjusting your filters or wait for new activity",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFilterDialog(
    currentFilter: LogFilter,
    onFilterApplied: (LogFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var minLevel by remember { mutableStateOf(currentFilter.minLevel) }
    var selectedCategories by remember { mutableStateOf(currentFilter.categories) }
    var searchText by remember { mutableStateOf(currentFilter.searchText ?: "") }
    var jobIdText by remember { mutableStateOf(currentFilter.jobId?.toString() ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Logs") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Log Level Filter
                Text(
                    text = "Minimum Log Level:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                LogLevel.values().forEach { level ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { minLevel = level },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = minLevel == level,
                            onClick = { minLevel = level }
                        )
                        Text(
                            text = level.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Category Filter
                Text(
                    text = "Categories:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                LogCategory.values().forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                selectedCategories = if (selectedCategories.contains(category)) {
                                    selectedCategories - category
                                } else {
                                    selectedCategories + category
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedCategories.contains(category),
                            onCheckedChange = { checked ->
                                selectedCategories = if (checked) {
                                    selectedCategories + category
                                } else {
                                    selectedCategories - category
                                }
                            }
                        )
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Search Text
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    label = { Text("Search Text") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Job ID Filter
                OutlinedTextField(
                    value = jobIdText,
                    onValueChange = { jobIdText = it },
                    label = { Text("Job ID") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val filter = LogFilter(
                        minLevel = minLevel,
                        categories = selectedCategories,
                        searchText = searchText.takeIf { it.isNotBlank() },
                        jobId = jobIdText.toLongOrNull()
                    )
                    onFilterApplied(filter)
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ExportLogsDialog(
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Logs") },
        text = {
            Column {
                Text("Choose export format:")
                Spacer(modifier = Modifier.height(8.dp))
                
                ExportFormat.values().forEach { format ->
                    TextButton(
                        onClick = { onExport(format) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(format.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LogStatisticsDialog(
    statistics: LogStatistics,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Statistics") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Log Statistics Overview:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                StatRow("Total Entries", statistics.totalEntries.toString())
                StatRow("Error Count", (statistics.entriesByLevel[LogLevel.ERROR] ?: 0).toString())
                StatRow("Warning Count", (statistics.entriesByLevel[LogLevel.WARN] ?: 0).toString())
                StatRow("Info Count", (statistics.entriesByLevel[LogLevel.INFO] ?: 0).toString())
                StatRow("Debug Count", (statistics.entriesByLevel[LogLevel.DEBUG] ?: 0).toString())
                
                Spacer(modifier = Modifier.height(16.dp))
                
                StatRow("Average per Minute", String.format("%.2f", statistics.averageEntriesPerMinute))
                StatRow("Most Active Category", statistics.entriesByCategory.maxByOrNull { it.value }?.key?.displayName ?: "N/A")
                StatRow("Top Errors", statistics.topErrors.size.toString())
                
                Spacer(modifier = Modifier.height(16.dp))
                
                statistics.timeRange?.let { (start, end) ->
                    StatRow("First Entry", SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(start)))
                    StatRow("Last Entry", SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(end)))
                } ?: run {
                    StatRow("Time Range", "No entries")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun LogDetailsDialog(
    logEntry: LogEntry,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Entry Details") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                StatRow("Timestamp", SimpleDateFormat("MMM dd, yyyy HH:mm:ss.SSS", Locale.getDefault()).format(Date(logEntry.timestamp)))
                StatRow("Level", logEntry.level.name)
                StatRow("Category", logEntry.category.name)
                StatRow("Tag", logEntry.tag)
                
                if (logEntry.jobId != null) {
                    StatRow("Job ID", logEntry.jobId.toString())
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Message:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = logEntry.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                
                if (logEntry.stackTrace != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Stack Trace:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = logEntry.stackTrace,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                if (logEntry.metadata.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Metadata:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    logEntry.metadata.forEach { (key, value) ->
                        StatRow(key, value.toString())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(2f)
        )
    }
}

private fun getLogLevelColor(level: LogLevel): Color {
    return when (level) {
        LogLevel.VERBOSE -> Color.Gray
        LogLevel.DEBUG -> Color.Blue
        LogLevel.INFO -> Color.Green
        LogLevel.WARN -> Color(0xFFFF9800) // Orange
        LogLevel.ERROR -> Color.Red
        LogLevel.CRITICAL -> Color(0xFF8B0000) // Dark Red
    }
}

private fun formatLogTimestamp(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}