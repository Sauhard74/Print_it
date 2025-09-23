package com.example.printer.utils

import android.content.Context
import android.util.Log
import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.AttributeType
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.NoSuchElementException
import java.util.ListIterator
import io.ktor.server.response.*

object IppAttributesUtils {
    private const val TAG = "IppAttributesUtils"
    private const val PREFS_NAME = "printer_preferences"
    private const val KEY_IPP_ATTRIBUTES = "ipp_attributes"
    private const val CUSTOM_ATTRIBUTES_DIR = "ipp_attributes"
    
    /**
     * Saves IPP attributes to a JSON file
     */
    fun saveIppAttributes(context: Context, attributes: List<AttributeGroup>, filename: String): Boolean {
        try {
            val attributesDir = File(context.filesDir, CUSTOM_ATTRIBUTES_DIR)
            if (!attributesDir.exists()) {
                attributesDir.mkdirs()
            }
            
            val file = File(attributesDir, filename)
            val jsonArray = JSONArray()
            
            attributes.forEach { group ->
                val groupObj = JSONObject().apply {
                    put("tag", group.tag.name)
                    put("attributes", JSONArray().apply {
                        val attributesInGroup = getAttributesFromGroup(group)
                        attributesInGroup.forEach { attr ->
                            put(JSONObject().apply {
                                put("name", attr.name)
                                put("value", attr.toString())
                                put("type", getAttributeType(attr))
                            })
                        }
                    })
                }
                jsonArray.put(groupObj)
            }
            
            FileOutputStream(file).use { it.write(jsonArray.toString().toByteArray()) }
            Log.d(TAG, "Saved IPP attributes to: ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving IPP attributes", e)
            return false
        }
    }
    
    /**
     * Helper method to get all attributes from a group
     */
    fun getAttributesFromGroup(group: AttributeGroup): List<Attribute<*>> {
        val result = mutableListOf<Attribute<*>>()
        // Try reflection path first for native JIPP implementations
        try {
            val attributesField = AttributeGroup::class.java.getDeclaredField("attributes")
            attributesField.isAccessible = true
            val attributesValue = attributesField.get(group)
            if (attributesValue is Collection<*>) {
                for (attr in attributesValue) {
                    if (attr is Attribute<*>) result.add(attr)
                }
            }
        } catch (e: Exception) {
            // Many of our AttributeGroup instances are custom (created via createAttributeGroup),
            // which don't expose a backing "attributes" field. In that case, fall back to using
            // the public iterator to collect attributes.
            Log.d(TAG, "Falling back to iterator for AttributeGroup access")
        }

        if (result.isEmpty()) {
            try {
                for (attr in group) {
                    if (attr is Attribute<*>) result.add(attr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to iterate attributes in AttributeGroup", e)
            }
        }

        return result
    }
    
    /**
     * Helper method to get attribute type as string
     */
    private fun getAttributeType(attr: Attribute<*>): String {
        return when {
            attr.toString().toIntOrNull() != null -> "INTEGER"
            attr.toString().equals("true", ignoreCase = true) || 
            attr.toString().equals("false", ignoreCase = true) -> "BOOLEAN"
            else -> "STRING"
        }
    }
    
    /**
     * Creates an attribute from name, value and type
     */
    fun createAttribute(name: String, value: String, type: String): Attribute<*>? {
        try {
            // Create basic attribute without relying on Types.of()
            val typedValue = when (type.uppercase()) {
                "INTEGER" -> value.toIntOrNull() ?: return null
                "BOOLEAN" -> value.equals("true", ignoreCase = true)
                else -> value
            }
            
            // Create attribute directly
            return when (typedValue) {
                is String -> StringAttribute(name, typedValue)
                is Int -> IntAttribute(name, typedValue)
                is Boolean -> BooleanAttribute(name, typedValue)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating attribute: $name", e)
        }
        return null
    }
    
    // Basic attribute implementations
    private class StringAttribute(override val name: String, private val value: String) : Attribute<String> {
        override val size: Int = 1
        override val type = object : AttributeType<String> {
            override val name: String get() = this@StringAttribute.name
            override fun coerce(value: Any): String? = value as? String
        }
        override fun isEmpty(): Boolean = false
        
        override fun get(index: Int): String {
            if (index != 0) throw IndexOutOfBoundsException()
            return value
        }
        override fun getValue(): String? = value
        override fun indexOf(element: String): Int = if (element == value) 0 else -1
        override fun lastIndexOf(element: String): Int = indexOf(element)
        
        override fun contains(element: String): Boolean = element == value
        override fun containsAll(elements: Collection<String>): Boolean = elements.all { contains(it) }
        override fun toString(): String = value
        
        override fun iterator(): Iterator<String> = object : Iterator<String> {
            private var hasNext = true
            
            override fun hasNext(): Boolean = hasNext
            
            override fun next(): String {
                if (!hasNext) throw NoSuchElementException()
                hasNext = false
                return value
            }
        }
        
        override fun listIterator(): kotlin.collections.ListIterator<String> = object : kotlin.collections.ListIterator<String> {
            private var index = 0
            
            override fun hasNext(): Boolean = index == 0
            override fun hasPrevious(): Boolean = index == 1
            override fun next(): String {
                if (!hasNext()) throw NoSuchElementException()
                index++
                return value
            }
            override fun nextIndex(): Int = if (hasNext()) 0 else 1
            override fun previous(): String {
                if (!hasPrevious()) throw NoSuchElementException()
                index--
                return value
            }
            override fun previousIndex(): Int = if (hasPrevious()) 0 else -1
        }
        
        override fun listIterator(index: Int): kotlin.collections.ListIterator<String> {
            if (index < 0 || index > 1) {
                throw IndexOutOfBoundsException("Index: $index, Size: 1")
            }
            return object : kotlin.collections.ListIterator<String> {
                private var idx = index
                
                override fun hasNext(): Boolean = idx == 0
                override fun hasPrevious(): Boolean = idx == 1
                override fun next(): String {
                    if (!hasNext()) throw NoSuchElementException()
                    idx++
                    return value
                }
                override fun nextIndex(): Int = if (hasNext()) 0 else 1
                override fun previous(): String {
                    if (!hasPrevious()) throw NoSuchElementException()
                    idx--
                    return value
                }
                override fun previousIndex(): Int = if (hasPrevious()) 0 else -1
            }
        }
        
        override fun subList(fromIndex: Int, toIndex: Int): List<String> {
            if (fromIndex < 0 || toIndex > 1 || fromIndex > toIndex) {
                throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex, size: 1")
            }
            
            return if (fromIndex == toIndex) {
                emptyList()
            } else {
                listOf(value)
            }
        }
    }
    
    // Multi-value string attribute implementation
    private class MultiStringAttribute(override val name: String, private val values: List<String>) : Attribute<String> {
        override val size: Int = values.size
        override val type = object : AttributeType<String> {
            override val name: String get() = this@MultiStringAttribute.name
            override fun coerce(value: Any): String? = value as? String
        }
        override fun isEmpty(): Boolean = values.isEmpty()

        override fun get(index: Int): String {
            if (index < 0 || index >= values.size) throw IndexOutOfBoundsException()
            return values[index]
        }
        override fun getValue(): String? = values.firstOrNull()
        override fun indexOf(element: String): Int = values.indexOf(element)
        override fun lastIndexOf(element: String): Int = values.lastIndexOf(element)

        override fun contains(element: String): Boolean = values.contains(element)
        override fun containsAll(elements: Collection<String>): Boolean = values.containsAll(elements)
        override fun toString(): String = values.joinToString(",")

        override fun iterator(): Iterator<String> = values.iterator()
        override fun listIterator(): kotlin.collections.ListIterator<String> = values.listIterator()
        override fun listIterator(index: Int): kotlin.collections.ListIterator<String> = values.listIterator(index)
        override fun subList(fromIndex: Int, toIndex: Int): List<String> = values.subList(fromIndex, toIndex)
    }
    
    private class IntAttribute(override val name: String, private val value: Int) : Attribute<Int> {
        override val size: Int = 1
        override val type = object : AttributeType<Int> {
            override val name: String get() = this@IntAttribute.name
            override fun coerce(value: Any): Int? = (value as? Int) ?: (value as? String)?.toIntOrNull()
        }
        override fun isEmpty(): Boolean = false
        
        override fun get(index: Int): Int {
            if (index != 0) throw IndexOutOfBoundsException()
            return value
        }
        override fun getValue(): Int? = value
        override fun indexOf(element: Int): Int = if (element == value) 0 else -1
        override fun lastIndexOf(element: Int): Int = indexOf(element)
        
        override fun contains(element: Int): Boolean = element == value
        override fun containsAll(elements: Collection<Int>): Boolean = elements.all { contains(it) }
        override fun toString(): String = value.toString()
        
        override fun iterator(): Iterator<Int> = object : Iterator<Int> {
            private var hasNext = true
            
            override fun hasNext(): Boolean = hasNext
            
            override fun next(): Int {
                if (!hasNext) throw NoSuchElementException()
                hasNext = false
                return value
            }
        }
        
        override fun listIterator(): kotlin.collections.ListIterator<Int> = object : kotlin.collections.ListIterator<Int> {
            private var index = 0
            
            override fun hasNext(): Boolean = index == 0
            override fun hasPrevious(): Boolean = index == 1
            override fun next(): Int {
                if (!hasNext()) throw NoSuchElementException()
                index++
                return value
            }
            override fun nextIndex(): Int = if (hasNext()) 0 else 1
            override fun previous(): Int {
                if (!hasPrevious()) throw NoSuchElementException()
                index--
                return value
            }
            override fun previousIndex(): Int = if (hasPrevious()) 0 else -1
        }
        
        override fun listIterator(index: Int): kotlin.collections.ListIterator<Int> {
            if (index < 0 || index > 1) {
                throw IndexOutOfBoundsException("Index: $index, Size: 1")
            }
            return object : kotlin.collections.ListIterator<Int> {
                private var idx = index
                
                override fun hasNext(): Boolean = idx == 0
                override fun hasPrevious(): Boolean = idx == 1
                override fun next(): Int {
                    if (!hasNext()) throw NoSuchElementException()
                    idx++
                    return value
                }
                override fun nextIndex(): Int = if (hasNext()) 0 else 1
                override fun previous(): Int {
                    if (!hasPrevious()) throw NoSuchElementException()
                    idx--
                    return value
                }
                override fun previousIndex(): Int = if (hasPrevious()) 0 else -1
            }
        }
        
        override fun subList(fromIndex: Int, toIndex: Int): List<Int> {
            if (fromIndex < 0 || toIndex > 1 || fromIndex > toIndex) {
                throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex, size: 1")
            }
            
            return if (fromIndex == toIndex) {
                emptyList()
            } else {
                listOf(value)
            }
        }
    }
    
    private class BooleanAttribute(override val name: String, private val value: Boolean) : Attribute<Boolean> {
        override val size: Int = 1
        override val type = object : AttributeType<Boolean> {
            override val name: String get() = this@BooleanAttribute.name
            override fun coerce(value: Any): Boolean? = when(value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> null
            }
        }
        override fun isEmpty(): Boolean = false
        
        override fun get(index: Int): Boolean {
            if (index != 0) throw IndexOutOfBoundsException()
            return value
        }
        override fun getValue(): Boolean? = value
        override fun indexOf(element: Boolean): Int = if (element == value) 0 else -1
        override fun lastIndexOf(element: Boolean): Int = indexOf(element)
        
        override fun contains(element: Boolean): Boolean = element == value
        override fun containsAll(elements: Collection<Boolean>): Boolean = elements.all { contains(it) }
        override fun toString(): String = value.toString()
        
        override fun iterator(): Iterator<Boolean> = object : Iterator<Boolean> {
            private var hasNext = true
            
            override fun hasNext(): Boolean = hasNext
            
            override fun next(): Boolean {
                if (!hasNext) throw NoSuchElementException()
                hasNext = false
                return value
            }
        }
        
        override fun listIterator(): kotlin.collections.ListIterator<Boolean> = object : kotlin.collections.ListIterator<Boolean> {
            private var index = 0
            
            override fun hasNext(): Boolean = index == 0
            override fun hasPrevious(): Boolean = index == 1
            override fun next(): Boolean {
                if (!hasNext()) throw NoSuchElementException()
                index++
                return value
            }
            override fun nextIndex(): Int = if (hasNext()) 0 else 1
            override fun previous(): Boolean {
                if (!hasPrevious()) throw NoSuchElementException()
                index--
                return value
            }
            override fun previousIndex(): Int = if (hasPrevious()) 0 else -1
        }
        
        override fun listIterator(index: Int): kotlin.collections.ListIterator<Boolean> {
            if (index < 0 || index > 1) {
                throw IndexOutOfBoundsException("Index: $index, Size: 1")
            }
            return object : kotlin.collections.ListIterator<Boolean> {
                private var idx = index
                
                override fun hasNext(): Boolean = idx == 0
                override fun hasPrevious(): Boolean = idx == 1
                override fun next(): Boolean {
                    if (!hasNext()) throw NoSuchElementException()
                    idx++
                    return value
                }
                override fun nextIndex(): Int = if (hasNext()) 0 else 1
                override fun previous(): Boolean {
                    if (!hasPrevious()) throw NoSuchElementException()
                    idx--
                    return value
                }
                override fun previousIndex(): Int = if (hasPrevious()) 0 else -1
            }
        }
        
        override fun subList(fromIndex: Int, toIndex: Int): List<Boolean> {
            if (fromIndex < 0 || toIndex > 1 || fromIndex > toIndex) {
                throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex, size: 1")
            }
            
            return if (fromIndex == toIndex) {
                emptyList()
            } else {
                listOf(value)
            }
        }
    }
    
    /**
     * Loads IPP attributes from a JSON file
     */
    fun loadIppAttributes(context: Context, filename: String): List<AttributeGroup>? {
        try {
            val file = File(context.filesDir, "$CUSTOM_ATTRIBUTES_DIR/$filename")
            if (!file.exists()) {
                Log.e(TAG, "IPP attributes file not found: ${file.absolutePath}")
                return null
            }
            
            val jsonString = FileInputStream(file).bufferedReader().use { it.readText() }
            val attributeGroups = mutableListOf<AttributeGroup>()

            // Detect format: legacy array vs printer JSON object
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("[")) {
                // Legacy array format
                val jsonArray = JSONArray(trimmed)
                parseLegacyAttributeArray(jsonArray, attributeGroups)
            } else {
                // New printer JSON format (e.g., { "response": { ... } })
                val root = try { JSONObject(trimmed) } catch (e: Exception) {
                    Log.e(TAG, "Invalid JSON object in IPP attributes file", e)
                    null
                }
                if (root != null) {
                    parsePrinterResponseJson(root, attributeGroups)
                }
            }

            Log.d(TAG, "Loaded ${attributeGroups.size} IPP attribute groups from: ${file.absolutePath}")
            return if (attributeGroups.isNotEmpty()) attributeGroups else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading IPP attributes", e)
            return null
        }
    }

    /**
     * Parses the legacy array-based schema into AttributeGroups
     */
    private fun parseLegacyAttributeArray(jsonArray: JSONArray, out: MutableList<AttributeGroup>) {
        for (i in 0 until jsonArray.length()) {
            val groupObj = jsonArray.getJSONObject(i)
            val tagName = groupObj.optString("tag", "PRINTER_ATTRIBUTES")
            val tag = getTagByName(tagName) ?: Tag.printerAttributes

            val loadedAttributes = mutableListOf<Attribute<*>>()
            val attrsJsonArray = groupObj.optJSONArray("attributes") ?: JSONArray()
            for (j in 0 until attrsJsonArray.length()) {
                val attrObj = attrsJsonArray.getJSONObject(j)
                val name = attrObj.optString("name")
                if (name.isBlank()) continue

                // Prefer multi-value if present
                if (attrObj.has("values")) {
                    val valuesArray = attrObj.getJSONArray("values")
                    val values = (0 until valuesArray.length()).map { idx -> valuesArray.get(idx).toString() }
                    loadedAttributes.add(MultiStringAttribute(name, values))
                } else {
                    val valueString = attrObj.optString("value")
                    val typeString = attrObj.optString("type", "STRING")
                    createAttribute(name, valueString, typeString)?.let { loadedAttributes.add(it) }
                }
            }

            if (loadedAttributes.isNotEmpty()) {
                out.add(createAttributeGroup(tag, loadedAttributes))
            }
        }
    }

    /**
     * Parses printer-style response JSON (like the sample provided) and fills AttributeGroups
     */
    private fun parsePrinterResponseJson(root: JSONObject, out: MutableList<AttributeGroup>) {
        try {
            val response = root.optJSONObject("response") ?: root

            // operation-attributes -> OPERATION_ATTRIBUTES group
            response.optJSONObject("operation-attributes")?.let { opAttrs ->
                val attrs = parseNameToObjectMap(opAttrs)
                if (attrs.isNotEmpty()) {
                    out.add(createAttributeGroup(Tag.operationAttributes, attrs))
                }
            }

            // printer-attributes -> PRINTER_ATTRIBUTES group
            response.optJSONObject("printer-attributes")?.let { prAttrs ->
                val attrs = parseNameToObjectMap(prAttrs)
                if (attrs.isNotEmpty()) {
                    out.add(createAttributeGroup(Tag.printerAttributes, attrs))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing printer response JSON", e)
        }
    }

    /**
     * Converts a map of attributeName -> { type, value } objects to a list of Attributes
     */
    private fun parseNameToObjectMap(container: JSONObject): List<Attribute<*>> {
        val result = mutableListOf<Attribute<*>>()
        val names = container.keys()
        var skippedCount = 0
        
        while (names.hasNext()) {
            val name = names.next()
            
            // Skip attributes with invalid names
            if (name.isBlank()) {
                skippedCount++
                continue
            }
            
            try {
                val obj = container.get(name)
                var attribute: Attribute<*>? = null
                
                when (obj) {
                    is JSONObject -> {
                        val type = obj.optString("type", "string").lowercase()
                        
                        // Handle multi-valued attributes
                        if (obj.has("value") && obj.get("value") is JSONArray) {
                            val arr = obj.getJSONArray("value")
                            if (arr.length() > 0) {
                                val values = (0 until arr.length()).map { idx -> 
                                    val value = arr.get(idx)
                                    // Handle null values
                                    if (value == JSONObject.NULL) "" else value.toString()
                                }
                                attribute = MultiStringAttribute(name, values)
                            }
                        } 
                        // Handle single-valued attributes
                        else if (obj.has("value")) {
                            val valueAny = obj.get("value")
                            if (valueAny != JSONObject.NULL) {
                                attribute = makeAttributeFromTypedValue(name, type, valueAny)
                            }
                        }
                        // Handle object without explicit value (treat as JSON string)
                        else {
                            attribute = StringAttribute(name, obj.toString())
                        }
                    }
                    is JSONArray -> {
                        if (obj.length() > 0) {
                            val values = (0 until obj.length()).map { idx -> 
                                val value = obj.get(idx)
                                if (value == JSONObject.NULL) "" else value.toString()
                            }
                            attribute = MultiStringAttribute(name, values)
                        }
                    }
                    is String, is Number, is Boolean -> {
                        // Handle primitive values directly
                        attribute = StringAttribute(name, obj.toString())
                    }
                    else -> {
                        // Handle other types (including null)
                        if (obj != JSONObject.NULL) {
                            attribute = StringAttribute(name, obj.toString())
                        }
                    }
                }
                
                if (attribute != null) {
                    result.add(attribute)
                } else {
                    Log.d(TAG, "Skipped attribute '$name' with null/empty value")
                    skippedCount++
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Skipping attribute '$name' due to parse error: ${e.message}", e)
                skippedCount++
            }
        }
        
        Log.d(TAG, "Parsed ${result.size} attributes, skipped $skippedCount from container")
        return result
    }

    /**
     * Creates an Attribute based on the provided type string and raw value
     */
    private fun makeAttributeFromTypedValue(name: String, type: String, valueAny: Any): Attribute<*>? {
        return try {
            when (type) {
                "integer", "enum" -> when (valueAny) {
                    is Number -> IntAttribute(name, valueAny.toInt())
                    is String -> valueAny.toIntOrNull()?.let { IntAttribute(name, it) } ?: StringAttribute(name, valueAny)
                    else -> StringAttribute(name, valueAny.toString())
                }
                "boolean" -> when (valueAny) {
                    is Boolean -> BooleanAttribute(name, valueAny)
                    is String -> BooleanAttribute(name, valueAny.equals("true", true))
                    else -> BooleanAttribute(name, false)
                }
                // Treat all others as strings while preserving content
                else -> StringAttribute(name, valueAny.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to string attribute for $name", e)
            StringAttribute(name, valueAny.toString())
        }
    }
    
    /**
     * Gets list of available IPP attribute files
     */
    fun getAvailableIppAttributeFiles(context: Context): List<String> {
        val attributesDir = File(context.filesDir, CUSTOM_ATTRIBUTES_DIR)
        if (!attributesDir.exists()) {
            return emptyList()
        }
        return attributesDir.listFiles()?.map { it.name } ?: emptyList()
    }
    
    /**
     * Deletes an IPP attributes file
     */
    fun deleteIppAttributes(context: Context, filename: String): Boolean {
        try {
            val file = File(context.filesDir, "$CUSTOM_ATTRIBUTES_DIR/$filename")
            return if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting IPP attributes file", e)
            return false
        }
    }
    
    /**
     * Validates IPP attributes with different levels of strictness
     */
    fun validateIppAttributes(attributes: List<AttributeGroup>?, strict: Boolean = false): Boolean {
        if (attributes == null || attributes.isEmpty()) {
            Log.w(TAG, "Validation failed: No attributes provided")
            return false
        }
        
        // Basic validation - just check that we have valid groups with valid attributes
        var hasValidGroups = false
        var totalAttributes = 0
        
        for (group in attributes) {
            try {
                val attributesInGroup = getAttributesFromGroup(group)
                if (attributesInGroup.isNotEmpty()) {
                    hasValidGroups = true
                    totalAttributes += attributesInGroup.size
                    
                    // Check that all attributes have valid names
                    for (attr in attributesInGroup) {
                        if (attr.name.isBlank()) {
                            Log.w(TAG, "Validation failed: Found attribute with blank name")
                            return false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Validation failed: Error processing attribute group", e)
                return false
            }
        }
        
        if (!hasValidGroups) {
            Log.w(TAG, "Validation failed: No valid attribute groups found")
            return false
        }
        
        // Strict validation for printer-specific requirements
        if (strict) {
            val requiredGroups = setOf(Tag.printerAttributes)
            val requiredAttributes = setOf(
                "printer-name",
                "printer-state", 
                "printer-is-accepting-jobs"
            )
            
            val hasRequiredGroups = attributes.any { it.tag in requiredGroups }
            val hasRequiredAttributes = attributes.any { group ->
                val attributesInGroup = getAttributesFromGroup(group)
                attributesInGroup.any { attr -> attr.name in requiredAttributes }
            }
            
            if (!hasRequiredGroups || !hasRequiredAttributes) {
                Log.w(TAG, "Strict validation failed: Missing required printer attributes")
                return false
            }
        }
        
        Log.d(TAG, "Validation passed: ${attributes.size} groups, $totalAttributes total attributes")
        return true
    }
    
    /**
     * Helper to get a Tag by name
     */
    fun getTagByName(name: String): Tag? {
        return when (name.uppercase()) {
            "PRINTER_ATTRIBUTES" -> Tag.printerAttributes
            "JOB_ATTRIBUTES" -> Tag.jobAttributes
            "OPERATION_ATTRIBUTES" -> Tag.operationAttributes
            "DOCUMENT_ATTRIBUTES" -> Tag.documentAttributes
            "UNSUPPORTED_ATTRIBUTES" -> Tag.unsupportedAttributes
            else -> null
        }
    }
    
    /**
     * Create an AttributeGroup with the given tag and attributes
     */
    fun createAttributeGroup(tag: Tag, attributes: List<Attribute<*>>): AttributeGroup {
        // Simplified implementation that wraps the attributes
        return object : AttributeGroup {
            override val tag: com.hp.jipp.encoding.DelimiterTag = tag as com.hp.jipp.encoding.DelimiterTag
            override val size: Int = attributes.size
            
            override fun isEmpty(): Boolean = attributes.isEmpty()
            
            override fun iterator(): Iterator<Attribute<*>> = attributes.iterator()
            
            override fun listIterator(): kotlin.collections.ListIterator<Attribute<*>> = attributes.listIterator()
            
            override fun listIterator(index: Int): kotlin.collections.ListIterator<Attribute<*>> = attributes.listIterator(index)
            
            override fun subList(fromIndex: Int, toIndex: Int): List<Attribute<*>> = attributes.subList(fromIndex, toIndex)
            
            override fun contains(element: Attribute<*>): Boolean {
                return attributes.any { it.name == element.name }
            }
            
            override fun containsAll(elements: Collection<Attribute<*>>): Boolean {
                return elements.all { contains(it) }
            }
            
            override fun get(index: Int): Attribute<*> {
                if (index < 0 || index >= attributes.size) throw IndexOutOfBoundsException()
                return attributes[index]
            }
            
            override fun indexOf(element: Attribute<*>): Int {
                return attributes.indexOfFirst { it.name == element.name }
            }
            
            override fun lastIndexOf(element: Attribute<*>): Int {
                return attributes.indexOfLast { it.name == element.name }
            }
            
            @Suppress("UNCHECKED_CAST")
            override operator fun <T : Any> get(type: AttributeType<T>): Attribute<T>? {
                return attributes.firstOrNull { it.name == type.name } as? Attribute<T>
            }
            
            override operator fun get(name: String): Attribute<*>? {
                return attributes.firstOrNull { it.name == name }
            }
            
            override fun toString(): String {
                return "AttributeGroup(tag=$tag, attributes=${attributes.size})"
            }
        }
    }
    
    /**
     * Converts IPP attributes to a JSON string
     */
    fun ippAttributesToJson(attributes: List<AttributeGroup>): String {
        val jsonObject = JSONObject()
        val attributeGroupsArray = JSONArray()
        
        attributes.forEach { group ->
            val groupObject = JSONObject()
            groupObject.put("tag", group.tag.name)
            
            val attributesArray = JSONArray()
            val attributesInGroup = getAttributesFromGroup(group)
            
            attributesInGroup.forEach { attr ->
                val attributeObject = JSONObject()
                attributeObject.put("name", attr.name)
                
                when {
                    attr.size > 1 -> {
                        // Handle multi-value attributes
                        val valuesArray = JSONArray()
                        for (i in 0 until attr.size) {
                            valuesArray.put(attr[i].toString())
                        }
                        attributeObject.put("values", valuesArray)
                        attributeObject.put("type", "collection")
                    }
                    else -> {
                        // Handle single-value attributes
                        attributeObject.put("value", attr.toString())
                        attributeObject.put("type", getAttributeType(attr))
                    }
                }
                
                attributesArray.put(attributeObject)
            }
            
            groupObject.put("attributes", attributesArray)
            attributeGroupsArray.put(groupObject)
        }
        
        jsonObject.put("attributeGroups", attributeGroupsArray)
        return jsonObject.toString(2)  // Pretty print with 2-space indentation
    }
    
    /**
     * Saves IPP attributes as a formatted JSON file
     */
    fun saveIppAttributesAsJson(context: Context, attributes: List<AttributeGroup>, filename: String): Boolean {
        try {
            val jsonString = ippAttributesToJson(attributes)
            
            val attributesDir = File(context.filesDir, CUSTOM_ATTRIBUTES_DIR)
            if (!attributesDir.exists()) {
                attributesDir.mkdirs()
            }
            
            val file = File(attributesDir, filename)
            FileOutputStream(file).use { it.write(jsonString.toByteArray()) }
            
            Log.d(TAG, "Saved IPP attributes as JSON to: ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving IPP attributes as JSON", e)
            return false
        }
    }
} 