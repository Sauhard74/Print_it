package com.example.printer.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.printer.plugins.ConfigurationField as PluginConfigField
import com.example.printer.plugins.FieldType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationField(
    field: PluginConfigField,
    currentValue: Any?,
    onValueChange: (Any) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        if (field.description != null) {
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        when (field.type) {
            FieldType.TEXT -> {
                OutlinedTextField(
                    value = currentValue?.toString() ?: field.defaultValue?.toString() ?: "",
                    onValueChange = { onValueChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(field.defaultValue?.toString() ?: "")
                    }
                )
            }
            
            FieldType.NUMBER -> {
                OutlinedTextField(
                    value = currentValue?.toString() ?: field.defaultValue?.toString() ?: "",
                    onValueChange = { value ->
                        val number = value.toDoubleOrNull()
                        if (number != null) {
                            val clampedValue = when {
                                field.min != null && number < field.min.toDouble() -> field.min
                                field.max != null && number > field.max.toDouble() -> field.max
                                else -> number
                            }
                            onValueChange(clampedValue)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = {
                        Text(field.defaultValue?.toString() ?: "0")
                    },
                    supportingText = {
                        if (field.min != null || field.max != null) {
                            Text(
                                text = "Range: ${field.min ?: "∞"} - ${field.max ?: "∞"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }
            
            FieldType.BOOLEAN -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (currentValue as? Boolean == true) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = currentValue as? Boolean ?: field.defaultValue as? Boolean ?: false,
                            onCheckedChange = { onValueChange(it) }
                        )
                    }
                }
            }
            
            FieldType.SELECT -> {
                var expanded by remember { mutableStateOf(false) }
                val options = field.options ?: emptyList()
                val selectedValue = currentValue?.toString() ?: field.defaultValue?.toString() ?: ""
                
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedValue,
                        onValueChange = { },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        placeholder = {
                            Text(field.defaultValue?.toString() ?: "Select option")
                        }
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    onValueChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            FieldType.FILE -> {
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let {
                        val filePath = it.toString()
                        onValueChange(filePath)
                    }
                }
                
                OutlinedTextField(
                    value = currentValue?.toString() ?: field.defaultValue?.toString() ?: "",
                    onValueChange = { onValueChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("File path")
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { 
                                filePickerLauncher.launch("*/*")
                            }
                        ) {
                            Text("Browse")
                        }
                    }
                )
            }
            
            FieldType.COLOR -> {
                val colorString = currentValue?.toString() ?: field.defaultValue?.toString() ?: "#000000"
                val parsedColor = remember(colorString) {
                    try {
                        parseHexColor(colorString)
                    } catch (e: Exception) {
                        Color.Gray
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = colorString,
                        onValueChange = { 
                            val trimmed = it.trim()
                            if (trimmed.startsWith("#") || trimmed.matches(Regex("^[0-9A-Fa-f]{6}$"))) {
                                val formatted = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
                                onValueChange(formatted)
                            } else if (trimmed.isEmpty()) {
                                onValueChange("#000000")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("#RRGGBB")
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = parsedColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                }
            }
        }
    }
}

/**
 * Parses a hex color string (e.g., "#RRGGBB" or "RRGGBB") into a Color object
 */
private fun parseHexColor(hex: String): Color {
    val cleanHex = hex.trim().removePrefix("#")
    if (cleanHex.length != 6) {
        throw IllegalArgumentException("Invalid hex color format: $hex")
    }
    
    val r = cleanHex.substring(0, 2).toInt(16)
    val g = cleanHex.substring(2, 4).toInt(16)
    val b = cleanHex.substring(4, 6).toInt(16)
    
    return Color(r, g, b)
}