package com.androidharness.app.ui.setup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.androidharness.app.AppContainer
import kotlinx.coroutines.launch
@Composable
fun SetupScreen(container: AppContainer, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f)); Text("horizon", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(48.dp))
        Text("Big ideas.\nRight here.", fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 50.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Text("Your own AI agent for chatting, research and creating. Powered by a model on your phone.", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("Your files and model downloads have their own home. App updates keep them safe.", style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { scope.launch { container.settings.setOnboardingDone(true); onFinish() } }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Get started") }
        Spacer(Modifier.height(16.dp))
    }
}
