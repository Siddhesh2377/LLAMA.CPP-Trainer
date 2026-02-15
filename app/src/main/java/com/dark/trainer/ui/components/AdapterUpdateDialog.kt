package com.dark.trainer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dark.trainer.models.Adapter

@Composable
fun AdapterUpdateDialog(
    updates: List<Adapter>,
    isDownloading: Boolean,
    downloadProgress: Int,
    onDownload: (Adapter) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text("Intelligence Updates Available") },
        text = {
            Column {
                updates.forEach { adapter ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = adapter.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Version ${adapter.version} • ${adapter.sizeMb} MB",
                                style = MaterialTheme.typography.bodySmall
                            )
                            
                            if (isDownloading) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                )
                                Text("Downloading... $downloadProgress%")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { updates.firstOrNull()?.let { onDownload(it) } },
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "Downloading..." else "Update Now")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDownloading
            ) {
                Text("Later")
            }
        }
    )
}