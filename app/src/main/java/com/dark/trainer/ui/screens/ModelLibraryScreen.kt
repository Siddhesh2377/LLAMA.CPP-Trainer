package com.dark.trainer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dark.trainer.models.Adapter
import com.dark.trainer.models.BaseModel
import com.dark.trainer.models.LocalAdapter
import com.dark.trainer.models.LocalModel
import com.dark.trainer.viewmodel.ModelManagerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    state: ModelManagerState,
    onRefresh: () -> Unit,
    onDownloadModel: (BaseModel) -> Unit,
    onDownloadAdapter: (Adapter) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDeleteAdapter: (String) -> Unit,
    onLoadModel: (String, String?) -> Unit,
    onSelectModel: (String) -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Downloaded") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Available Models") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Available Adapters") }
                )
            }

            when (selectedTab) {
                0 -> DownloadedTab(
                    localModels = state.localModels,
                    localAdapters = state.localAdapters,
                    onDeleteModel = onDeleteModel,
                    onDeleteAdapter = onDeleteAdapter,
                    onLoadModel = onLoadModel
                )

                1 -> AvailableModelsTab(
                    models = state.availableModels,
                    localModels = state.localModels,
                    isDownloading = state.isDownloading,
                    downloadProgress = state.downloadProgress,
                    downloadingItemId = state.downloadingItemId,
                    onDownload = onDownloadModel,
                    onSelectModel = onSelectModel
                )

                2 -> AvailableAdaptersTab(
                    adapters = state.availableAdapters,
                    localAdapters = state.localAdapters,
                    selectedModelId = state.selectedModelId,
                    isDownloading = state.isDownloading,
                    downloadProgress = state.downloadProgress,
                    downloadingItemId = state.downloadingItemId,
                    onDownload = onDownloadAdapter
                )
            }

            // Error display
            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
fun DownloadedTab(
    localModels: List<LocalModel>,
    localAdapters: List<LocalAdapter>,
    onDeleteModel: (String) -> Unit,
    onDeleteAdapter: (String) -> Unit,
    onLoadModel: (String, String?) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (localModels.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No models downloaded",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Download models from the Available Models tab",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(localModels) { model ->
            LocalModelCard(
                model = model,
                adapters = localAdapters.filter { it.baseModelId == model.baseModelId },
                onDelete = { onDeleteModel(model.baseModelId) },
                onLoad = { adapterId -> onLoadModel(model.baseModelId, adapterId) }
            )
        }
    }
}

@Composable
fun LocalModelCard(
    model: LocalModel,
    adapters: List<LocalAdapter>,
    onDelete: () -> Unit,
    onLoad: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.modelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${model.sizeBytes / 1024 / 1024} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    Button(
                        onClick = { onLoad(null) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Load")
                    }

                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            "Expand"
                        )
                    }

                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (expanded && adapters.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    "Adapters (${adapters.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                adapters.forEach { adapter ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    adapter.adapterName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    adapter.domain,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Button(
                                onClick = { onLoad(adapter.adapterId) },
                                modifier = Modifier.size(width = 80.dp, height = 36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Load", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Model?") },
            text = { Text("This will delete the model and all its adapters. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AvailableModelsTab(
    models: List<BaseModel>,
    localModels: List<LocalModel>,
    isDownloading: Boolean,
    downloadProgress: Int,
    downloadingItemId: String?,
    onDownload: (BaseModel) -> Unit,
    onSelectModel: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(models) { model ->
            val isDownloaded = localModels.any { it.baseModelId == model.id }
            val isDownloadingThis = downloadingItemId == model.id

            BaseModelCard(
                model = model,
                isDownloaded = isDownloaded,
                isDownloading = isDownloadingThis,
                downloadProgress = if (isDownloadingThis) downloadProgress else 0,
                onDownload = { onDownload(model) },
                onSelectForAdapters = { onSelectModel(model.id) }
            )
        }
    }
}

@Composable
fun BaseModelCard(
    model: BaseModel,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    onDownload: () -> Unit,
    onSelectForAdapters: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDownloaded) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    model.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        model.parameterCount?.let {
                            AssistChip(
                                onClick = {},
                                label = { Text(it, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        model.quantization?.let {
                            AssistChip(
                                onClick = {},
                                label = { Text(it, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        model.sizeMb?.let {
                            AssistChip(
                                onClick = {},
                                label = { Text("${it}MB", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "$downloadProgress%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDownloaded) {
                        Button(
                            onClick = onSelectForAdapters,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Extension, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("View Adapters")
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AvailableAdaptersTab(
    adapters: List<Adapter>,
    localAdapters: List<LocalAdapter>,
    selectedModelId: String?,
    isDownloading: Boolean,
    downloadProgress: Int,
    downloadingItemId: String?,
    onDownload: (Adapter) -> Unit
) {
    if (selectedModelId == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Select a model first",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Go to Available Models and select a model to view its adapters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (adapters.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No adapters available for this model",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        items(adapters) { adapter ->
            val isDownloaded = localAdapters.any { it.adapterId == adapter.id }
            val isDownloadingThis = downloadingItemId == adapter.id

            AdapterCard(
                adapter = adapter,
                isDownloaded = isDownloaded,
                isDownloading = isDownloadingThis,
                downloadProgress = if (isDownloadingThis) downloadProgress else 0,
                onDownload = { onDownload(adapter) }
            )
        }
    }
}

@Composable
fun AdapterCard(
    adapter: Adapter,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDownloaded) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                adapter.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Domain: ${adapter.domain}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("v${adapter.version}", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp)
                )
                adapter.sizeMb?.let {
                    AssistChip(
                        onClick = {},
                        label = { Text("${it}MB", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "$downloadProgress%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (!isDownloaded) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        "Downloaded",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
