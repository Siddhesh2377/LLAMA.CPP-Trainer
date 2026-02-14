package com.dark.trainer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dark.lora.LoraJNI
import com.dark.trainer.ui.theme.TrainerTheme

class MainActivity : ComponentActivity() {
    private val loraJNI = LoraJNI()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermission()

        setContent {
            TrainerTheme {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Train", "Test")

        // Shared state
        var log by remember { mutableStateOf("Ready.") }
        var modelPath by remember { mutableStateOf("/storage/emulated/0/Download/Qwen3-0.6B-Q8_0.gguf") }
        var isBusy by remember { mutableStateOf(false) }
        var currentStage by remember { mutableStateOf("") }

        fun appendLog(msg: String) {
            log = msg + "\n" + log
            if (log.length > 50000) log = log.substring(0, 50000)
        }

        LaunchedEffect(Unit) {
            loraJNI.setLogCallback(object : LoraJNI.LogCallback {
                override fun onLog(message: String) {
                    runOnUiThread { appendLog(message) }
                }
            })
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("LoRA Trainer") })
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> TrainTab(
                        modelPath = modelPath,
                        onModelPathChange = { modelPath = it },
                        log = log,
                        isBusy = isBusy,
                        currentStage = currentStage,
                        onBusyChange = { isBusy = it },
                        onStageChange = { currentStage = it },
                        appendLog = ::appendLog
                    )
                    1 -> TestTab(
                        modelPath = modelPath,
                        log = log,
                        isBusy = isBusy,
                        onBusyChange = { isBusy = it },
                        appendLog = ::appendLog
                    )
                }
            }
        }
    }

    @Composable
    private fun TrainTab(
        modelPath: String,
        onModelPathChange: (String) -> Unit,
        log: String,
        isBusy: Boolean,
        currentStage: String,
        onBusyChange: (Boolean) -> Unit,
        onStageChange: (String) -> Unit,
        appendLog: (String) -> Unit
    ) {
        var trainingText by remember { mutableStateOf("The quick brown fox jumps over the lazy dog. " +
                "Machine learning is a subset of artificial intelligence. " +
                "Neural networks are inspired by the human brain. " +
                "Deep learning uses multiple layers of neural networks. " +
                "Natural language processing enables computers to understand human language. " +
                "Transformers are a type of neural network architecture. " +
                "Large language models can generate human-like text. " +
                "Fine-tuning adapts a pre-trained model to a specific task. " +
                "LoRA is a parameter-efficient fine-tuning method. " +
                "On-device training enables privacy-preserving machine learning.") }
        var rank by remember { mutableStateOf("2") }
        var alpha by remember { mutableStateOf("8.0") }
        var learningRate by remember { mutableStateOf("0.0001") }
        var epochs by remember { mutableStateOf("1") }
        var nCtx by remember { mutableStateOf("256") }
        var skipLayers by remember { mutableStateOf("20") }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text("Model", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = modelPath,
                onValueChange = onModelPathChange,
                label = { Text("GGUF Model Path") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Training Data", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = trainingText,
                onValueChange = { trainingText = it },
                label = { Text("Training Text") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                maxLines = 5
            )

            Text("Parameters", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nCtx, onValueChange = { nCtx = it },
                    label = { Text("Context") }, modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    value = rank, onValueChange = { rank = it },
                    label = { Text("Rank") }, modifier = Modifier.width(70.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    value = alpha, onValueChange = { alpha = it },
                    label = { Text("Alpha") }, modifier = Modifier.width(70.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                OutlinedTextField(
                    value = skipLayers, onValueChange = { skipLayers = it },
                    label = { Text("Skip") }, modifier = Modifier.width(65.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = learningRate, onValueChange = { learningRate = it },
                    label = { Text("Learning Rate") }, modifier = Modifier.width(140.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                OutlinedTextField(
                    value = epochs, onValueChange = { epochs = it },
                    label = { Text("Epochs") }, modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
            }

            if (currentStage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBusy) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(text = currentStage, modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    onBusyChange(true)
                    Thread {
                        val nEpochs = epochs.toIntOrNull() ?: 1
                        val lr = learningRate.toFloatOrNull() ?: 1e-4f
                        val ctxSize = nCtx.toIntOrNull() ?: 256
                        val loraRank = rank.toIntOrNull() ?: 2
                        val loraAlpha = alpha.toFloatOrNull() ?: 8.0f
                        val skip = skipLayers.toIntOrNull() ?: 0

                        try {
                            runOnUiThread { onStageChange("[1/6] Initializing backend...") }
                            if (!loraJNI.initLlamaBackend(applicationInfo.nativeLibraryDir)) {
                                runOnUiThread { onStageChange("FAILED: Backend init"); onBusyChange(false) }
                                return@Thread
                            }

                            runOnUiThread { onStageChange("[2/6] Loading model...") }
                            val modelResult = loraJNI.loadModel(modelPath, 0, ctxSize)
                            if (modelResult.startsWith("ERROR")) {
                                runOnUiThread { onStageChange("FAILED: $modelResult"); onBusyChange(false) }
                                return@Thread
                            }

                            runOnUiThread { onStageChange("[3/6] Creating LoRA adapter...") }
                            val adapterResult = loraJNI.createLoraAdapter(loraRank, loraAlpha, skip)
                            if (adapterResult.startsWith("ERROR")) {
                                runOnUiThread { onStageChange("FAILED: $adapterResult"); onBusyChange(false) }
                                return@Thread
                            }

                            runOnUiThread { onStageChange("[4/6] Tokenizing & preparing data...") }
                            val dataResult = loraJNI.setTrainingData(trainingText)
                            if (dataResult.startsWith("ERROR")) {
                                runOnUiThread { onStageChange("FAILED: $dataResult"); onBusyChange(false) }
                                return@Thread
                            }

                            runOnUiThread { onStageChange("[5/6] Initializing optimizer...") }
                            val initResult = loraJNI.initTraining(lr, nEpochs)
                            if (initResult.startsWith("ERROR")) {
                                runOnUiThread { onStageChange("FAILED: $initResult"); onBusyChange(false) }
                                return@Thread
                            }

                            for (i in 0 until nEpochs) {
                                val startTime = System.currentTimeMillis()
                                runOnUiThread { onStageChange("[5/6] Training epoch ${i + 1}/$nEpochs...") }
                                loraJNI.trainEpoch(i)
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                                runOnUiThread { appendLog("--- Epoch ${i + 1} took ${"%.1f".format(elapsed)}s ---") }
                            }

                            runOnUiThread { onStageChange("[6/6] Saving LoRA adapter...") }
                            val outputPath = "${filesDir}/lora_adapter.gguf"
                            loraJNI.saveLoraAdapter(outputPath)

                            runOnUiThread {
                                onStageChange("Training complete! Saved to $outputPath")
                                onBusyChange(false)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                onStageChange("FAILED: ${e.message}")
                                appendLog("Exception: ${e.stackTraceToString()}")
                                onBusyChange(false)
                            }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy && modelPath.isNotBlank() && trainingText.isNotBlank()
            ) {
                Text(if (isBusy) "Training..." else "Start Training")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Log", style = MaterialTheme.typography.titleSmall)
            Text(text = log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    @Composable
    private fun TestTab(
        modelPath: String,
        log: String,
        isBusy: Boolean,
        onBusyChange: (Boolean) -> Unit,
        appendLog: (String) -> Unit
    ) {
        var prompt by remember { mutableStateOf("Machine learning is") }
        var maxTokens by remember { mutableStateOf("64") }
        var temperature by remember { mutableStateOf("0.8") }
        var loraPath by remember { mutableStateOf("") }
        var generatedText by remember { mutableStateOf("") }
        var isGenerating by remember { mutableStateOf(false) }
        var hasModel by remember { mutableStateOf(false) }
        var hasAdapter by remember { mutableStateOf(false) }

        // Set default lora path on first render
        LaunchedEffect(Unit) {
            loraPath = "${filesDir}/lora_adapter.gguf"
        }

        // Refresh state when tab becomes visible
        LaunchedEffect(isBusy) {
            if (!isBusy) {
                hasModel = loraJNI.hasModel()
                hasAdapter = loraJNI.hasAdapter()
            }
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasModel) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (hasModel) "Model loaded" else "No model loaded (train first or load below)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (hasModel) {
                        Text(
                            text = if (hasAdapter) "LoRA adapter: active" else "LoRA adapter: none",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Load model (if not already loaded from training)
            if (!hasModel) {
                Button(
                    onClick = {
                        onBusyChange(true)
                        Thread {
                            try {
                                loraJNI.initLlamaBackend(applicationInfo.nativeLibraryDir)
                                val result = loraJNI.loadModel(modelPath, 0, 256)
                                runOnUiThread {
                                    hasModel = !result.startsWith("ERROR")
                                    appendLog(result)
                                    onBusyChange(false)
                                }
                            } catch (e: Exception) {
                                runOnUiThread { appendLog("ERROR: ${e.message}"); onBusyChange(false) }
                            }
                        }.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && modelPath.isNotBlank()
                ) {
                    Text("Load Model")
                }
            }

            // LoRA adapter controls
            if (hasModel) {
                Text("LoRA Adapter", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = loraPath,
                    onValueChange = { loraPath = it },
                    label = { Text("Adapter Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onBusyChange(true)
                            Thread {
                                try {
                                    val result = loraJNI.loadLoraAdapter(loraPath)
                                    runOnUiThread {
                                        hasAdapter = !result.startsWith("ERROR")
                                        appendLog(result)
                                        onBusyChange(false)
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { appendLog("ERROR: ${e.message}"); onBusyChange(false) }
                                }
                            }.start()
                        },
                        enabled = !isBusy && loraPath.isNotBlank()
                    ) {
                        Text("Load LoRA")
                    }
                    OutlinedButton(
                        onClick = {
                            loraJNI.removeLoraAdapter()
                            hasAdapter = false
                            appendLog("LoRA adapter removed")
                        },
                        enabled = !isBusy && hasAdapter
                    ) {
                        Text("Remove LoRA")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Generation
            Text("Generate", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                maxLines = 4
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxTokens, onValueChange = { maxTokens = it },
                    label = { Text("Max Tokens") }, modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    value = temperature, onValueChange = { temperature = it },
                    label = { Text("Temperature") }, modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
            }

            Button(
                onClick = {
                    isGenerating = true
                    onBusyChange(true)
                    generatedText = ""
                    Thread {
                        try {
                            val result = loraJNI.generate(
                                prompt,
                                maxTokens.toIntOrNull() ?: 64,
                                temperature.toFloatOrNull() ?: 0.8f
                            )
                            runOnUiThread {
                                generatedText = if (result.startsWith("ERROR")) result
                                else "$prompt$result"
                                isGenerating = false
                                onBusyChange(false)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                generatedText = "ERROR: ${e.message}"
                                isGenerating = false
                                onBusyChange(false)
                            }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy && hasModel && prompt.isNotBlank()
            ) {
                Text(if (isGenerating) "Generating..." else "Generate")
            }

            // Output
            if (generatedText.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Output", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = generatedText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Log", style = MaterialTheme.typography.titleSmall)
            Text(text = log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    private fun requestStoragePermission() {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loraJNI.cleanupLlama()
    }
}
