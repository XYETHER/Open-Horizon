package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.androidharness.app.local.LocalModelManager
import com.androidharness.app.local.LocalModelLimits
import com.androidharness.app.local.LocalModelCatalog
import com.androidharness.app.local.QuickRunBudgets
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.ui.chat.ChatUiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.androidharness.app.local.DeviceTelemetry
import com.androidharness.app.local.DeviceUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceUsageStrip(state:ChatUiState,manager:LocalModelManager,onApply:suspend (ThinkingLevel,Int)->Result<Unit>) {
 val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
 val sampler=remember {DeviceTelemetry(context.applicationContext)}
 var usage by remember {mutableStateOf<DeviceUsage?>(null)};var details by remember {mutableStateOf(false)}
 val scope=rememberCoroutineScope();var saving by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
 val revision by manager.limitsRevision.collectAsState()
 val limits by produceState<LocalModelLimits?>(null,state.activeProviderId,revision,details) {
  value=withContext(Dispatchers.IO) {state.activeProviderId?.takeIf(LocalModelCatalog::isLocal)?.removePrefix(LocalModelCatalog.PROVIDER_PREFIX)?.let(manager::limits)}
 }
 var output by remember(details,state.activeProviderId,limits) {mutableStateOf(limits?.output?.toString().orEmpty())}
 var thinking by remember(details,state.activeProviderId) {mutableStateOf(state.thinkingLevel)}
 val maxOutput=minOf(4096,(limits?.context ?: 512)-128)
 val draft=limits?.let {runCatching {QuickRunBudgets.output(it,output.toIntOrNull() ?: 0)}.getOrNull()}
 val editable= !state.busy && !saving && limits!=null
 LaunchedEffect(lifecycle,sampler) {lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
  while(true) {usage=withContext(Dispatchers.IO) {runCatching {sampler.sample()}.getOrNull()};delay(1000)}
 }}
 fun ram(bytes:Long)=if(bytes>=1024L*1024*1024)"%.2f GB".format(bytes.toDouble()/(1024*1024*1024)) else "${bytes/(1024*1024)} MB"
 fun percent(value:Double?)=value?.let {"%.0f%%".format(it)} ?: "—"
 val u=usage
 Row(Modifier.fillMaxWidth().clickable {details=true}.padding(horizontal=20.dp,vertical=2.dp),horizontalArrangement=Arrangement.SpaceBetween) {
  Text("App RAM ${u?.let {ram(it.appRam)} ?: "—"}",style=MaterialTheme.typography.labelSmall)
  Text("CPU ${percent(u?.cpuPercent)}",style=MaterialTheme.typography.labelSmall)
  Text("GPU ${percent(u?.gpuPercent)}",style=MaterialTheme.typography.labelSmall)
  IconButton(onClick={details=true},modifier=Modifier.size(36.dp)) {Icon(Icons.Outlined.Settings,"Run settings",Modifier.size(17.dp))}
 }
 if(details)AlertDialog(onDismissRequest={details=false},title={Text("Run settings")},confirmButton={Row {
  TextButton(onClick={details=false},enabled=!saving) {Text("Close")}
  TextButton(enabled=editable && draft!=null,onClick={scope.launch {saving=true;error=null;val result=onApply(thinking,draft!!.output);saving=false;result.fold(onSuccess={details=false},onFailure={error=it.message})}}) {Text("Apply")}
 }},text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("App RAM: ${u?.let {ram(it.appRam)} ?: "Sampling…"} (PSS, including native model memory)")
  Text("Phone RAM: ${u?.let {ram((it.totalRam-it.availableRam).coerceAtLeast(0)) + " / " + ram(it.totalRam)} ?: "Sampling…"}")
  Text("Available RAM: ${u?.let {ram(it.availableRam)} ?: "—"}")
  Text("App CPU: ${percent(u?.cpuPercent)} of ${u?.cores ?: 0} cores. This is app usage, not total device CPU.")
  Text("Device GPU: ${percent(u?.gpuPercent)}. ${u?.gpuStatus ?: "Sampling…"}")
  HorizontalDivider()
  Text("Thinking profile",style=MaterialTheme.typography.titleSmall)
  Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {ThinkingProfile.entries.forEach {profile ->
   FilterChip(selected=thinkingProfile(thinking)==profile,onClick={thinking=profile.level},enabled=editable,label={Text(profile.title)})
  }}
  Text("This controls reasoning effort, not a separate hard thinking-token limit.",style=MaterialTheme.typography.bodySmall)
  OutlinedTextField(output,{output=it},label={Text("Output budget (tokens)")},singleLine=true,enabled=editable,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().semantics {contentDescription="Output budget"},isError=draft==null && output.isNotBlank())
  Slider(value=(output.toFloatOrNull() ?: 16f).coerceIn(16f,maxOutput.toFloat()),onValueChange={output=((it/128).roundToInt()*128).coerceIn(16,maxOutput).toString()},valueRange=16f..maxOutput.toFloat(),enabled=editable)
  Text("16–$maxOutput tokens, including thinking, final text and tool calls. Input allowance: ${draft?.input ?: "—"}. Changes apply to the next reply.",style=MaterialTheme.typography.bodySmall)
  if(state.busy)Text("Stop the task to change settings.",style=MaterialTheme.typography.bodySmall)
  if(limits==null)Text("Choose a local model first.",style=MaterialTheme.typography.bodySmall)
  error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
  Text("Model inference uses CPU. RAM updates about every 5 seconds; CPU/GPU every second while this screen is visible. RAM Plus is not included in physical RAM totals.",style=MaterialTheme.typography.bodySmall)
 }})
}
