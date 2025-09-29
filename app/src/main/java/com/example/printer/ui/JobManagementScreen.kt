package com.example.printer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.printer.queue.PrintJob
import com.example.printer.queue.PrintJobQueue
import com.example.printer.queue.PrintJobState
import com.example.printer.simulator.PrintJobSimulator
import com.example.printer.logging.PrinterLogger
import com.example.printer.logging.LogCategory
import com.example.printer.logging.LogLevel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobManagementScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Get instances
    val jobQueue = remember { PrintJobQueue.getInstance(context) }
    val simulator = remember { PrintJobSimulator.getInstance(context) }
    val logger = remember { PrinterLogger.getInstance(context) }
    
    // State
    val jobs by jobQueue.jobsFlow.collectAsStateWithLifecycle()
    val queueStats by jobQueue.queueStatistics.collectAsStateWithLifecycle()
    val simulationState by simulator.simulationState.collectAsStateWithLifecycle()
    
    var selectedJob by remember { mutableStateOf<PrintJob?>(null) }
    var showJobDetails by remember { mutableStateOf(false) }
    var showSimulationConfig by remember { mutableStateOf(false) }
    var selectedJobForAction by remember { mutableStateOf<PrintJob?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job Management") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSimulationConfig = true }) {
                        Icon(Icons.Default.Settings, "Simulation Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Queue Statistics Card
            item {
                QueueStatisticsCard(
                    stats = queueStats,
                    simulationEnabled = simulationState.isSimulating
                )
            }
            
            // Active Errors Card (if any)
            if (simulationState.activeErrors.isNotEmpty()) {
                item {
                    ActiveErrorsCard(
                        activeErrors = simulationState.activeErrors,
                        onResolveError = { errorId ->
                            coroutineScope.launch {
                                simulator.resolveError(errorId, "Manual resolution")
                                logger.i(LogCategory.USER_ACTION, "JobManagementScreen", 
                                    "Manually resolved error: $errorId")
                            }
                        }
                    )
                }
            }
            
            // Job List Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Print Jobs (${jobs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Filter/Sort options could go here
                    IconButton(onClick = { /* TODO: Add filtering */ }) {
                        Icon(Icons.Default.Settings, "Filter")
                    }
                }
            }
            
            // Job List
            if (jobs.isEmpty()) {
                item {
                    EmptyJobsCard()
                }
            } else {
                items(jobs) { job ->
                    JobCard(
                        job = job,
                        onJobClick = { 
                            selectedJob = it
                            showJobDetails = true
                        },
                        onActionClick = { 
                            selectedJobForAction = it
                            showActionDialog = true
                        }
                    )
                }
            }
        }
    }
    
    // Job Details Dialog
    if (showJobDetails && selectedJob != null) {
        JobDetailsDialog(
            job = selectedJob!!,
            onDismiss = { 
                showJobDetails = false
                selectedJob = null
            },
            onAction = { job, action ->
                coroutineScope.launch {
                    when (action) {
                        "hold" -> jobQueue.holdJob(job.id)
                        "release" -> jobQueue.releaseJob(job.id)
                        "cancel" -> jobQueue.cancelJob(job.id)
                        "delete" -> jobQueue.deleteJob(job.id)
                    }
                    logger.i(LogCategory.USER_ACTION, "JobManagementScreen", 
                        "Performed action '$action' on job ${job.id}")
                }
                showJobDetails = false
                selectedJob = null
            }
        )
    }
    
    // Quick Action Dialog
    if (showActionDialog && selectedJobForAction != null) {
        QuickActionDialog(
            job = selectedJobForAction!!,
            onDismiss = { 
                showActionDialog = false
                selectedJobForAction = null
            },
            onAction = { job, action ->
                coroutineScope.launch {
                    when (action) {
                        "hold" -> jobQueue.holdJob(job.id)
                        "release" -> jobQueue.releaseJob(job.id)
                        "cancel" -> jobQueue.cancelJob(job.id)
                        "delete" -> jobQueue.deleteJob(job.id)
                    }
                    logger.i(LogCategory.USER_ACTION, "JobManagementScreen", 
                        "Quick action '$action' on job ${job.id}")
                }
                showActionDialog = false
                selectedJobForAction = null
            }
        )
    }
    
    // Simulation Configuration Dialog
    if (showSimulationConfig) {
        SimulationConfigDialog(
            simulator = simulator,
            onDismiss = { showSimulationConfig = false }
        )
    }
}

@Composable
fun QueueStatisticsCard(
    stats: PrintJobQueue.QueueStatistics?,
    simulationEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Queue Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // Simulation Status Indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (simulationEnabled) Color.Green else Color.Gray,
                                shape = RoundedCornerShape(50)
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (simulationEnabled) "Simulation ON" else "Simulation OFF",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (simulationEnabled) Color.Green else Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (stats != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Total Jobs", stats.totalJobs.toString())
                    StatItem("Pending", stats.pendingJobs.toString())
                    StatItem("Processing", stats.processingJobs.toString())
                    StatItem("Completed", stats.completedJobs.toString())
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Held", stats.heldJobs.toString())
                    StatItem("Canceled", stats.canceledJobs.toString())
                    StatItem("Queue Size", "${stats.queueSize / 1024} KB")
                    StatItem("Avg Time", "${stats.averageProcessingTime}ms")
                }
            } else {
                Text(
                    text = "No statistics available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun ActiveErrorsCard(
    activeErrors: Map<String, com.example.printer.simulator.SimulatedError>,
    onResolveError: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Active Errors (${activeErrors.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            activeErrors.forEach { (errorId, error) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = error.description,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = error.code,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    
                    if (error.recoverable) {
                        TextButton(
                            onClick = { onResolveError(errorId) }
                        ) {
                            Text("Resolve")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobCard(
    job: PrintJob,
    onJobClick: (PrintJob) -> Unit,
    onActionClick: (PrintJob) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = { onJobClick(job) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = job.getReadableName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Text(
                        text = "Job #${job.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                JobStateChip(job.state)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Format: ${job.documentFormat}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Size: ${job.size / 1024} KB",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Column {
                    Text(
                        text = "User: ${job.jobOriginatingUserName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatTimestamp(job.submissionTime),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            if (job.state in listOf(PrintJobState.PENDING, PrintJobState.HELD, PrintJobState.PROCESSING)) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onActionClick(job) }
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Actions")
                    }
                }
            }
        }
    }
}

@Composable
fun JobStateChip(state: PrintJobState) {
    val (color, text) = when (state) {
        PrintJobState.PENDING -> MaterialTheme.colorScheme.primary to "Pending"
        PrintJobState.HELD -> MaterialTheme.colorScheme.tertiary to "Held"
        PrintJobState.PROCESSING -> MaterialTheme.colorScheme.secondary to "Processing"
        PrintJobState.STOPPED -> MaterialTheme.colorScheme.outline to "Stopped"
        PrintJobState.CANCELED -> MaterialTheme.colorScheme.error to "Canceled"
        PrintJobState.ABORTED -> MaterialTheme.colorScheme.error to "Aborted"
        PrintJobState.COMPLETED -> Color.Green to "Completed"
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EmptyJobsCard() {
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
                text = "No print jobs in queue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Print jobs will appear here when received",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun JobDetailsDialog(
    job: PrintJob,
    onDismiss: () -> Unit,
    onAction: (PrintJob, String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Job Details")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                JobDetailItem("Job ID", job.id.toString())
                JobDetailItem("Name", job.getReadableName())
                JobDetailItem("State", job.getStateDescription())
                JobDetailItem("Format", job.documentFormat)
                JobDetailItem("Size", "${job.size / 1024} KB")
                JobDetailItem("User", job.jobOriginatingUserName)
                JobDetailItem("Submitted", formatTimestamp(job.submissionTime))
                JobDetailItem("State Reasons", job.stateReasons.joinToString(", "))
                
                if (job.metadata.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Metadata:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    job.metadata.forEach { (key, value) ->
                        JobDetailItem(key, value.toString())
                    }
                }
            }
        },
        confirmButton = {
            Row {
                when (job.state) {
                    PrintJobState.PENDING -> {
                        TextButton(onClick = { onAction(job, "hold") }) {
                            Text("Hold")
                        }
                        TextButton(onClick = { onAction(job, "cancel") }) {
                            Text("Cancel")
                        }
                    }
                    PrintJobState.HELD -> {
                        TextButton(onClick = { onAction(job, "release") }) {
                            Text("Release")
                        }
                        TextButton(onClick = { onAction(job, "cancel") }) {
                            Text("Cancel")
                        }
                    }
                    PrintJobState.PROCESSING -> {
                        TextButton(onClick = { onAction(job, "cancel") }) {
                            Text("Cancel")
                        }
                    }
                    else -> {
                        TextButton(onClick = { onAction(job, "delete") }) {
                            Text("Delete")
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun JobDetailItem(label: String, value: String) {
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

@Composable
fun QuickActionDialog(
    job: PrintJob,
    onDismiss: () -> Unit,
    onAction: (PrintJob, String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Job Actions")
        },
        text = {
            Text("Choose an action for job: ${job.getReadableName()}")
        },
        confirmButton = {
            Column {
                when (job.state) {
                    PrintJobState.PENDING -> {
                        TextButton(
                            onClick = { onAction(job, "hold") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Hold Job")
                        }
                        TextButton(
                            onClick = { onAction(job, "cancel") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel Job")
                        }
                    }
                    PrintJobState.HELD -> {
                        TextButton(
                            onClick = { onAction(job, "release") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ArrowBack, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Release Job")
                        }
                        TextButton(
                            onClick = { onAction(job, "cancel") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel Job")
                        }
                    }
                    PrintJobState.PROCESSING -> {
                        TextButton(
                            onClick = { onAction(job, "cancel") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel Job")
                        }
                    }
                    else -> {
                        TextButton(
                            onClick = { onAction(job, "delete") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete Job")
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulationConfigDialog(
    simulator: PrintJobSimulator,
    onDismiss: () -> Unit
) {
    val currentConfig = simulator.getSimulationConfig()
    var enabled by remember { mutableStateOf(currentConfig.enabled) }
    var errorProbability by remember { mutableStateOf(currentConfig.errorProbability) }
    var autoRecover by remember { mutableStateOf(currentConfig.autoRecover) }
    var errorDuration by remember { mutableStateOf(currentConfig.errorDuration) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Simulation Configuration")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Configure error simulation settings:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Enable/Disable simulation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Simulation", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Error Probability
                Text(
                    text = "Error Probability: ${(errorProbability * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = errorProbability,
                    onValueChange = { errorProbability = it },
                    valueRange = 0f..1f,
                    enabled = enabled
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Auto Recovery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto Recovery", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = autoRecover,
                        onCheckedChange = { autoRecover = it },
                        enabled = enabled
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Error Duration
                Text(
                    text = "Error Duration: ${errorDuration / 1000}s",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = errorDuration.toFloat(),
                    onValueChange = { errorDuration = it.toLong() },
                    valueRange = 1000f..30000f, // 1-30 seconds
                    enabled = enabled && autoRecover
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newConfig = currentConfig.copy(
                        enabled = enabled,
                        errorProbability = errorProbability,
                        autoRecover = autoRecover,
                        errorDuration = errorDuration
                    )
                    simulator.configureSimulation(newConfig)
                    onDismiss()
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

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
}