package com.example.boxclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.boxclient.data.BoxState
import com.example.boxclient.ui.BoxViewModel
import com.example.boxclient.ui.BoxUiState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.boxclient.ui.ActionKey
import com.example.boxclient.ui.ActionKind
import com.example.boxclient.ui.Countdown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

fun formatMinutesSeconds(seconds: Double?): String {
    if (seconds == null) return "—"
    val total = seconds.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}


val SmallButtonPadding = PaddingValues(
    horizontal = 12.dp,
    vertical = 6.dp
)

val VOICE_REG_TEXT = """
I am speaking clearly and at a comfortable, natural pace.
I will continue reading until I am told to stop.

The environment may include background noise,
but my voice should remain steady and consistent.

Please listen carefully to the sound of my voice.
This recording is used only for calibration and synchronization.

Now I will read a sequence of numbers.
Zero, one, two, three, four, five,
six, seven, eight, nine, ten.

I will repeat the numbers once more.
Zero, one, two, three, four, five,
six, seven, eight, nine, ten.

Next, I will read a short sentence.
The quick brown fox jumps over the lazy dog.

I will now read a few words with different sounds.
Blue box. Yellow hazard. Safe disposal.
Robot assistant. Human operator. Team coordination.

I am still speaking at a steady pace.
This is the same voice that will be used during the task.

Voice registration is almost complete.
I will pause briefly, then finish.

Thank you. End of voice sample.
""".trimIndent()

class MainActivity : ComponentActivity() {

    private val viewModel: BoxViewModel by viewModels()
    private var beaconScanner: BeaconScanner? = null

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }



    private fun hasBlePermissions(): Boolean =
        blePermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) ==
                    PackageManager.PERMISSION_GRANTED
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val granted = results.values.all { it }
            if (granted) {
                beaconScanner?.startScanning()
            } else {
                viewModel.updateNearbyBoxes(emptySet())
                viewModel.setMessage("Bluetooth permission denied — proximity disabled")
            }
        }

    private fun requestBlePermissions() {
        permissionLauncher.launch(blePermissions)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        beaconScanner = BeaconScanner(
            context = this,
            onNearbyBoxesChanged = { boxIds ->
                viewModel.updateNearbyBoxes(boxIds)
            }
        )

        setContent {
            MaterialTheme {
                BoxClientApp(viewModel)
            }
        }
    }
    override fun onStart() {
        super.onStart()

        if (hasBlePermissions()) {
            beaconScanner?.startScanning()
        } else {
            requestBlePermissions()
        }
    }


    override fun onStop() {
        super.onStop()
        beaconScanner?.stopScanning()
    }
}
@Composable
fun InlineBadge(
    text: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}
@Composable
fun BoxClientApp(viewModel: BoxViewModel) {
    val uiState by viewModel.uiState


    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()

        ) {

            if (uiState.showSyncOverlay) {
                SyncOverlay(
                    text = VOICE_REG_TEXT,
                    onHide = { viewModel.hideSyncOverlay() }
                )
            }

            // ─── Status Card: server time + nearby nodes ─────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    // Server time at the top
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {

                        val frozen = (uiState.serverTime != null && uiState.timeLimitSec != null &&
                                uiState.serverTime!! >= uiState.timeLimitSec!!)

                        val timeColor =
                            if (frozen) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant


                        Text(
                            text = "Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = timeColor
                        )

                        InlineBadge(
                            text = formatMinutesSeconds(uiState.serverTime),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }


                    val nearbyBoxes = uiState.nearbyBoxIds.toList().sorted()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Nearby",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (nearbyBoxes.isEmpty()) {
                            InlineBadge(
                                text = "none",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            nearbyBoxes.forEach { id ->
                                InlineBadge(
                                    text = id.toString(),
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }


                    val agentId = uiState.selectedAgentId
                    val p =
                        uiState.agentParams?.agents?.get(agentId) ?: uiState.agentParams?.default

                    if (p != null) {
                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Small label (no agent name)
                            Text(
                                text = "Sensor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            AssistChip(
                                onClick = { /* no-op */ },
                                enabled = false,
                                label = {
                                    Text(
                                        text = "X  ${"%.2f".format(p.X.present)}/${"%.2f".format(p.X.absent)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )

                            AssistChip(
                                onClick = { /* no-op */ },
                                enabled = false,
                                label = {
                                    Text(
                                        text = "Y  ${"%.2f".format(p.Y.present)}/${"%.2f".format(p.Y.absent)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }

                    val completedDisposals = uiState.boxes.flatMap { b ->
                        b.disposalResults
                            .filter { it.status == "completed" }
                            .map { dr -> b to dr }
                    }

                    val xCompleted = completedDisposals.count { (_b, dr) -> dr.property == "X" }
                    val yCompleted = completedDisposals.count { (_b, dr) -> dr.property == "Y" }

                    val xTrue = completedDisposals.count { (b, dr) ->
                        dr.property == "X" && dr.success == true
                    }
                    val yTrue = completedDisposals.count { (b, dr) ->
                        dr.property == "Y" && dr.success == true
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(50.dp)
                    ) {
                        Text(
                            text = "✅ X $xTrue/$xCompleted   ✅ Y $yTrue/$yCompleted",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                if (uiState.showSyncOverlay)
                                    viewModel.hideSyncOverlay()
                                else
                                    viewModel.showSyncOverlay()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (uiState.showSyncOverlay) "Hide sync" else "Sync",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ─── Busy indicator ───────────────────────────────────────────────
            if (uiState.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Waiting for response...")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ─── Controls ─────────────────────────────────────────────────────
            TopControls(
                uiState = uiState,
                onPropertyChange = viewModel::onPropertyChanged,
                onSense = viewModel::requestSense,
                onDispose = viewModel::requestDispose,
                onCancelAll = viewModel::cancelAll,
                onSelectedAgentChange = viewModel::setSelectedAgent,
                onShowSync = { viewModel.showSyncOverlay() },

                onHideSync = { viewModel.hideSyncOverlay() },
            )




            Spacer(modifier = Modifier.height(12.dp))

            // ─── List section ─────────────────────────────────────────────────
            Text("Boxes list", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))

            BoxList(
                boxes = uiState.boxes,
                selectedBoxId = uiState.selectedBoxId,
                serverTime = uiState.serverTime,
                nearbyBoxIds = uiState.nearbyBoxIds,
                countdowns = uiState.countdowns,
                onBoxClick = { viewModel.selectBox(it.boxId) },
                modifier = Modifier.weight(1f)   // now valid: direct child of Column
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ─── Map section ──────────────────────────────────────────────────
            Text("Spatial view", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))

            BoxGrid(
                boxes = uiState.boxes,
                selectedBoxId = uiState.selectedBoxId,
                onBoxClick = { viewModel.selectBox(it.boxId) }, // later: map clicks
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            )

            // ─── Message line ─────────────────────────────────────────────────
            if (uiState.lastMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    uiState.lastMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun AgentSelector(
    selectedAgent: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Agent: $selectedAgent")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("human_a") },
                onClick = {
                    onSelect("human_a")
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("human_b") },
                onClick = {
                    onSelect("human_b")
                    expanded = false
                }
            )
        }
    }
}



@Composable
fun SyncOverlay(
    text: String,
    onHide: () -> Unit,
) {
    val scroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Synchronization",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)   // ✅ bound the card height
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll) // ✅ scroll long text
                        .padding(16.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onHide,
                contentPadding = SmallButtonPadding
            ) {
                Text("Hide")
            }
        }
    }
}


@Composable
fun TopControls(
    uiState: BoxUiState,
    onSelectedAgentChange: (String) -> Unit,
    onPropertyChange: (String) -> Unit,
    onSense: () -> Unit,
    onDispose: () -> Unit,
    onCancelAll: () -> Unit,
    onShowSync: () -> Unit,
    onHideSync: () -> Unit,
) {
    Column {

        // Row 1: Selected box + Agent selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Selected",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.selectedBoxId == null) {
                    InlineBadge(
                        text = "none",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    InlineBadge(
                        text = uiState.selectedBoxId.toString(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            AgentSelector(
                selectedAgent = uiState.selectedAgentId,
                onSelect = onSelectedAgentChange
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: Property selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PropertySelector(
                selected = uiState.selectedProperty,
                onPropertyChange = onPropertyChange,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row {
                OutlinedButton(
                    onClick = onCancelAll,
                    enabled = uiState.selectedBoxId != null,
                ) {
                    Text("Cancel")
                }
            }

        }

        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(
                onClick = onSense,
                enabled = uiState.selectedBoxId != null && !uiState.isBusy
            ) {
                Text("Request SENSE")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onDispose,
                enabled = uiState.selectedBoxId != null && !uiState.isBusy
            ) {
                Text("Request DISPOSE")
            }
        }






    }
}




@Composable
fun PropertySelector(
    selected: String,
    onPropertyChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Property: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("X") },
                onClick = {
                    onPropertyChange("X")
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Y") },
                onClick = {
                    onPropertyChange("Y")
                    expanded = false
                }
            )
        }
    }
}

@Composable
fun BoxList(
    boxes: List<BoxState>,
    selectedBoxId: Int?,
    serverTime: Double?,
    nearbyBoxIds: Set<Int>,
    countdowns: Map<ActionKey, Countdown>,   // NEW
    onBoxClick: (BoxState) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(boxes.size) { idx ->
            val box = boxes[idx]
            val isSelected = box.boxId == selectedBoxId
            val isNearby = box.boxId in nearbyBoxIds      // 👈 NEW
            val remaining = serverTime?.let { box.deadline - it }
            val isOverdue = (remaining != null && remaining <= 0.0)
            val textColor = if (isOverdue) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface

            val senseXKey = ActionKey(box.boxId, "X", ActionKind.SENSE)
            val senseYKey = ActionKey(box.boxId, "Y", ActionKind.SENSE)
            val dispXKey  = ActionKey(box.boxId, "X", ActionKind.DISPOSE)
            val dispYKey  = ActionKey(box.boxId, "Y", ActionKind.DISPOSE)
            val senseX = countdowns[senseXKey]?.remainingSec ?: box.senseTimeX
            val senseY = countdowns[senseYKey]?.remainingSec ?: box.senseTimeY
            val dispX  = countdowns[dispXKey]?.remainingSec  ?: box.disposeTimeX
            val dispY  = countdowns[dispYKey]?.remainingSec  ?: box.disposeTimeY

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onBoxClick(box) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor =
                        when {
                            isNearby -> MaterialTheme.colorScheme.secondaryContainer
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Box ${box.boxId}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (isNearby) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "NEARBY",
                                style = MaterialTheme.typography.labelSmall,
                                //color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    Text(
                        text = "Deadline: ${formatMinutesSeconds(remaining)}",
                        color = textColor
                    )
                    Text(
                        text = "Disposed X: ${box.disposedX}, Y: ${box.disposedY}",
                        color = textColor
                    )
                    Text(
                        text = "Sense X/Y: ${formatMinutesSeconds(senseX)} / ${formatMinutesSeconds(senseY)}",
                        color = textColor
                    )
                    Text(
                        text = "Dispose X/Y: ${formatMinutesSeconds(dispX)} / ${formatMinutesSeconds(dispY)}",
                        color = textColor
                    )


                    val completed = box.senseResults
                        .filter { it.status == "completed" && (it.property == "X" || it.property == "Y") }

// Show all estimates, grouped by property then agent
                    val xEsts = completed
                        .filter { it.property == "X" }
                        .sortedWith(compareBy({ it.agentId }, { it.completedAt ?: -1.0 }))  // stable order

                    val yEsts = completed
                        .filter { it.property == "Y" }
                        .sortedWith(compareBy({ it.agentId }, { it.completedAt ?: -1.0 }))

                    if (xEsts.isNotEmpty()) {
                        Text("Sense X estimates:")
                        xEsts.forEach { sr ->
                            val pStr = sr.probability?.let { "%.2f".format(it) } ?: "—"
                            Text(
                                "  ${sr.agentId}: detected=${sr.detected}, p=$pStr",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (yEsts.isNotEmpty()) {
                        Text("Sense Y estimates:")
                        yEsts.forEach { sr ->
                            val pStr = sr.probability?.let { "%.2f".format(it) } ?: "—"
                            Text(
                                "  ${sr.agentId}: detected=${sr.detected}, p=$pStr",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                }
            }
        }
    }


}



@Composable
fun BoxGrid(
    boxes: List<BoxState>,
    selectedBoxId: Int?,
    onBoxClick: (BoxState) -> Unit,   // currently unused; kept for future map clicks
    modifier: Modifier = Modifier,
) {
    if (boxes.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text("No boxes yet")
        }
        return
    }

    val minX = boxes.minOf { it.x }
    val maxX = boxes.maxOf { it.x }
    val minY = boxes.minOf { it.y }
    val maxY = boxes.maxOf { it.y }

    val rangeX = (maxX - minX).takeIf { it != 0.0 } ?: 1.0
    val rangeY = (maxY - minY).takeIf { it != 0.0 } ?: 1.0

    Canvas(modifier = modifier.fillMaxSize()) {
        val padding = 12.dp.toPx()
        val width = size.width - 2 * padding
        val height = size.height - 2 * padding

        boxes.forEach { b ->
            val nx = ((b.x - minX) / rangeX).toFloat()   // 0..1
            val ny = ((b.y - minY) / rangeY).toFloat()   // 0..1

            val xf = nx
            val yf = ny

            val xC = xf - 0.5f
            val yC = yf - 0.5f
            val xr = (-yC) + 0.5f
            val yr = ( xC) + 0.5f

            val cx = padding + xr * width
            val cy = padding + (1f - yr) * height      // vertical flip (cartesian up)

            drawCircle(
                color = if (b.boxId == selectedBoxId) Color.Red else Color.Black,
                radius = 10f,
                center = Offset(cx, cy)
            )
        }
    }

}



@Composable
fun BoxDetailsCard(box: BoxState) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "Details for Box ${box.boxId}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text("Deadline: ${"%.2f".format(box.deadline)} s")
            Text("Disposed X: ${box.disposedX}, Y: ${box.disposedY}")
            Spacer(modifier = Modifier.height(4.dp))
            Text("Sensing results:")
            if (box.senseResults.isEmpty()) {
                Text("- none yet -", style = MaterialTheme.typography.bodySmall)
            } else {
                box.senseResults.forEach { sr ->
                    Text(
                        "Agent=${sr.agentId}, prop=${sr.property}, status=${sr.status}, " +
                                "detected=${sr.detected}, p=${sr.probability}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
