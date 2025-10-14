package com.example.printer.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.example.printer.logging.LogCategory
import com.example.printer.logging.LogLevel
import com.example.printer.logging.PrinterLogger
import com.example.printer.queue.PrintJob
import com.example.printer.queue.PrintJobState
import com.hp.jipp.encoding.AttributeGroup
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin interface for extending printer behavior
 */
interface PrinterPlugin {
    val id: String
    val name: String
    val version: String
    val description: String
    val author: String
    
    /**
     * Called when the plugin is loaded
     */
    suspend fun onLoad(context: Context): Boolean
    
    /**
     * Called when the plugin is unloaded
     */
    suspend fun onUnload(): Boolean
    
    /**
     * Called before processing a print job
     * @return true to continue processing, false to stop
     */
    suspend fun beforeJobProcessing(job: PrintJob): Boolean = true
    
    /**
     * Called during print job processing
     * @return modified job or null to use original
     */
    suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? = null
    
    /**
     * Called after processing a print job
     */
    suspend fun afterJobProcessing(job: PrintJob, success: Boolean) {}
    
    /**
     * Called when IPP attributes are requested
     * @return custom attributes or null to use defaults
     */
    suspend fun customizeIppAttributes(originalAttributes: List<AttributeGroup>): List<AttributeGroup>? = null
    
    /**
     * Called for custom IPP operations
     * @return true if handled, false to use default handling
     */
    suspend fun handleCustomIppOperation(operation: String, attributes: List<AttributeGroup>): Boolean = false
    
    /**
     * Called when an error occurs
     * @return true if error was handled, false to use default handling
     */
    suspend fun handleError(error: Throwable, context: String): Boolean = false
    
    /**
     * Get plugin configuration UI data
     */
    fun getConfigurationSchema(): PluginConfigurationSchema? = null
    
    /**
     * Update plugin configuration
     */
    suspend fun updateConfiguration(config: Map<String, Any>): Boolean = true
}

/**
 * Job processing result from plugin
 */
data class JobProcessingResult(
    val processedBytes: ByteArray?,
    val modifiedJob: PrintJob?,
    val customMetadata: Map<String, Any> = emptyMap(),
    val shouldContinue: Boolean = true,
    val customResponse: String? = null
)

/**
 * Plugin configuration schema for UI generation
 */
data class PluginConfigurationSchema(
    val fields: List<ConfigurationField>
)

/**
 * Configuration field definition
 */
data class ConfigurationField(
    val key: String,
    val label: String,
    val type: FieldType,
    val defaultValue: Any?,
    val required: Boolean = false,
    val description: String? = null,
    val options: List<String>? = null, // For SELECT type
    val min: Number? = null, // For NUMBER type
    val max: Number? = null  // For NUMBER type
)

/**
 * Configuration field types
 */
enum class FieldType {
    TEXT, NUMBER, BOOLEAN, SELECT, FILE, COLOR
}

/**
 * Plugin metadata for registration
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val className: String,
    val enabled: Boolean = false,
    val loadOrder: Int = 100,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): PluginMetadata {
            return PluginMetadata(
                id = json.getString("id"),
                name = json.getString("name"),
                version = json.getString("version"),
                description = json.getString("description"),
                author = json.getString("author"),
                className = json.getString("className"),
                enabled = json.optBoolean("enabled", false),
                loadOrder = json.optInt("loadOrder", 100),
                dependencies = json.optJSONArray("dependencies")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                permissions = json.optJSONArray("permissions")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList()
            )
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("version", version)
            put("description", description)
            put("author", author)
            put("className", className)
            put("enabled", enabled)
            put("loadOrder", loadOrder)
            put("dependencies", org.json.JSONArray(dependencies))
            put("permissions", org.json.JSONArray(permissions))
        }
    }
}

/**
 * Plugin framework manager
 */
class PluginFramework private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PluginFramework"
        private const val PLUGINS_DIR = "plugins"
        private const val PLUGIN_CONFIG_FILE = "plugin_config.json"
        
        @Volatile
        private var INSTANCE: PluginFramework? = null
        
        fun getInstance(context: Context): PluginFramework {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PluginFramework(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val logger = PrinterLogger.getInstance(context)
    private val loadedPlugins = ConcurrentHashMap<String, PrinterPlugin>()
    private val pluginMetadata = ConcurrentHashMap<String, PluginMetadata>()
    private val pluginConfigurations = ConcurrentHashMap<String, Map<String, Any>>()
    
    private val _availablePlugins = MutableStateFlow<List<PluginMetadata>>(emptyList())
    val availablePlugins: StateFlow<List<PluginMetadata>> = _availablePlugins.asStateFlow()
    
    private val _loadedPluginIds = MutableStateFlow<Set<String>>(emptySet())
    val loadedPluginIds: StateFlow<Set<String>> = _loadedPluginIds.asStateFlow()
    
    init {
        initializeBuiltInPlugins()
        loadPluginMetadata()
    }
    
    /**
     * Initialize built-in plugins
     */
    private fun initializeBuiltInPlugins() {
        // Register all built-in plugins
        val builtInPlugins = listOf(
            PluginMetadata(
                id = "delay_simulator",
                name = "Processing Delay Simulator",
                version = "1.0.0",
                description = "Simulates processing delays for testing slow network or heavy load conditions",
                author = "Built-in",
                className = "DelaySimulatorPlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "error_injection",
                name = "Error Injection",
                version = "1.0.0",
                description = "Injects various errors for testing error handling",
                author = "Built-in",
                className = "ErrorInjectionPlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "document_modifier",
                name = "Document Modifier",
                version = "1.0.0",
                description = "Modifies documents during processing for testing different scenarios",
                author = "Built-in",
                className = "DocumentModifierPlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "attribute_override",
                name = "Attribute Override",
                version = "1.0.0",
                description = "Overrides IPP attributes for testing different printer capabilities",
                author = "Built-in",
                className = "AttributeOverridePlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "logging_enhancer",
                name = "Enhanced Logging",
                version = "1.0.0",
                description = "Adds enhanced logging capabilities for debugging",
                author = "Built-in",
                className = "LoggingEnhancerPlugin",
                enabled = false
            )
        )
        
        builtInPlugins.forEach { metadata ->
            pluginMetadata[metadata.id] = metadata
        }
        
        updateAvailablePlugins()
        
        logger.d(LogCategory.SYSTEM, TAG, "Registered ${builtInPlugins.size} built-in plugins")
    }
    
    /**
     * Load a plugin by ID
     */
    suspend fun loadPlugin(pluginId: String): Boolean {
        val metadata = pluginMetadata[pluginId]
        if (metadata == null) {
            logger.e(LogCategory.SYSTEM, TAG, "Plugin metadata not found: $pluginId")
            return false
        }
        
        if (loadedPlugins.containsKey(pluginId)) {
            logger.w(LogCategory.SYSTEM, TAG, "Plugin already loaded: $pluginId")
            return true
        }
        
        return try {
            // Check dependencies
            if (!checkDependencies(metadata)) {
                logger.e(LogCategory.SYSTEM, TAG, "Plugin dependencies not satisfied: $pluginId")
                return false
            }
            
            // Create plugin instance
            val plugin = createPluginInstance(metadata)
            if (plugin == null) {
                logger.e(LogCategory.SYSTEM, TAG, "Failed to create plugin instance: $pluginId")
                return false
            }
            
            // Load plugin
            val loaded = plugin.onLoad(context)
            if (loaded) {
                loadedPlugins[pluginId] = plugin
                
                // Initialize plugin with default configuration if none exists
                if (!pluginConfigurations.containsKey(pluginId)) {
                    val configSchema = plugin.getConfigurationSchema()
                    if (configSchema != null) {
                        val defaultConfig = mutableMapOf<String, Any>()
                        configSchema.fields.forEach { field ->
                            field.defaultValue?.let { defaultValue ->
                                defaultConfig[field.key] = defaultValue
                            }
                        }
                        if (defaultConfig.isNotEmpty()) {
                            pluginConfigurations[pluginId] = defaultConfig
                            plugin.updateConfiguration(defaultConfig)
                            savePluginConfigurations()
                            logger.d(LogCategory.SYSTEM, TAG, "Initialized plugin ${plugin.name} with default configuration")
                        }
                    }
                } else {
                    // Apply existing configuration
                    val existingConfig = pluginConfigurations[pluginId]!!
                    plugin.updateConfiguration(existingConfig)
                    logger.d(LogCategory.SYSTEM, TAG, "Applied existing configuration to plugin ${plugin.name}")
                }
                
                // Update metadata to enabled
                pluginMetadata[pluginId] = metadata.copy(enabled = true)
                
                updateLoadedPlugins()
                updateAvailablePlugins()
                
                logger.i(LogCategory.SYSTEM, TAG, "Plugin loaded successfully: ${plugin.name}")
                return true
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Plugin failed to load: ${plugin.name}")
                return false
            }
            
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error loading plugin: $pluginId", e)
            false
        }
    }
    
    /**
     * Unload a plugin by ID
     */
    suspend fun unloadPlugin(pluginId: String): Boolean {
        val plugin = loadedPlugins[pluginId]
        if (plugin == null) {
            logger.w(LogCategory.SYSTEM, TAG, "Plugin not loaded: $pluginId")
            return false
        }
        
        return try {
            val unloaded = plugin.onUnload()
            if (unloaded) {
                loadedPlugins.remove(pluginId)
                
                // Update metadata to disabled
                pluginMetadata[pluginId]?.let { metadata ->
                    pluginMetadata[pluginId] = metadata.copy(enabled = false)
                }
                
                updateLoadedPlugins()
                updateAvailablePlugins()
                
                logger.i(LogCategory.SYSTEM, TAG, "Plugin unloaded successfully: ${plugin.name}")
                return true
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Plugin failed to unload: ${plugin.name}")
                return false
            }
            
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error unloading plugin: $pluginId", e)
            false
        }
    }
    
    /**
     * Execute plugin hooks for job processing (before)
     */
    suspend fun executeBeforeJobProcessing(job: PrintJob): Boolean {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val shouldContinue = plugin.beforeJobProcessing(job)
                if (!shouldContinue) {
                    logger.i(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} stopped job processing for job ${job.id}")
                    return false
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} beforeJobProcessing", e)
            }
        }
        
        return true
    }
    
    /**
     * Execute plugin hooks for job processing
     */
    suspend fun executeJobProcessing(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val result = plugin.processJob(job, documentBytes)
                if (result != null) {
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} processed job ${job.id}")
                    return result
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} processJob", e)
            }
        }
        
        return null
    }
    
    /**
     * Execute plugin hooks for job processing (after)
     */
    suspend fun executeAfterJobProcessing(job: PrintJob, success: Boolean) {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                plugin.afterJobProcessing(job, success)
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} afterJobProcessing", e)
            }
        }
    }
    
    /**
     * Execute plugin hooks for IPP attribute customization
     */
    suspend fun executeIppAttributeCustomization(originalAttributes: List<AttributeGroup>): List<AttributeGroup> {
        var currentAttributes = originalAttributes
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val customAttributes = plugin.customizeIppAttributes(currentAttributes)
                if (customAttributes != null) {
                    currentAttributes = customAttributes
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} customized IPP attributes")
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} customizeIppAttributes", e)
            }
        }
        
        return currentAttributes
    }
    
    /**
     * Execute plugin hooks for custom IPP operations
     */
    suspend fun executeCustomIppOperation(operation: String, attributes: List<AttributeGroup>): Boolean {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val handled = plugin.handleCustomIppOperation(operation, attributes)
                if (handled) {
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} handled custom IPP operation: $operation")
                    return true
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} handleCustomIppOperation", e)
            }
        }
        
        return false
    }
    
    /**
     * Execute plugin hooks for error handling
     */
    suspend fun executeErrorHandling(error: Throwable, context: String): Boolean {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val handled = plugin.handleError(error, context)
                if (handled) {
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} handled error in context: $context")
                    return true
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} handleError", e)
            }
        }
        
        return false
    }
    
    /**
     * Get plugin configuration
     */
    fun getPluginConfiguration(pluginId: String): Map<String, Any>? {
        return pluginConfigurations[pluginId]
    }
    
    /**
     * Update plugin configuration
     */
    suspend fun updatePluginConfiguration(pluginId: String, config: Map<String, Any>): Boolean {
        val plugin = loadedPlugins[pluginId]
        if (plugin == null) {
            logger.w(LogCategory.SYSTEM, TAG, "Cannot configure unloaded plugin: $pluginId")
            return false
        }
        
        return try {
            val updated = plugin.updateConfiguration(config)
            if (updated) {
                pluginConfigurations[pluginId] = config
                savePluginConfigurations()
                logger.i(LogCategory.SYSTEM, TAG, "Updated configuration for plugin: ${plugin.name}")
            }
            updated
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error updating plugin configuration: $pluginId", e)
            false
        }
    }
    
    /**
     * Get plugin by ID
     */
    fun getPlugin(pluginId: String): PrinterPlugin? {
        return loadedPlugins[pluginId]
    }
    
    /**
     * Get all loaded plugins
     */
    fun getLoadedPlugins(): List<PrinterPlugin> {
        return loadedPlugins.values.toList()
    }
    
    // Private helper methods
    
    private fun createPluginInstance(metadata: PluginMetadata): PrinterPlugin? {
        return when (metadata.id) {
            "delay_simulator" -> DelaySimulatorPlugin()
            "error_injection" -> ErrorInjectionPlugin()
            "document_modifier" -> DocumentModifierPlugin()
            "attribute_override" -> AttributeOverridePlugin()
            "logging_enhancer" -> LoggingEnhancerPlugin()
            else -> {
                // For external plugins, would use class loading here
                logger.w(LogCategory.SYSTEM, TAG, "Unknown plugin class: ${metadata.className}")
                null
            }
        }
    }
    
    private fun checkDependencies(metadata: PluginMetadata): Boolean {
        return metadata.dependencies.all { dependency ->
            loadedPlugins.containsKey(dependency)
        }
    }
    
    private fun getSortedLoadedPlugins(): List<PrinterPlugin> {
        return loadedPlugins.values.sortedBy { plugin ->
            pluginMetadata[plugin.id]?.loadOrder ?: 100
        }
    }
    
    private fun updateAvailablePlugins() {
        _availablePlugins.value = pluginMetadata.values.sortedBy { it.name }
    }
    
    private fun updateLoadedPlugins() {
        _loadedPluginIds.value = loadedPlugins.keys.toSet()
    }
    
    private fun loadPluginMetadata() {
        try {
            val configFile = File(context.filesDir, PLUGIN_CONFIG_FILE)
            if (configFile.exists()) {
                val jsonContent = configFile.readText()
                val jsonObject = JSONObject(jsonContent)
                
                // Load configurations
                val configsJson = jsonObject.optJSONObject("configurations")
                configsJson?.let { configs ->
                    configs.keys().forEach { pluginId ->
                        val configJson = configs.getJSONObject(pluginId)
                        val configMap = mutableMapOf<String, Any>()
                        configJson.keys().forEach { key ->
                            configMap[key] = configJson.get(key)
                        }
                        pluginConfigurations[pluginId] = configMap
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error loading plugin metadata", e)
        }
    }
    
    private fun savePluginConfigurations() {
        try {
            val configFile = File(context.filesDir, PLUGIN_CONFIG_FILE)
            val jsonObject = JSONObject()
            
            // Save configurations
            val configsJson = JSONObject()
            pluginConfigurations.forEach { (pluginId, config) ->
                val configJson = JSONObject(config)
                configsJson.put(pluginId, configJson)
            }
            jsonObject.put("configurations", configsJson)
            
            configFile.writeText(jsonObject.toString(2))
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error saving plugin configurations", e)
        }
    }
}

// Built-in plugin implementations

/**
 * Plugin that adds configurable delays to job processing
 */
class DelaySimulatorPlugin : PrinterPlugin {
    override val id = "delay_simulator"
    override val name = "Processing Delay Simulator"
    override val version = "1.0.0"
    override val description = "Simulates processing delays for testing slow network or heavy load conditions"
    override val author = "Built-in"
    
    private var delayMs: Long = 1000L
    private var randomDelay: Boolean = false
    private var lastCalculatedDelay: Long = 0L // Store the last delay for consistent reporting
    
    override suspend fun onLoad(context: Context): Boolean = true
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun beforeJobProcessing(job: PrintJob): Boolean {
        // Calculate delay once and store it
        lastCalculatedDelay = if (randomDelay) {
            (delayMs * 0.5 + Math.random() * delayMs).toLong()
        } else {
            delayMs
        }
        
        kotlinx.coroutines.delay(lastCalculatedDelay)
        
        return true // Continue processing
    }
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        // Return the same delay that was actually used
        return JobProcessingResult(
            processedBytes = null,
            modifiedJob = null,
            customMetadata = mapOf("simulated_delay_ms" to lastCalculatedDelay)
        )
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField("delay_ms", "Delay (milliseconds)", FieldType.NUMBER, 1000L, min = 0, max = 30000),
                ConfigurationField("random_delay", "Random delay", FieldType.BOOLEAN, false)
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        delayMs = (config["delay_ms"] as? Number)?.toLong() ?: 1000L
        randomDelay = config["random_delay"] as? Boolean ?: false
        return true
    }
}

/**
 * Plugin that injects various errors for testing error handling and system resilience
 */
class ErrorInjectionPlugin : PrinterPlugin {
    override val id = "error_injection"
    override val name = "Error Injection"
    override val version = "1.0.0"
    override val description = "Injects various errors for testing error handling and system resilience"
    override val author = "Built-in"
    
    private var errorProbability: Float = 0.1f
    private var errorTypes: List<String> = listOf("network", "memory", "format")
    private var errorMode: String = "random" // random, sequential, specific
    private var specificErrorType: String = "network"
    private var currentErrorIndex = 0
    private var lastInjectedError: String? = null
    
    // Predefined error scenarios
    private val errorScenarios = mapOf(
        "network" to listOf(
            "Connection timeout - simulated network failure",
            "Socket connection refused - printer unreachable", 
            "Network congestion - packet loss detected",
            "DNS resolution failed - printer hostname unknown"
        ),
        "memory" to listOf(
            "Out of memory - insufficient heap space for job processing",
            "Memory allocation failed - large document processing error",
            "Buffer overflow - document size exceeds memory limits"
        ),
        "format" to listOf(
            "Unsupported document format - invalid file structure",
            "Corrupted document data - parsing failed",
            "Invalid IPP attributes - malformed request data",
            "Document compression error - decompression failed"
        ),
        "hardware" to listOf(
            "Printer jam detected - paper feed mechanism blocked",
            "Low ink/toner levels - insufficient supplies",
            "Hardware malfunction - print head alignment error",
            "Temperature sensor error - printer overheating"
        ),
        "authorization" to listOf(
            "Authentication failed - invalid credentials",
            "Access denied - insufficient permissions",
            "Session expired - re-authentication required",
            "Account locked - too many failed attempts"
        ),
        "queue" to listOf(
            "Print queue full - maximum jobs exceeded",
            "Job priority conflict - queue management error",
            "Duplicate job ID - processing conflict detected"
        )
    )
    
    override suspend fun onLoad(context: Context): Boolean = true
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun beforeJobProcessing(job: PrintJob): Boolean {
        if (shouldInjectError()) {
            val errorDetails = getNextError()
            lastInjectedError = errorDetails.first
            
            // Log the error injection for debugging
            android.util.Log.w("ErrorInjectionPlugin", 
                "Injecting ${errorDetails.first} error: ${errorDetails.second}")
            
            throw createErrorException(errorDetails.first, errorDetails.second)
        }
        return true
    }
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        // Return metadata about error injection for testing
        return JobProcessingResult(
            processedBytes = null,
            modifiedJob = null,
            customMetadata = mapOf(
                "error_probability" to errorProbability,
                "error_mode" to errorMode,
                "available_error_types" to errorTypes.joinToString(","),
                "last_injected_error" to (lastInjectedError ?: "none")
            )
        )
    }
    
    private fun shouldInjectError(): Boolean {
        return Math.random() < errorProbability
    }
    
    private fun getNextError(): Pair<String, String> {
        val errorType = when (errorMode) {
            "sequential" -> {
                val type = errorTypes[currentErrorIndex % errorTypes.size]
                currentErrorIndex++
                type
            }
            "specific" -> specificErrorType
            else -> errorTypes.random() // "random" mode
        }
        
        val scenarios = errorScenarios[errorType] ?: listOf("Generic $errorType error")
        val errorMessage = scenarios.random()
        
        return Pair(errorType, errorMessage)
    }
    
    private fun createErrorException(errorType: String, message: String): Exception {
        return when (errorType) {
            "network" -> java.net.ConnectException("Network Error: $message")
            "memory" -> RuntimeException("Memory Error: $message") // OutOfMemoryError is not Exception subclass
            "format" -> IllegalArgumentException("Format Error: $message") 
            "hardware" -> RuntimeException("Hardware Error: $message")
            "authorization" -> SecurityException("Authorization Error: $message")
            "queue" -> IllegalStateException("Queue Error: $message")
            else -> RuntimeException("$errorType Error: $message")
        }
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "error_probability",
                    label = "Error Probability",
                    type = FieldType.NUMBER,
                    defaultValue = 0.1,
                    min = 0.0,
                    max = 1.0,
                    description = "Probability of error injection (0.0 to 1.0)"
                ),
                ConfigurationField(
                    key = "error_mode",
                    label = "Error Mode",
                    type = FieldType.SELECT,
                    defaultValue = "random",
                    options = listOf("random", "sequential", "specific"),
                    description = "How errors are selected: random, sequential rotation, or specific type"
                ),
                ConfigurationField(
                    key = "error_types",
                    label = "Error Types",
                    type = FieldType.TEXT,
                    defaultValue = "network,memory,format",
                    description = "Comma-separated list: network, memory, format, hardware, authorization, queue"
                ),
                ConfigurationField(
                    key = "specific_error_type",
                    label = "Specific Error Type",
                    type = FieldType.SELECT,
                    defaultValue = "network",
                    options = listOf("network", "memory", "format", "hardware", "authorization", "queue"),
                    description = "Error type to use when mode is 'specific'"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        errorProbability = (config["error_probability"] as? Number)?.toFloat() ?: 0.1f
        errorMode = config["error_mode"] as? String ?: "random"
        specificErrorType = config["specific_error_type"] as? String ?: "network"
        errorTypes = (config["error_types"] as? String)?.split(",")?.map { it.trim() } 
            ?: listOf("network", "memory", "format")
        
        // Reset sequential counter when configuration changes
        currentErrorIndex = 0
        
        return true
    }
}

/**
 * Plugin that modifies documents during processing by adding watermarks
 */
class DocumentModifierPlugin : PrinterPlugin {
    override val id = "document_modifier"
    override val name = "Document Modifier"
    override val version = "1.0.0"
    override val description = "Adds watermarks to documents (PDF and images) for testing and identification"
    override val author = "Built-in"
    
    @Volatile private var addWatermark: Boolean = false
    @Volatile private var watermarkText: String = "TEST"
    @Volatile private var watermarkOpacity: Float = 0.3f
    @Volatile private var watermarkSize: Int = 48
    @Volatile private var pdfBoxInitialized: Boolean = false
    
    override suspend fun onLoad(context: Context): Boolean {
        // Initialize PdfBox for Android (only once)
        if (!pdfBoxInitialized) {
            try {
                PDFBoxResourceLoader.init(context)
                pdfBoxInitialized = true
                Log.d("DocumentModifierPlugin", "PdfBox initialized successfully")
            } catch (e: Exception) {
                Log.e("DocumentModifierPlugin", "Failed to initialize PdfBox", e)
                return false
            }
        }
        return true
    }
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        if (!addWatermark) return null
        
        return try {
            // Detect document type
            val documentType = com.example.printer.utils.DocumentTypeUtils.detectDocumentType(documentBytes)
            
            val modifiedBytes = when (documentType) {
                com.example.printer.utils.DocumentType.PDF -> addWatermarkToPdf(documentBytes)
                com.example.printer.utils.DocumentType.JPEG,
                com.example.printer.utils.DocumentType.PNG -> addWatermarkToImage(documentBytes)
                else -> {
                    Log.d("DocumentModifierPlugin", "Watermarking not supported for type: $documentType")
                    null
                }
            }
            
            if (modifiedBytes != null) {
                val modifiedJob = job.copy(
                    metadata = job.metadata + mapOf(
                        "watermark" to watermarkText,
                        "watermark_applied" to true
                    )
                )
                
                JobProcessingResult(
                    processedBytes = modifiedBytes,
                    modifiedJob = modifiedJob,
                    customMetadata = mapOf(
                        "watermark_added" to true,
                        "watermark_text" to watermarkText,
                        "document_type" to documentType.name
                    )
                )
            } else {
                // Return metadata only if watermarking failed
                val modifiedJob = job.copy(
                    metadata = job.metadata + mapOf("watermark_attempted" to true)
                )
                JobProcessingResult(
                    processedBytes = null,
                    modifiedJob = modifiedJob,
                    customMetadata = mapOf("watermark_added" to false)
                )
            }
        } catch (e: Exception) {
            Log.e("DocumentModifierPlugin", "Error adding watermark", e)
            null
        }
    }
    
    /**
     * Add watermark to PDF document using PdfBox
     */
    private fun addWatermarkToPdf(pdfBytes: ByteArray): ByteArray? {
        // Input validation
        if (pdfBytes.isEmpty()) {
            Log.e("DocumentModifierPlugin", "PDF bytes are empty")
            return null
        }
        
        if (watermarkText.isBlank()) {
            Log.e("DocumentModifierPlugin", "Watermark text is blank")
            return null
        }
        
        // Validate configuration ranges
        val validOpacity = watermarkOpacity.coerceIn(0.1f, 1.0f)
        val validSize = watermarkSize.coerceIn(12, 144)
        
        return try {
            // Load the PDF document with automatic resource management
            val inputStream = ByteArrayInputStream(pdfBytes)
            val outputStream = ByteArrayOutputStream()
            
            PDDocument.load(inputStream).use { document ->
                // Create content stream for watermark overlay
                val font = PDType1Font.HELVETICA_BOLD
                
                // Create Extended Graphics State for opacity
                val graphicsState = PDExtendedGraphicsState()
                graphicsState.nonStrokingAlphaConstant = validOpacity
                graphicsState.strokingAlphaConstant = validOpacity
                
                // Iterate through all pages and add watermark
                for (page in document.pages) {
                    // Use try-finally to ensure contentStream is always closed
                    var contentStream: PDPageContentStream? = null
                    try {
                        contentStream = PDPageContentStream(
                            document,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )
                        
                        // Get page dimensions
                        val mediaBox = page.mediaBox
                        val pageWidth = mediaBox.width
                        val pageHeight = mediaBox.height
                        
                        // Apply graphics state for opacity
                        contentStream.setGraphicsStateParameters(graphicsState)
                        
                        // Set watermark properties
                        contentStream.beginText()
                        contentStream.setFont(font, validSize.toFloat())
                        
                        // Set color (gray)
                        contentStream.setNonStrokingColor(
                            128f / 255f,
                            128f / 255f, 
                            128f / 255f
                        )
                        
                        // Calculate center position
                        val centerX = pageWidth / 2
                        val centerY = pageHeight / 2
                        
                        // Create transformation matrix for rotation and positioning
                        // 1. Translate to center
                        // 2. Rotate -45 degrees
                        // 3. The text will be drawn at the origin of this transformed coordinate system
                        val matrix = Matrix()
                        matrix.translate(centerX, centerY)
                        matrix.rotate(Math.toRadians(-45.0))
                        
                        // Adjust for text centering with proper precision
                        val textWidth = font.getStringWidth(watermarkText) / 1000.0f * validSize
                        matrix.translate(-textWidth / 2, 0f)
                        
                        contentStream.setTextMatrix(matrix)
                        
                        // Draw watermark text
                        contentStream.showText(watermarkText)
                        contentStream.endText()
                    } finally {
                        // Always close the content stream
                        contentStream?.close()
                    }
                }
                
                // Save modified PDF to byte array
                document.save(outputStream)
                
                Log.d("DocumentModifierPlugin", "Successfully watermarked PDF with ${document.numberOfPages} pages")
            } // document is automatically closed here
            
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("DocumentModifierPlugin", "Error watermarking PDF", e)
            null
        }
    }
    
    /**
     * Add watermark to image document
     */
    private fun addWatermarkToImage(imageBytes: ByteArray): ByteArray? {
        // Input validation
        if (imageBytes.isEmpty()) {
            Log.e("DocumentModifierPlugin", "Image bytes are empty")
            return null
        }
        
        if (watermarkText.isBlank()) {
            Log.e("DocumentModifierPlugin", "Watermark text is blank")
            return null
        }
        
        // Validate configuration ranges
        val validOpacity = watermarkOpacity.coerceIn(0.1f, 1.0f)
        val validSize = watermarkSize.coerceIn(12, 144)
        
        var originalBitmap: Bitmap? = null
        var mutableBitmap: Bitmap? = null
        
        return try {
            // Decode the image
            originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (originalBitmap == null) {
                Log.e("DocumentModifierPlugin", "Failed to decode image")
                return null
            }
            
            // Create a mutable copy
            mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (mutableBitmap == null) {
                Log.e("DocumentModifierPlugin", "Failed to create mutable bitmap copy")
                return null
            }
            
            val canvas = Canvas(mutableBitmap)
            
            // Create watermark paint with validated values
            val paint = Paint().apply {
                color = Color.argb((validOpacity * 255).toInt(), 255, 255, 255)
                textSize = validSize.toFloat()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                setShadowLayer(2f, 2f, 2f, Color.BLACK)
            }
            
            // Draw watermark diagonally
            canvas.save()
            val centerX = mutableBitmap.width / 2f
            val centerY = mutableBitmap.height / 2f
            canvas.rotate(-45f, centerX, centerY)
            canvas.drawText(watermarkText, centerX, centerY, paint)
            canvas.restore()
            
            // Detect original format and preserve it
            val outputStream = ByteArrayOutputStream()
            val format = when {
                imageBytes.size >= 2 && 
                imageBytes[0] == 0xFF.toByte() && 
                imageBytes[1] == 0xD8.toByte() -> {
                    // JPEG signature
                    Bitmap.CompressFormat.JPEG
                }
                else -> {
                    // Default to PNG for PNG and other formats
                    Bitmap.CompressFormat.PNG
                }
            }
            
            val quality = if (format == Bitmap.CompressFormat.JPEG) 90 else 100
            mutableBitmap.compress(format, quality, outputStream)
            
            Log.d("DocumentModifierPlugin", "Successfully watermarked image (format: $format)")
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("DocumentModifierPlugin", "Error watermarking image", e)
            null
        } finally {
            // Always clean up bitmaps to prevent memory leaks
            originalBitmap?.recycle()
            mutableBitmap?.recycle()
        }
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "add_watermark",
                    label = "Enable Watermark",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Add watermark to PDF and image documents"
                ),
                ConfigurationField(
                    key = "watermark_text",
                    label = "Watermark Text",
                    type = FieldType.TEXT,
                    defaultValue = "TEST",
                    description = "Text to display as watermark"
                ),
                ConfigurationField(
                    key = "watermark_opacity",
                    label = "Watermark Opacity",
                    type = FieldType.NUMBER,
                    defaultValue = 0.3,
                    min = 0.1,
                    max = 1.0,
                    description = "Opacity of watermark (0.1 = very transparent, 1.0 = solid)"
                ),
                ConfigurationField(
                    key = "watermark_size",
                    label = "Watermark Size",
                    type = FieldType.NUMBER,
                    defaultValue = 48,
                    min = 12,
                    max = 144,
                    description = "Font size of watermark text in points"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        addWatermark = config["add_watermark"] as? Boolean ?: false
        watermarkText = config["watermark_text"] as? String ?: "TEST"
        watermarkOpacity = (config["watermark_opacity"] as? Number)?.toFloat() ?: 0.3f
        watermarkSize = (config["watermark_size"] as? Number)?.toInt() ?: 48
        return true
    }
}

/**
 * Plugin that overrides IPP attributes
 */
class AttributeOverridePlugin : PrinterPlugin {
    override val id = "attribute_override"
    override val name = "Attribute Override"
    override val version = "1.0.0"
    override val description = "Overrides IPP attributes for testing different printer capabilities"
    override val author = "Built-in"
    
    private var printerName: String = "Custom Virtual Printer"
    private var maxJobs: Int = 100
    private var colorSupported: Boolean = true
    private var duplexSupported: Boolean = false
    
    override suspend fun onLoad(context: Context): Boolean = true
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun customizeIppAttributes(originalAttributes: List<AttributeGroup>): List<AttributeGroup>? {
        // Could modify attributes here based on configuration
        return null
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "printer_name",
                    label = "Printer Name",
                    type = FieldType.TEXT,
                    defaultValue = "Custom Virtual Printer",
                    description = "Override the printer name shown to clients"
                ),
                ConfigurationField(
                    key = "max_jobs",
                    label = "Maximum Jobs",
                    type = FieldType.NUMBER,
                    defaultValue = 100,
                    description = "Maximum number of queued jobs",
                    min = 1,
                    max = 1000
                ),
                ConfigurationField(
                    key = "color_supported",
                    label = "Color Support",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Whether to advertise color printing support"
                ),
                ConfigurationField(
                    key = "duplex_supported",
                    label = "Duplex Support",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Whether to advertise duplex printing support"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        printerName = config["printer_name"] as? String ?: "Custom Virtual Printer"
        maxJobs = (config["max_jobs"] as? Number)?.toInt() ?: 100
        colorSupported = config["color_supported"] as? Boolean ?: true
        duplexSupported = config["duplex_supported"] as? Boolean ?: false
        return true
    }
}

/**
 * Plugin that enhances logging
 * 
 * This plugin provides advanced logging capabilities for debugging and monitoring:
 * - File-based logging with automatic rotation
 * - Performance metrics tracking (job processing time, throughput)
 * - Detailed job information logging
 * - Configurable log levels
 * - Log file management and export
 * 
 * Log files are stored in the app's cache directory and automatically rotated
 * when they exceed 10MB to prevent excessive storage usage.
 */
class LoggingEnhancerPlugin : PrinterPlugin {
    override val id = "logging_enhancer"
    override val name = "Enhanced Logging"
    override val version = "1.0.0"
    override val description = "Adds enhanced logging capabilities for debugging"
    override val author = "Built-in"
    
    @Volatile private var logLevel: String = "DEBUG"
    @Volatile private var logToFile: Boolean = true
    @Volatile private var logJobDetails: Boolean = true
    @Volatile private var logPerformance: Boolean = false
    
    private var logFile: java.io.File? = null
    private var logWriter: java.io.BufferedWriter? = null
    private val logLock = Any()
    
    // Performance tracking
    private val jobStartTimes = mutableMapOf<Long, Long>()
    private val performanceStats = mutableMapOf<String, MutableList<Long>>()
    
    companion object {
        private const val TAG = "LoggingEnhancerPlugin"
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val LOG_FILE_NAME = "printer_enhanced_log.txt"
    }
    
    override suspend fun onLoad(context: Context): Boolean {
        if (logToFile) {
            initializeLogFile(context)
        }
        writeLog("INFO", "Enhanced Logging Plugin loaded - file logging: $logToFile, performance: $logPerformance")
        return true
    }
    
    override suspend fun onUnload(): Boolean {
        writeLog("INFO", "Enhanced Logging Plugin unloading")
        closeLogFile()
        return true
    }
    
    override suspend fun beforeJobProcessing(job: PrintJob): Boolean {
        val timestamp = System.currentTimeMillis()
        
        if (logJobDetails) {
            val message = buildString {
                append("▶ Job Started: ${job.id}")
                append("\n  Name: ${job.name}")
                append("\n  Status: ${job.status}")
                append("\n  Pages: ${job.totalPages}")
                append("\n  Copies: ${job.copies}")
                append("\n  Format: ${job.documentFormat}")
                append("\n  Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(job.createdAt))}")
                if (job.metadata.isNotEmpty()) {
                    append("\n  Metadata: ${job.metadata}")
                }
            }
            writeLog("INFO", message)
        }
        
        if (logPerformance) {
            synchronized(jobStartTimes) {
                jobStartTimes[job.id] = timestamp
            }
            writeLog("DEBUG", "Performance tracking started for job ${job.id}")
        }
        
        return true
    }
    
    override suspend fun afterJobProcessing(job: PrintJob, success: Boolean) {
        if (logJobDetails) {
            val statusIcon = if (success) "✓" else "✗"
            val message = buildString {
                append("$statusIcon Job ${if (success) "Completed" else "Failed"}: ${job.id}")
                append("\n  Name: ${job.name}")
                append("\n  Status: ${job.status}")
                append("\n  Success: $success")
            }
            writeLog(if (success) "INFO" else "ERROR", message)
        }
        
        if (logPerformance) {
            val startTime = synchronized(jobStartTimes) {
                jobStartTimes.remove(job.id)
            }
            
            if (startTime != null) {
                val duration = System.currentTimeMillis() - startTime
                val durationSeconds = duration / 1000.0
                
                // Track statistics
                synchronized(performanceStats) {
                    performanceStats.getOrPut("processing_times") { mutableListOf() }.add(duration)
                }
                
                writeLog("INFO", "⏱ Performance Metrics for job ${job.id}:")
                writeLog("INFO", "  Processing time: ${durationSeconds}s (${duration}ms)")
                writeLog("INFO", "  Document size: ${job.documentBytes?.size ?: 0} bytes")
                
                if (job.documentBytes != null && job.documentBytes!!.isNotEmpty()) {
                    val throughput = job.documentBytes!!.size / durationSeconds
                    writeLog("INFO", "  Throughput: ${formatBytes(throughput.toLong())}/s")
                }
                
                // Log aggregate statistics every 10 jobs
                val processingTimes = synchronized(performanceStats) {
                    performanceStats["processing_times"]?.toList() ?: emptyList()
                }
                
                if (processingTimes.size % 10 == 0 && processingTimes.isNotEmpty()) {
                    val avgTime = processingTimes.average()
                    val minTime = processingTimes.minOrNull() ?: 0
                    val maxTime = processingTimes.maxOrNull() ?: 0
                    
                    writeLog("INFO", "📊 Aggregate Performance Statistics (last ${processingTimes.size} jobs):")
                    writeLog("INFO", "  Average: ${avgTime / 1000.0}s")
                    writeLog("INFO", "  Min: ${minTime / 1000.0}s")
                    writeLog("INFO", "  Max: ${maxTime / 1000.0}s")
                }
            }
        }
    }
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        // This plugin doesn't modify jobs, just logs them
        return null
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "log_level",
                    label = "Log Level",
                    type = FieldType.SELECT,
                    defaultValue = "DEBUG",
                    description = "Minimum log level to capture",
                    options = listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR")
                ),
                ConfigurationField(
                    key = "log_to_file",
                    label = "Log to File",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Save logs to file for later analysis (stored in app cache)"
                ),
                ConfigurationField(
                    key = "log_job_details",
                    label = "Log Job Details",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Log detailed information about each job"
                ),
                ConfigurationField(
                    key = "log_performance",
                    label = "Log Performance Metrics",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Log performance timing, throughput, and statistics"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        val oldLogToFile = logToFile
        
        logLevel = config["log_level"] as? String ?: "DEBUG"
        logToFile = config["log_to_file"] as? Boolean ?: true
        logJobDetails = config["log_job_details"] as? Boolean ?: true
        logPerformance = config["log_performance"] as? Boolean ?: false
        
        // Reinitialize log file if setting changed
        if (logToFile != oldLogToFile) {
            if (logToFile) {
                // Will be initialized on next write
                writeLog("INFO", "File logging enabled")
            } else {
                writeLog("INFO", "File logging disabled")
                closeLogFile()
            }
        }
        
        writeLog("DEBUG", "Configuration updated: level=$logLevel, toFile=$logToFile, jobDetails=$logJobDetails, performance=$logPerformance")
        
        return true
    }
    
    /**
     * Initialize log file in app's cache directory
     */
    private fun initializeLogFile(context: Context) {
        try {
            synchronized(logLock) {
                // Close existing file if open
                logWriter?.close()
                
                // Create log file in cache directory
                val cacheDir = context.cacheDir
                logFile = java.io.File(cacheDir, LOG_FILE_NAME)
                
                // Check if rotation is needed
                if (logFile!!.exists() && logFile!!.length() > MAX_LOG_FILE_SIZE) {
                    rotateLogFile()
                }
                
                // Open writer in append mode
                logWriter = java.io.BufferedWriter(
                    java.io.FileWriter(logFile, true)
                )
                
                Log.d(TAG, "Log file initialized: ${logFile!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log file", e)
            logWriter = null
            logFile = null
        }
    }
    
    /**
     * Rotate log file when it gets too large
     */
    private fun rotateLogFile() {
        try {
            val oldFile = java.io.File(logFile!!.parent, "$LOG_FILE_NAME.old")
            if (oldFile.exists()) {
                oldFile.delete()
            }
            logFile!!.renameTo(oldFile)
            Log.d(TAG, "Log file rotated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }
    
    /**
     * Write log entry to both Android log and file
     */
    private fun writeLog(level: String, message: String) {
        // Write to Android log
        when (level) {
            "VERBOSE" -> Log.v(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            "INFO" -> Log.i(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message)
        }
        
        // Write to file if enabled
        if (logToFile) {
            try {
                synchronized(logLock) {
                    val timestamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US
                    ).format(java.util.Date())
                    
                    val logEntry = "[$timestamp] [$level] $message\n"
                    
                    logWriter?.write(logEntry)
                    logWriter?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }
    }
    
    /**
     * Close log file writer
     */
    private fun closeLogFile() {
        try {
            synchronized(logLock) {
                logWriter?.flush()
                logWriter?.close()
                logWriter = null
                logFile = null
            }
            Log.d(TAG, "Log file closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing log file", e)
        }
    }
    
    /**
     * Format bytes to human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}