package vip.ringling.recon.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.ui.theme.ReconMotion

@Composable
fun ReconSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    effectiveType: InputType?,
    running: Boolean,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateDpAsState(if (focused) 2.dp else 1.dp, ReconMotion.snappy(), label = "border")
    val cs = MaterialTheme.colorScheme
    val canGo = effectiveType != null && value.isNotBlank()

    Surface(
        modifier.fillMaxWidth().heightIn(min = 68.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainerHigh,
        border = BorderStroke(border, if (focused) cs.primary else cs.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = effectiveType,
                transitionSpec = { (scaleIn(ReconMotion.spatial(), 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
                label = "typeIcon",
            ) { t ->
                Icon(
                    t?.icon() ?: Icons.Rounded.Search,
                    null, Modifier.size(24.dp), tint = if (t != null) cs.primary else cs.onSurfaceVariant,
                )
            }
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty()) {
                    Text("Email, phone, @user, domain, ZIP", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search target" },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    interactionSource = interaction,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Email, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { if (canGo) onSubmit() }),
                )
            }
            if (value.isNotEmpty() && !running) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, "Clear", tint = cs.onSurfaceVariant)
                }
            }
            IconButton(
                onClick = if (running) onStop else onSubmit,
                enabled = running || canGo,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = cs.primary, contentColor = cs.onPrimary,
                    disabledContainerColor = cs.surfaceContainerHighest, disabledContentColor = cs.onSurfaceVariant,
                ),
                shape = CircleShape,
            ) {
                Icon(if (running) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.ArrowForward, if (running) "Stop scan" else "Run scan")
            }
        }
    }
}

/** Auto-detected type is marked; tapping another overrides, tapping the override again returns to auto. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TypeChipRow(
    auto: InputType?,
    override: InputType?,
    onOverride: (InputType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val effective = override ?: auto
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InputType.entries.forEach { type ->
            val selected = effective == type
            val label = when {
                selected && override == null -> "${type.label} · auto"
                selected -> "${type.label} · set"
                else -> type.label
            }
            FilterChip(
                selected = selected,
                onClick = { onOverride(if (override == type) null else if (auto == type && override == null) null else type) },
                label = { Text(label) },
                leadingIcon = { Icon(type.icon(), null, Modifier.size(FilterChipDefaults.IconSize)) },
                shape = MaterialTheme.shapes.large,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}
