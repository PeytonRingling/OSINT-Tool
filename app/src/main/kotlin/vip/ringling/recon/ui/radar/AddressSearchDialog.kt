package vip.ringling.recon.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.AddressQuery
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.SignalTextField
import vip.ringling.recon.ui.theme.ReconTheme

/** Offender-registry search around a street address (Idaho's own "map search"): street, city and radius, plus the legal notice, before anything is sent. */
@Composable
fun AddressSearchDialog(onSearch: (AddressQuery) -> Unit, onDismiss: () -> Unit) {
    val sig = ReconTheme.signal
    var street by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var radiusIndex by remember { mutableIntStateOf(1) }
    val query = AddressQuery.of(street, city, AddressQuery.RADII[radiusIndex])
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Registry near an address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SignalTextField(street, { street = it }, "Street address, e.g. 700 W Jefferson St", description = "Street address")
                SignalTextField(city, { city = it }, "Idaho city, e.g. Boise", description = "City", onGo = { query?.let(onSearch) })
                SignalLabel("Radius (miles)")
                SegmentedControl(AddressQuery.RADII.map { "$it mi" }, radiusIndex, { radiusIndex = it })
                Text("Uses the Idaho State Police registry's own map search: it lists registrants whose registered address is within the radius, nearest first. Idaho addresses only. Shown live and not saved.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                Text("Idaho Code 74-120: this information may not be used for any commercial purpose or to compile a telephone or mailing list, and must not be used to harass, threaten or harm anyone.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { query?.let(onSearch) }, enabled = query != null) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
