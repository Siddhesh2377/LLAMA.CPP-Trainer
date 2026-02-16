package com.dark.trainer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    temperature: Float,
    maxTokens: Int,
    systemPrompt: String,
    onTemperatureChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var tempTemperature by remember { mutableFloatStateOf(temperature) }
    var tempMaxTokens by remember { mutableIntStateOf(maxTokens) }
    var tempSystemPrompt by remember { mutableStateOf(systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Temperature
                Column {
                    Text(
                        "Temperature: ${String.format("%.2f", tempTemperature)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Lower = more focused, Higher = more creative",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = tempTemperature,
                        onValueChange = { tempTemperature = it },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                }

                // Max Tokens
                Column {
                    Text(
                        "Max Tokens: $tempMaxTokens",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Maximum length of generated response",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = tempMaxTokens.toFloat(),
                        onValueChange = { tempMaxTokens = it.toInt() },
                        valueRange = 64f..2048f,
                        steps = 15
                    )
                }

                // System Prompt
                Column {
                    Text(
                        "System Prompt",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = tempSystemPrompt,
                        onValueChange = { tempSystemPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        placeholder = { Text("You are a helpful AI assistant.") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onTemperatureChange(tempTemperature)
                    onMaxTokensChange(tempMaxTokens)
                    onSystemPromptChange(tempSystemPrompt)
                    onSave()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
