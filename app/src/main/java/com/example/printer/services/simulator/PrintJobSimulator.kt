package com.example.printer.services.simulator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Professional Print Job Simulator for Chromium QA Testing
 * Simulates various printer states, error conditions, and edge cases
 * for comprehensive testing scenarios
 */
class PrintJobSimulator {
    
    private val _simulatorState = MutableStateFlow(SimulatorState.DISABLED)
    val simulatorState: Flow<SimulatorState> = _simulatorState.asStateFlow()
    
    private val _activeSimulations = MutableStateFlow<List<ActiveSimulation>>(emptyList())
    val activeSimulations: Flow<List<ActiveSimulation>> = _activeSimulations.asStateFlow()
    
    private var isRunning = false
    
    /**
     * Simulator operational states
     */
    enum class SimulatorState {
        DISABLED,
        IDLE,
        SIMULATING_ERROR,
        SIMULATING_DELAY,
        SIMULATING_PAPER_JAM,
        SIMULATING_LOW_TONER,
        SIMULATING_OFFLINE,
        SIMULATING_BUSY,
        CUSTOM_SCENARIO
    }
    
    /**
     * Available error simulation types for QA testing
     */
    enum class ErrorType {
        PAPER_JAM,
        OUT_OF_PAPER,
        LOW_TONER,
        OUT_OF_TONER,
        PRINTER_OFFLINE,
        NETWORK_ERROR,
        AUTHENTICATION_ERROR,
        INVALID_FORMAT,
        INSUFFICIENT_MEMORY,
        HARDWARE_FAILURE,
        DRIVER_ERROR,
        TIMEOUT,
        RANDOM_FAILURE,
        PERFORMANCE_DEGRADATION,
        INTERMITTENT_CONNECTION
    }
    
    /**
     * Severity levels for error simulation
     */
    enum class ErrorSeverity {
        LOW,        // Warning level, job continues
        MEDIUM,     // Job paused, user intervention needed
        HIGH,       // Job failed, retry possible
        CRITICAL    // System failure, requires restart
    }
    
    /**
     * Active simulation tracking
     */
    data class ActiveSimulation(
        val id: String,
        val type: ErrorType,
        val severity: ErrorSeverity,
        val startTime: LocalDateTime,
        val duration: Long, // milliseconds
        val description: String,
        val affectedJobs: Int = 0,
        val recoveryAction: String? = null
    )
    
    /**
     * Simulation configuration for comprehensive testing
     */
    data class SimulationConfig(
        val enableRandomErrors: Boolean = false,
        val errorProbability: Double = 0.1, // 10% chance
        val enablePerformanceDegradation: Boolean = false,
        val slowdownFactor: Double = 2.0, // 2x slower
        val enableIntermittentFailures: Boolean = false,
        val failureInterval: Long = 30000, // 30 seconds
        val enableResourceExhaustion: Boolean = false,
        val maxJobsBeforeFailure: Int = 100,
        val enableNetworkIssues: Boolean = false,
        val networkFailureRate: Double = 0.05 // 5% chance
    )
    
    private var currentConfig = SimulationConfig()
    
    /**
     * Starts the print job simulator with specified configuration
     */
    suspend fun startSimulator(config: SimulationConfig = SimulationConfig()) {
        if (isRunning) return
        
        currentConfig = config
        isRunning = true
        _simulatorState.value = SimulatorState.IDLE
        
        // Start background simulation processes
        if (config.enableRandomErrors) {
            startRandomErrorSimulation()
        }
        
        if (config.enableIntermittentFailures) {
            startIntermittentFailureSimulation()
        }
        
        if (config.enableNetworkIssues) {
            startNetworkIssueSimulation()
        }
    }
    
    /**
     * Stops the simulator and clears all active simulations
     */
    suspend fun stopSimulator() {
        isRunning = false
        _simulatorState.value = SimulatorState.DISABLED
        _activeSimulations.value = emptyList()
    }
    
    /**
     * Simulates specific error scenario for targeted testing
     */
    suspend fun simulateError(
        errorType: ErrorType,
        severity: ErrorSeverity = ErrorSeverity.MEDIUM,
        duration: Long = 10000, // 10 seconds default
        description: String? = null
    ): String {
        val simulationId = generateSimulationId()
        val simulation = ActiveSimulation(
            id = simulationId,
            type = errorType,
            severity = severity,
            startTime = LocalDateTime.now(),
            duration = duration,
            description = description ?: generateErrorDescription(errorType, severity)
        )
        
        // Add to active simulations
        val currentSimulations = _activeSimulations.value.toMutableList()
        currentSimulations.add(simulation)
        _activeSimulations.value = currentSimulations
        
        // Update simulator state
        _simulatorState.value = when (errorType) {
            ErrorType.PAPER_JAM -> SimulatorState.SIMULATING_PAPER_JAM
            ErrorType.LOW_TONER, ErrorType.OUT_OF_TONER -> SimulatorState.SIMULATING_LOW_TONER
            ErrorType.PRINTER_OFFLINE, ErrorType.NETWORK_ERROR -> SimulatorState.SIMULATING_OFFLINE
            else -> SimulatorState.SIMULATING_ERROR
        }
        
        // Schedule removal after duration
        scheduleSimulationEnd(simulationId, duration)
        
        return simulationId
    }
    
    /**
     * Simulates paper jam scenario with realistic behavior
     */
    suspend fun simulatePaperJam(severity: ErrorSeverity = ErrorSeverity.HIGH): String {
        return simulateError(
            errorType = ErrorType.PAPER_JAM,
            severity = severity,
            duration = 30000, // 30 seconds
            description = "Paper jam detected in tray 1. Manual intervention required to clear paper path."
        )
    }
    
    /**
     * Simulates low toner scenario
     */
    suspend fun simulateLowToner(severity: ErrorSeverity = ErrorSeverity.LOW): String {
        return simulateError(
            errorType = ErrorType.LOW_TONER,
            severity = severity,
            duration = 60000, // 1 minute
            description = "Toner level below 15%. Print quality may be affected. Replace toner cartridge soon."
        )
    }
    
    /**
     * Simulates printer going offline
     */
    suspend fun simulateOffline(duration: Long = 45000): String {
        return simulateError(
            errorType = ErrorType.PRINTER_OFFLINE,
            severity = ErrorSeverity.HIGH,
            duration = duration,
            description = "Printer has gone offline. Check network connectivity and power status."
        )
    }
    
    /**
     * Simulates network connectivity issues
     */
    suspend fun simulateNetworkError(duration: Long = 20000): String {
        return simulateError(
            errorType = ErrorType.NETWORK_ERROR,
            severity = ErrorSeverity.MEDIUM,
            duration = duration,
            description = "Network connectivity issue detected. Attempting to reconnect..."
        )
    }
    
    /**
     * Simulates authentication failures
     */
    suspend fun simulateAuthenticationError(): String {
        return simulateError(
            errorType = ErrorType.AUTHENTICATION_ERROR,
            severity = ErrorSeverity.MEDIUM,
            duration = 15000,
            description = "Authentication failed. Invalid credentials or access denied."
        )
    }
    
    /**
     * Simulates invalid document format error
     */
    suspend fun simulateFormatError(): String {
        return simulateError(
            errorType = ErrorType.INVALID_FORMAT,
            severity = ErrorSeverity.HIGH,
            duration = 5000,
            description = "Unsupported document format. Unable to process print job."
        )
    }
    
    /**
     * Simulates printer busy state with queue backup
     */
    suspend fun simulateBusyPrinter(queueSize: Int = 5, duration: Long = 60000): String {
        _simulatorState.value = SimulatorState.SIMULATING_BUSY
        
        return simulateError(
            errorType = ErrorType.HARDWARE_FAILURE,
            severity = ErrorSeverity.MEDIUM,
            duration = duration,
            description = "Printer busy processing $queueSize jobs. Estimated wait time: ${duration / 1000} seconds."
        )
    }
    
    /**
     * Simulates performance degradation scenario
     */
    suspend fun simulatePerformanceDegradation(slowdownFactor: Double = 3.0, duration: Long = 120000): String {
        return simulateError(
            errorType = ErrorType.PERFORMANCE_DEGRADATION,
            severity = ErrorSeverity.LOW,
            duration = duration,
            description = "Performance degraded by ${slowdownFactor}x. Print jobs taking longer than usual."
        )
    }
    
    /**
     * Simulates complex multi-error scenario for advanced testing
     */
    suspend fun simulateComplexScenario(): List<String> {
        val simulationIds = mutableListOf<String>()
        
        // Start with network instability
        simulationIds.add(simulateNetworkError(15000))
        delay(5000)
        
        // Add performance degradation
        simulationIds.add(simulatePerformanceDegradation(2.5, 30000))
        delay(10000)
        
        // Introduce paper jam
        simulationIds.add(simulatePaperJam(ErrorSeverity.HIGH))
        delay(20000)
        
        // End with low toner warning
        simulationIds.add(simulateLowToner(ErrorSeverity.LOW))
        
        return simulationIds
    }
    
    /**
     * Clears specific simulation by ID
     */
    suspend fun clearSimulation(simulationId: String): Boolean {
        val currentSimulations = _activeSimulations.value.toMutableList()
        val removed = currentSimulations.removeIf { it.id == simulationId }
        
        if (removed) {
            _activeSimulations.value = currentSimulations
            
            // Update state if no simulations remain
            if (currentSimulations.isEmpty()) {
                _simulatorState.value = if (isRunning) SimulatorState.IDLE else SimulatorState.DISABLED
            }
        }
        
        return removed
    }
    
    /**
     * Clears all active simulations
     */
    suspend fun clearAllSimulations() {
        _activeSimulations.value = emptyList()
        _simulatorState.value = if (isRunning) SimulatorState.IDLE else SimulatorState.DISABLED
    }
    
    /**
     * Gets current simulation status for monitoring
     */
    fun getSimulationStatus(): SimulationStatus {
        val activeSimulations = _activeSimulations.value
        return SimulationStatus(
            isActive = isRunning,
            state = _simulatorState.value,
            activeSimulationCount = activeSimulations.size,
            totalErrorsSimulated = activeSimulations.size,
            criticalErrorsActive = activeSimulations.count { it.severity == ErrorSeverity.CRITICAL },
            longestRunningSimulation = activeSimulations.maxByOrNull { 
                LocalDateTime.now().toEpochSecond(java.time.ZoneOffset.UTC) - 
                it.startTime.toEpochSecond(java.time.ZoneOffset.UTC) 
            }
        )
    }
    
    /**
     * Status information for monitoring and debugging
     */
    data class SimulationStatus(
        val isActive: Boolean,
        val state: SimulatorState,
        val activeSimulationCount: Int,
        val totalErrorsSimulated: Int,
        val criticalErrorsActive: Int,
        val longestRunningSimulation: ActiveSimulation?
    )
    
    /**
     * Checks if a specific error type should be triggered based on configuration
     */
    fun shouldTriggerError(errorType: ErrorType): Boolean {
        if (!isRunning) return false
        
        return when (errorType) {
            ErrorType.RANDOM_FAILURE -> currentConfig.enableRandomErrors && Random.nextDouble() < currentConfig.errorProbability
            ErrorType.NETWORK_ERROR -> currentConfig.enableNetworkIssues && Random.nextDouble() < currentConfig.networkFailureRate
            ErrorType.PERFORMANCE_DEGRADATION -> currentConfig.enablePerformanceDegradation
            else -> false
        }
    }
    
    /**
     * Gets current performance multiplier based on active simulations
     */
    fun getPerformanceMultiplier(): Double {
        if (!isRunning || !currentConfig.enablePerformanceDegradation) return 1.0
        
        val hasPerformanceIssue = _activeSimulations.value.any { 
            it.type == ErrorType.PERFORMANCE_DEGRADATION 
        }
        
        return if (hasPerformanceIssue) currentConfig.slowdownFactor else 1.0
    }
    
    // Private helper methods
    
    private suspend fun startRandomErrorSimulation() {
        // Background coroutine for random error injection
        while (isRunning) {
            delay(Random.nextLong(10000, 60000)) // 10-60 seconds
            
            if (shouldTriggerError(ErrorType.RANDOM_FAILURE)) {
                val randomError = ErrorType.values().random()
                simulateError(
                    errorType = randomError,
                    severity = ErrorSeverity.values().random(),
                    duration = Random.nextLong(5000, 30000)
                )
            }
        }
    }
    
    private suspend fun startIntermittentFailureSimulation() {
        while (isRunning) {
            delay(currentConfig.failureInterval)
            
            if (currentConfig.enableIntermittentFailures) {
                simulateNetworkError(Random.nextLong(5000, 15000))
            }
        }
    }
    
    private suspend fun startNetworkIssueSimulation() {
        while (isRunning) {
            delay(Random.nextLong(30000, 120000)) // 30s - 2min
            
            if (shouldTriggerError(ErrorType.NETWORK_ERROR)) {
                simulateNetworkError(Random.nextLong(10000, 30000))
            }
        }
    }
    
    private suspend fun scheduleSimulationEnd(simulationId: String, duration: Long) {
        delay(duration)
        clearSimulation(simulationId)
    }
    
    private fun generateSimulationId(): String {
        return "sim_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
    }
    
    private fun generateErrorDescription(errorType: ErrorType, severity: ErrorSeverity): String {
        val severityText = when (severity) {
            ErrorSeverity.LOW -> "Warning"
            ErrorSeverity.MEDIUM -> "Error"
            ErrorSeverity.HIGH -> "Critical Error"
            ErrorSeverity.CRITICAL -> "System Failure"
        }
        
        val errorText = when (errorType) {
            ErrorType.PAPER_JAM -> "Paper jam detected"
            ErrorType.OUT_OF_PAPER -> "Out of paper"
            ErrorType.LOW_TONER -> "Low toner level"
            ErrorType.OUT_OF_TONER -> "Toner cartridge empty"
            ErrorType.PRINTER_OFFLINE -> "Printer offline"
            ErrorType.NETWORK_ERROR -> "Network connectivity issue"
            ErrorType.AUTHENTICATION_ERROR -> "Authentication failed"
            ErrorType.INVALID_FORMAT -> "Invalid document format"
            ErrorType.INSUFFICIENT_MEMORY -> "Insufficient printer memory"
            ErrorType.HARDWARE_FAILURE -> "Hardware malfunction"
            ErrorType.DRIVER_ERROR -> "Printer driver error"
            ErrorType.TIMEOUT -> "Operation timeout"
            ErrorType.RANDOM_FAILURE -> "Unexpected failure"
            ErrorType.PERFORMANCE_DEGRADATION -> "Performance degraded"
            ErrorType.INTERMITTENT_CONNECTION -> "Intermittent connectivity"
        }
        
        return "$severityText: $errorText"
    }
} 