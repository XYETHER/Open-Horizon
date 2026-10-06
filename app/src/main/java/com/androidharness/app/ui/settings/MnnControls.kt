package com.androidharness.app.ui.settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.local.*
@Composable internal fun MnnControls(settings:MnnSettings){
    val mode by settings.mode.collectAsStateWithLifecycle()
    val precision by settings.precision.collectAsStateWithLifecycle()
    val memory by settings.memory.collectAsStateWithLifecycle()
    val attention by settings.attention.collectAsStateWithLifecycle()
    SettingsPanel(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("MNN performance",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MnnCompute.entries.forEach{choice->FilterChip(selected=mode==choice,onClick={settings.set(choice)},label={Text(choice.label)})}}
            Text("Compute precision",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("low" to "FP16","normal" to "Normal").forEach{(choice,label)->FilterChip(selected=precision==choice,onClick={settings.set(mode,precision=choice)},label={Text(label)})}}
            Text("CPU cache",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(10 to "INT8 K + V",8 to "Unquantized").forEach{(choice,label)->FilterChip(selected=attention==choice,onClick={settings.set(mode,attention=choice)},enabled=mode==MnnCompute.CPU,label={Text(label)})}}
            Text(if(mode==MnnCompute.GPU)"Adreno uses FP16 KV cache. CPU cache selection is retained for CPU." else "INT8 is MNN’s cache format. Unquantized cache follows compute precision. Q5 is unavailable in this engine.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("low" to "Low memory","normal" to "Normal memory").forEach{(choice,label)->FilterChip(selected=memory==choice,onClick={settings.set(mode,memory=choice)},label={Text(label)})}}
            Text("High performance scheduling and Arm INT4 kernels are enabled. Adreno uses buffer kernels, wide tuning and command batching. CPU threads apply only in CPU mode. Settings apply to the next reply.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
