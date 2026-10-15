package vip.ringling.recon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.ui.theme.ReconTheme

fun ModuleId.icon(): ImageVector = when (this) {
    ModuleId.BREACH -> Icons.Outlined.LockOpen
    ModuleId.IDENTITY -> Icons.Outlined.Fingerprint
    ModuleId.PHONE -> Icons.Outlined.Phone
    ModuleId.DOMAIN -> Icons.Outlined.Dns
    ModuleId.REGISTRY -> Icons.Outlined.Gavel
}

fun InputType.icon(): ImageVector = when (this) {
    InputType.EMAIL -> Icons.Outlined.Mail
    InputType.PHONE -> Icons.Outlined.Phone
    InputType.USERNAME -> Icons.Outlined.AlternateEmail
    InputType.DOMAIN -> Icons.Outlined.Language
    InputType.ZIP -> Icons.Outlined.Pin
}

@Composable
fun severityContainer(s: Severity) = when (s) {
    Severity.CLEAN -> ReconTheme.severity.cleanContainer
    Severity.INFO -> ReconTheme.severity.infoContainer
    Severity.EXPOSED -> ReconTheme.severity.exposedContainer
}

fun Severity.icon(): ImageVector = when (this) {
    Severity.CLEAN -> Icons.Outlined.CheckCircle
    Severity.INFO -> Icons.Outlined.Info
    Severity.EXPOSED -> Icons.Outlined.Warning
}

/** Color is never the only signal: every chip also carries an icon and a word. */
@Composable
fun SeverityChip(severity: Severity, modifier: Modifier = Modifier) {
    val fg = severityColor(severity)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(severityContainer(severity))
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(severity.icon(), null, Modifier.size(15.dp), tint = fg)
        Text(severity.label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
fun ConfidenceMeter(confidence: Confidence, modifier: Modifier = Modifier) {
    val filled = confidence.ordinal + 1
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            androidx.compose.foundation.layout.Box(
                Modifier.size(7.dp).clip(CircleShape).background(
                    if (i < filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
        Text("${confidence.label} confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}
