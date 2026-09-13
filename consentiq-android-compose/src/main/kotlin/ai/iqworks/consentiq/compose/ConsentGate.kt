package ai.iqworks.consentiq.compose

import ai.iqworks.consentiq.ConsentIQ
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
public fun ConsentGate(
    category: String,
    placeholder: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state by ConsentIQ.state.collectAsStateWithLifecycle()
    when {
        state.isLoading -> placeholder()
        state.hasConsent(category) -> content()
    }
}
