package com.example.printer.attributes

import android.util.Log
import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Validation result for IPP attributes
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError> = emptyList(),
    val warnings: List<ValidationWarning> = emptyList(),
    val suggestions: List<ValidationSuggestion> = emptyList()
)

/**
 * Validation error with severity and location
 */
data class ValidationError(
    val code: String,
    val message: String,
    val severity: ErrorSeverity,
    val attributeName: String? = null,
    val groupTag: String? = null,
    val suggestedFix: String? = null
)

/**
 * Validation warning
 */
data class ValidationWarning(
    val code: String,
    val message: String,
    val attributeName: String? = null,
    val recommendation: String? = null
)

/**
 * Validation suggestion for improvement
 */
data class ValidationSuggestion(
    val code: String,
    val message: String,
    val attributeName: String? = null,
    val improvement: String? = null
)

/**
 * Error severity levels
 */
enum class ErrorSeverity {
    CRITICAL,   // Will cause IPP client failures
    HIGH,       // May cause compatibility issues
    MEDIUM,     // Best practice violations
    LOW         // Minor improvements
}

/**
 * Attribute validation categories
 */
enum class ValidationCategory {
    REQUIRED_ATTRIBUTES,
    ATTRIBUTE_SYNTAX,
    VALUE_CONSTRAINTS,
    COMPATIBILITY,
    PERFORMANCE,
    SECURITY
}

/**
 * Real-time IPP attribute validator with comprehensive checks
 */
class AttributeValidator private constructor() {
    companion object {
        private const val TAG = "AttributeValidator"
        
        @Volatile
        private var INSTANCE: AttributeValidator? = null
        
        fun getInstance(): AttributeValidator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AttributeValidator().also { INSTANCE = it }
            }
        }
    }
    
    private val _validationResults = MutableStateFlow<ValidationResult?>(null)
    val validationResults: StateFlow<ValidationResult?> = _validationResults.asStateFlow()
    
    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating.asStateFlow()
    
    /**
     * Required IPP attributes for printer operation
     */
    private val requiredPrinterAttributes = setOf(
        "printer-uri-supported",
        "uri-authentication-supported", 
        "uri-security-supported",
        "printer-name",
        "printer-state",
        "printer-state-reasons",
        "ipp-versions-supported",
        "operations-supported",
        "charset-configured",
        "charset-supported",
        "natural-language-configured",
        "generated-natural-language-supported",
        "document-format-default",
        "document-format-supported",
        "printer-is-accepting-jobs",
        "queued-job-count",
        "printer-up-time",
        "compression-supported",
        "pdl-override-supported"
    )
    
    /**
     * Recommended attributes for better compatibility
     */
    private val recommendedAttributes = setOf(
        "color-supported",
        "pages-per-minute",
        "pages-per-minute-color",
        "printer-make-and-model",
        "printer-more-info",
        "printer-location",
        "printer-info",
        "media-default",
        "media-supported",
        "media-ready",
        "sides-default",
        "sides-supported",
        "orientation-requested-default",
        "orientation-requested-supported",
        "copies-default",
        "copies-supported",
        "finishings-default",
        "finishings-supported"
    )
    
    /**
     * Validate IPP attributes in real-time
     */
    suspend fun validateAttributes(attributes: List<AttributeGroup>): ValidationResult {
        _isValidating.value = true
        
        try {
            Log.d(TAG, "Starting validation of ${attributes.size} attribute groups")
            
            val errors = mutableListOf<ValidationError>()
            val warnings = mutableListOf<ValidationWarning>()
            val suggestions = mutableListOf<ValidationSuggestion>()
            
            // Validate each category
            validateRequiredAttributes(attributes, errors)
            validateAttributeSyntax(attributes, errors, warnings)
            validateValueConstraints(attributes, errors, warnings)
            validateCompatibility(attributes, warnings, suggestions)
            validatePerformanceConsiderations(attributes, suggestions)
            validateSecurity(attributes, warnings, suggestions)
            
            val result = ValidationResult(
                isValid = errors.none { it.severity == ErrorSeverity.CRITICAL },
                errors = errors,
                warnings = warnings,
                suggestions = suggestions
            )
            
            _validationResults.value = result
            
            Log.d(TAG, "Validation completed: ${errors.size} errors, ${warnings.size} warnings, ${suggestions.size} suggestions")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during validation", e)
            val errorResult = ValidationResult(
                isValid = false,
                errors = listOf(
                    ValidationError(
                        code = "VALIDATION_EXCEPTION",
                        message = "Internal validation error: ${e.message}",
                        severity = ErrorSeverity.CRITICAL
                    )
                )
            )
            _validationResults.value = errorResult
            return errorResult
            
        } finally {
            _isValidating.value = false
        }
    }
    
    /**
     * Validate required attributes are present
     */
    private fun validateRequiredAttributes(
        attributes: List<AttributeGroup>,
        errors: MutableList<ValidationError>
    ) {
        val printerGroup = attributes.find { it.tag == Tag.printerAttributes }
        if (printerGroup == null) {
            errors.add(
                ValidationError(
                    code = "MISSING_PRINTER_GROUP",
                    message = "Missing printer attributes group",
                    severity = ErrorSeverity.CRITICAL,
                    suggestedFix = "Add a printer attributes group with Tag.printerAttributes"
                )
            )
            return
        }
        
        val presentAttributes = extractAttributeNames(printerGroup)
        
        for (requiredAttr in requiredPrinterAttributes) {
            if (requiredAttr !in presentAttributes) {
                errors.add(
                    ValidationError(
                        code = "MISSING_REQUIRED_ATTRIBUTE",
                        message = "Missing required attribute: $requiredAttr",
                        severity = ErrorSeverity.CRITICAL,
                        attributeName = requiredAttr,
                        groupTag = Tag.printerAttributes.name,
                        suggestedFix = "Add the $requiredAttr attribute to printer group"
                    )
                )
            }
        }
    }
    
    /**
     * Validate attribute syntax and structure
     */
    private fun validateAttributeSyntax(
        attributes: List<AttributeGroup>,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        for (group in attributes) {
            try {
                val groupAttributes = extractAttributes(group)
                
                for (attr in groupAttributes) {
                    // Validate attribute name format
                    if (!isValidAttributeName(attr.name)) {
                        errors.add(
                            ValidationError(
                                code = "INVALID_ATTRIBUTE_NAME",
                                message = "Invalid attribute name format: ${attr.name}",
                                severity = ErrorSeverity.HIGH,
                                attributeName = attr.name,
                                groupTag = group.tag.name,
                                suggestedFix = "Use lowercase with hyphens (e.g., 'printer-name')"
                            )
                        )
                    }
                    
                    // Validate attribute values
                    validateAttributeValue(attr, errors, warnings)
                }
                
            } catch (e: Exception) {
                errors.add(
                    ValidationError(
                        code = "GROUP_PARSE_ERROR",
                        message = "Error parsing attribute group ${group.tag}: ${e.message}",
                        severity = ErrorSeverity.HIGH,
                        groupTag = group.tag.name
                    )
                )
            }
        }
    }
    
    /**
     * Validate value constraints for specific attributes
     */
    private fun validateValueConstraints(
        attributes: List<AttributeGroup>,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        val printerGroup = attributes.find { it.tag == Tag.printerAttributes } ?: return
        val printerAttributes = extractAttributes(printerGroup)
        
        for (attr in printerAttributes) {
            when (attr.name) {
                "ipp-versions-supported" -> {
                    val versions = getAttributeValues(attr)
                    if ("1.1" !in versions) {
                        warnings.add(
                            ValidationWarning(
                                code = "MISSING_IPP_VERSION",
                                message = "IPP version 1.1 not supported - may cause compatibility issues",
                                attributeName = attr.name,
                                recommendation = "Add IPP version 1.1 support for better compatibility"
                            )
                        )
                    }
                }
                
                "printer-state" -> {
                    val state = getAttributeValues(attr).firstOrNull()
                    if (state !in listOf("idle", "processing", "stopped")) {
                        errors.add(
                            ValidationError(
                                code = "INVALID_PRINTER_STATE",
                                message = "Invalid printer state: $state",
                                severity = ErrorSeverity.HIGH,
                                attributeName = attr.name,
                                suggestedFix = "Use one of: idle, processing, stopped"
                            )
                        )
                    }
                }
                
                "operations-supported" -> {
                    val operations = getAttributeValues(attr)
                    val requiredOps = listOf("Print-Job", "Get-Printer-Attributes")
                    for (requiredOp in requiredOps) {
                        if (requiredOp !in operations) {
                            errors.add(
                                ValidationError(
                                    code = "MISSING_REQUIRED_OPERATION",
                                    message = "Missing required operation: $requiredOp",
                                    severity = ErrorSeverity.CRITICAL,
                                    attributeName = attr.name,
                                    suggestedFix = "Add support for $requiredOp operation"
                                )
                            )
                        }
                    }
                }
                
                "document-format-supported" -> {
                    val formats = getAttributeValues(attr)
                    if (formats.isEmpty()) {
                        errors.add(
                            ValidationError(
                                code = "EMPTY_DOCUMENT_FORMATS",
                                message = "No supported document formats specified",
                                severity = ErrorSeverity.CRITICAL,
                                attributeName = attr.name,
                                suggestedFix = "Add at least one supported format (e.g., 'application/pdf')"
                            )
                        )
                    } else if ("application/octet-stream" !in formats) {
                        warnings.add(
                            ValidationWarning(
                                code = "MISSING_OCTET_STREAM",
                                message = "Missing 'application/octet-stream' format support",
                                attributeName = attr.name,
                                recommendation = "Add 'application/octet-stream' for raw data support"
                            )
                        )
                    }
                }
                
                "charset-supported" -> {
                    val charsets = getAttributeValues(attr)
                    if ("utf-8" !in charsets) {
                        warnings.add(
                            ValidationWarning(
                                code = "MISSING_UTF8_CHARSET",
                                message = "UTF-8 charset not supported",
                                attributeName = attr.name,
                                recommendation = "Add UTF-8 charset support for international compatibility"
                            )
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Validate compatibility with common IPP clients
     */
    private fun validateCompatibility(
        attributes: List<AttributeGroup>,
        warnings: MutableList<ValidationWarning>,
        suggestions: MutableList<ValidationSuggestion>
    ) {
        val printerGroup = attributes.find { it.tag == Tag.printerAttributes } ?: return
        val presentAttributes = extractAttributeNames(printerGroup)
        
        // Check for recommended attributes
        for (recommendedAttr in recommendedAttributes) {
            if (recommendedAttr !in presentAttributes) {
                suggestions.add(
                    ValidationSuggestion(
                        code = "MISSING_RECOMMENDED_ATTRIBUTE",
                        message = "Consider adding recommended attribute: $recommendedAttr",
                        attributeName = recommendedAttr,
                        improvement = "Improves compatibility with various IPP clients"
                    )
                )
            }
        }
        
        // Check for macOS compatibility
        if ("printer-make-and-model" !in presentAttributes) {
            warnings.add(
                ValidationWarning(
                    code = "MACOS_COMPATIBILITY",
                    message = "macOS may have issues without printer-make-and-model",
                    attributeName = "printer-make-and-model",
                    recommendation = "Add printer-make-and-model for better macOS compatibility"
                )
            )
        }
        
        // Check for Windows compatibility
        if ("color-supported" !in presentAttributes) {
            suggestions.add(
                ValidationSuggestion(
                    code = "WINDOWS_COMPATIBILITY",
                    message = "Windows clients prefer explicit color support indication",
                    attributeName = "color-supported",
                    improvement = "Add color-supported attribute for better Windows integration"
                )
            )
        }
    }
    
    /**
     * Validate performance-related attributes
     */
    private fun validatePerformanceConsiderations(
        attributes: List<AttributeGroup>,
        suggestions: MutableList<ValidationSuggestion>
    ) {
        val printerGroup = attributes.find { it.tag == Tag.printerAttributes } ?: return
        val printerAttributes = extractAttributes(printerGroup)
        
        // Check for performance attributes
        val hasSpeedAttributes = printerAttributes.any { 
            it.name in listOf("pages-per-minute", "pages-per-minute-color") 
        }
        
        if (!hasSpeedAttributes) {
            suggestions.add(
                ValidationSuggestion(
                    code = "PERFORMANCE_ATTRIBUTES",
                    message = "Consider adding speed attributes for better client experience",
                    improvement = "Add pages-per-minute attributes to help clients estimate job completion"
                )
            )
        }
        
        // Check document format efficiency
        val documentFormats = printerAttributes
            .find { it.name == "document-format-supported" }
            ?.let { getAttributeValues(it) } ?: emptyList()
        
        if (documentFormats.size > 10) {
            suggestions.add(
                ValidationSuggestion(
                    code = "TOO_MANY_FORMATS",
                    message = "Large number of supported formats may impact performance",
                    attributeName = "document-format-supported",
                    improvement = "Consider reducing to most commonly used formats"
                )
            )
        }
    }
    
    /**
     * Validate security considerations
     */
    private fun validateSecurity(
        attributes: List<AttributeGroup>,
        warnings: MutableList<ValidationWarning>,
        suggestions: MutableList<ValidationSuggestion>
    ) {
        val printerGroup = attributes.find { it.tag == Tag.printerAttributes } ?: return
        val printerAttributes = extractAttributes(printerGroup)
        
        // Check URI security
        val uriSecurity = printerAttributes
            .find { it.name == "uri-security-supported" }
            ?.let { getAttributeValues(it) } ?: emptyList()
        
        if ("tls" !in uriSecurity && "ssl3" !in uriSecurity) {
            warnings.add(
                ValidationWarning(
                    code = "NO_SECURE_TRANSPORT",
                    message = "No secure transport methods supported",
                    attributeName = "uri-security-supported",
                    recommendation = "Consider adding TLS support for secure communication"
                )
            )
        }
        
        // Check authentication
        val uriAuth = printerAttributes
            .find { it.name == "uri-authentication-supported" }
            ?.let { getAttributeValues(it) } ?: emptyList()
        
        if (uriAuth.size <= 1 && "none" in uriAuth) {
            suggestions.add(
                ValidationSuggestion(
                    code = "AUTHENTICATION_OPTIONS",
                    message = "Consider adding authentication methods for secure environments",
                    attributeName = "uri-authentication-supported",
                    improvement = "Add basic, digest, or certificate authentication support"
                )
            )
        }
    }
    
    // Helper methods
    private fun extractAttributeNames(group: AttributeGroup): Set<String> {
        return try {
            extractAttributes(group).map { it.name }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting attribute names", e)
            emptySet()
        }
    }
    
    private fun extractAttributes(group: AttributeGroup): List<Attribute<*>> {
        return try {
            // AttributeGroup implements Iterable<Attribute<*>>, so we can iterate directly
            // This is much more reliable than trying to parse string representations
            group.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting attributes from group", e)
            emptyList()
        }
    }
    
    private fun getAttributeValues(attr: Attribute<*>): List<String> {
        return try {
            // Convert attribute values to strings
            // This would need proper implementation with HP JIPP library
            listOf(attr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting attribute values", e)
            emptyList()
        }
    }
    
    private fun validateAttributeValue(
        attr: Attribute<*>,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        try {
            val values = getAttributeValues(attr)
            
            // Check for empty values
            if (values.isEmpty()) {
                warnings.add(
                    ValidationWarning(
                        code = "EMPTY_ATTRIBUTE_VALUE",
                        message = "Attribute ${attr.name} has no values",
                        attributeName = attr.name,
                        recommendation = "Ensure attribute has at least one value"
                    )
                )
            }
            
            // Check for null or empty string values
            values.forEach { value ->
                if (value.isBlank()) {
                    warnings.add(
                        ValidationWarning(
                            code = "BLANK_ATTRIBUTE_VALUE",
                            message = "Attribute ${attr.name} has blank value",
                            attributeName = attr.name,
                            recommendation = "Remove blank values or provide meaningful content"
                        )
                    )
                }
            }
            
        } catch (e: Exception) {
            errors.add(
                ValidationError(
                    code = "ATTRIBUTE_VALUE_ERROR",
                    message = "Error validating attribute ${attr.name}: ${e.message}",
                    severity = ErrorSeverity.MEDIUM,
                    attributeName = attr.name
                )
            )
        }
    }
    
    private fun isValidAttributeName(name: String): Boolean {
        // IPP attribute names should be lowercase with hyphens
        return name.matches(Regex("^[a-z][a-z0-9-]*[a-z0-9]$"))
    }
    
    /**
     * Get validation summary
     */
    fun getValidationSummary(result: ValidationResult): String {
        val criticalErrors = result.errors.count { it.severity == ErrorSeverity.CRITICAL }
        val highErrors = result.errors.count { it.severity == ErrorSeverity.HIGH }
        val totalErrors = result.errors.size
        val warnings = result.warnings.size
        val suggestions = result.suggestions.size
        
        return buildString {
            if (result.isValid) {
                append("✅ Attributes are valid for IPP operation\n")
            } else {
                append("❌ Attributes have critical issues\n")
            }
            
            if (criticalErrors > 0) {
                append("🔴 Critical errors: $criticalErrors\n")
            }
            if (highErrors > 0) {
                append("🟠 High priority errors: $highErrors\n")
            }
            if (totalErrors > criticalErrors + highErrors) {
                append("🟡 Other errors: ${totalErrors - criticalErrors - highErrors}\n")
            }
            if (warnings > 0) {
                append("⚠️ Warnings: $warnings\n")
            }
            if (suggestions > 0) {
                append("💡 Suggestions: $suggestions\n")
            }
        }
    }
}