package ai.iqworks.consentiq.compose

import ai.iqworks.consentiq.BannerLabels
import ai.iqworks.consentiq.CategoryInfo
import ai.iqworks.consentiq.ConsentIQ
import ai.iqworks.consentiq.PropertyConfig
import ai.iqworks.consentiq.TranslationSet
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.net.URLEncoder

public const val DEFAULT_PRIMARY_COLOR: String = "#f97316"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ConsentSheet(consentIQ: ConsentIQ = ConsentIQ) {
    val state by consentIQ.state.collectAsStateWithLifecycle()
    val config = state.config
    if (!state.isSheetVisible || state.isTcfActive || config == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val direction = if (config.isRtl(state.language)) LayoutDirection.Rtl else LayoutDirection.Ltr

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = { consentIQ.dismissSheet() },
        sheetState = sheetState,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            SheetContent(
                config = config,
                language = state.language,
                consent = state.consent,
                onAcceptAll = { closeThen { consentIQ.acceptAllAsync() } },
                onReject = { closeThen { consentIQ.rejectAllAsync() } },
                onSave = { selection -> closeThen { consentIQ.savePreferencesAsync(selection) } },
            )
        }
    }
}

@Composable
private fun SheetContent(
    config: PropertyConfig,
    language: String,
    consent: Map<String, Boolean>,
    onAcceptAll: () -> Unit,
    onReject: () -> Unit,
    onSave: (Map<String, Boolean>) -> Unit,
) {
    val labels = config.labelsFor(language) ?: config.labels.values.firstOrNull() ?: EMPTY_LABELS
    val translations = config.translationsFor(language)
    val primary = parseHexColor(config.banner.primaryColor) ?: parseHexColor(DEFAULT_PRIMARY_COLOR)!!
    val noticeHref = withLangParam(config.banner.noticeUrl, language)
    val categories = remember(config) { config.categories.filter { it.enabled } }
    val context = LocalContext.current

    var showCategories by remember { mutableStateOf(false) }
    var selection by remember(config, consent) { mutableStateOf(initialSelection(categories, consent)) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
    ) {
        Text(
            text = labels.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        val blurb = translations?.bannerDescription
        if (!blurb.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (noticeHref != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { openUrl(context, noticeHref) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = labels.viewNotice,
                    color = primary,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!showCategories) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAcceptAll,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = Color.White),
                ) {
                    Text(labels.acceptAll)
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Text(labels.reject)
                }
            }
            TextButton(
                onClick = { showCategories = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(labels.customize, color = primary)
            }
        } else {
            Text(
                text = labels.preferencesTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            categories.forEachIndexed { index, category ->
                CategoryRow(
                    category = category,
                    translations = translations,
                    checked = category.isRequired || selection[category.code] == true,
                    primary = primary,
                    onCheckedChange = { checked -> selection = selection + (category.code to checked) },
                )
                if (index < categories.lastIndex) HorizontalDivider()
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(selection) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = Color.White),
            ) {
                Text(labels.save)
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryInfo,
    translations: TranslationSet?,
    checked: Boolean,
    primary: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    val translation = translations?.categories?.get(category.code)
    val title = translation?.title?.takeIf { it.isNotBlank() } ?: category.name
    val description = translation?.description?.takeIf { it.isNotBlank() } ?: category.description
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = !category.isRequired,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = primary,
                    disabledCheckedTrackColor = primary.copy(alpha = 0.5f),
                ),
        )
    }
}

private fun initialSelection(
    categories: List<CategoryInfo>,
    consent: Map<String, Boolean>,
): Map<String, Boolean> {
    val selection = LinkedHashMap<String, Boolean>()
    for (category in categories) {
        selection[category.code] = category.isRequired || (consent[category.code] ?: category.defaultState)
    }
    selection["necessary"] = true
    return selection
}

internal fun withLangParam(
    url: String?,
    language: String,
): String? {
    if (url.isNullOrBlank() || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return null
    val hashIndex = url.indexOf('#')
    val base = if (hashIndex >= 0) url.substring(0, hashIndex) else url
    val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
    val separator = if (base.contains('?')) "&" else "?"
    return "$base${separator}lang=${URLEncoder.encode(language, "UTF-8")}$fragment"
}

internal fun parseHexColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    val expanded =
        when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
    val rgb = expanded.toLongOrNull(16) ?: return null
    return Color(
        red = ((rgb shr 16) and 0xFF).toInt(),
        green = ((rgb shr 8) and 0xFF).toInt(),
        blue = (rgb and 0xFF).toInt(),
    )
}

private fun openUrl(
    context: Context,
    url: String,
) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private val EMPTY_LABELS =
    BannerLabels(
        title = "",
        acceptAll = "",
        acceptRequired = "",
        reject = "",
        customize = "",
        save = "",
        preferencesTitle = "",
        viewNotice = "",
    )
