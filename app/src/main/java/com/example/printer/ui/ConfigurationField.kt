package com.example.printer.ui

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
                OutlinedTextField(
                    value = currentValue?.toString() ?: field.defaultValue?.toString() ?: "",
                    onValueChange = { onValueChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("File path")
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { /* TODO: File picker */ }
                        ) {
                            Text("Browse")
                        }
                    }
                )
            }
            
            FieldType.COLOR -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentValue?.toString() ?: field.defaultValue?.toString() ?: "#000000",
                        onValueChange = { onValueChange(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("#RRGGBB")
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = androidx.compose.ui.graphics.Color.Gray, // TODO: Parse color
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                }
            }
        }
    }
}