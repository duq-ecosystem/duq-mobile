package com.duq.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duq.android.data.SettingsRepository
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    repo: SettingsRepository = koinInject(),
) {
    var silenceTimeout by remember { mutableFloatStateOf(repo.getSilenceTimeoutMsSync().toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Профиль/интеграции вынесены на верхний уровень — аватар в топбаре (AppChrome.openProfile).
            Text("Голос", style = MaterialTheme.typography.titleMedium)

            Text("Таймаут тишины: ${(silenceTimeout / 1000).roundToInt()}с")
            Slider(
                value = silenceTimeout,
                onValueChange = { silenceTimeout = it },
                onValueChangeFinished = { repo.saveSilenceTimeoutMs(silenceTimeout.toLong()) },
                valueRange = 1000f..4000f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
