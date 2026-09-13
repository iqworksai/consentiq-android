package ai.iqworks.consentiq.example

import ai.iqworks.consentiq.ConsentIQ
import ai.iqworks.consentiq.compose.ConsentGate
import ai.iqworks.consentiq.compose.ConsentSheet
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ExampleScreen()
                    ConsentSheet()
                }
            }
        }
    }
}

@Composable
private fun ExampleScreen() {
    val state by ConsentIQ.state.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "ConsentIQ Example", style = MaterialTheme.typography.headlineSmall)
        Text(
            text =
                when {
                    state.isLoading -> "Loading configuration"
                    state.config == null -> "Configuration unavailable"
                    else -> "Regulation: ${state.regulation ?: "none"}  Language: ${state.language}"
                },
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        state.consent.forEach { (category, granted) ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(category)
                Text(if (granted) "granted" else "denied")
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ConsentIQ.showSheet() }) { Text("Preferences") }
            OutlinedButton(onClick = { ConsentIQ.resetConsent() }) { Text("Reset") }
        }

        Spacer(Modifier.height(8.dp))

        ConsentGate(
            category = "analytics",
            placeholder = { Text("Waiting for consent state") },
        ) {
            Text("Analytics is enabled for this visitor.")
        }
    }
}
