package app.mapa.shared.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mapa.core.family.ParentCard
import app.mapa.routine.RoutineState
import org.json.JSONArray
import org.json.JSONObject

enum class SampleDemoRole { PHONE_OWNER, FAMILY }

private data class DemoDay(
    val interactions: List<Int>,
    val observed: List<Boolean>,
    val charging: List<Boolean>,
    val battery: List<Int>,
    val usualUse: List<Double>,
    val nowBin: Int,
    val usualFirstUseBin: Int,
    val lastUseBin: Int,
    val interactionTotal: Int,
    val observedBins: Int,
    val chargingBins: Int,
) {
    val lastUseMinute: Int get() = lastUseBin * 15
    val usualFirstUseMinute: Int get() = usualFirstUseBin * 15
    val currentBattery: Int get() = battery[nowBin]
    val currentCharging: Boolean get() = charging[nowBin]
}

/** Read the selected model-derived day from the frozen MaPa-Rhythm training dataset sample. */
private fun loadDemoDay(context: Context): DemoDay {
    val json = context.assets.open("demo/sample-day.json").bufferedReader().use { JSONObject(it.readText()) }
    fun JSONArray.ints() = List(length()) { getInt(it) }
    fun JSONArray.booleans() = List(length()) { getBoolean(it) }
    fun JSONArray.doubles() = List(length()) { getDouble(it) }
    val day = DemoDay(
        interactions = json.getJSONArray("interactions").ints(),
        observed = json.getJSONArray("observed").booleans(),
        charging = json.getJSONArray("charging").booleans(),
        battery = json.getJSONArray("battery").ints(),
        usualUse = json.getJSONArray("usual_use_probability").doubles(),
        nowBin = json.getInt("now_bin"),
        usualFirstUseBin = json.getInt("median_first_use_bin"),
        lastUseBin = json.getInt("last_use_bin"),
        interactionTotal = json.getInt("sample_interaction_total"),
        observedBins = json.getInt("sample_observed_bins"),
        chargingBins = json.getInt("sample_charging_bins"),
    )
    require(day.interactions.size == 96 && day.observed.size == 96 && day.usualUse.size == 96) {
        "The MaPa demo fixture must contain 96 quarter-hour bins."
    }
    return day
}

private fun sampleCard(day: DemoDay) = ParentCard(
    state = RoutineState.QUIET,
    stateLabel = "Quieter than usual",
    detail = "The sample has no phone use since ${clockTime(day.lastUseMinute)}. This profile's median first-use time is ${clockTime(day.usualFirstUseMinute)}.",
    updatedAgo = "Dataset sample · 10:45 AM",
    stale = false,
    forecast = "Usual first phone use: around ${clockTime(day.usualFirstUseMinute)}.",
    note = "This is a model-derived dataset example, not a real person's phone record. Quiet phone time has many ordinary explanations.",
    noteByAi = false,
    learning = null,
    modelLine = "MaPa Rhythm Hybrid",
    confidencePct = null,
    battery = day.currentBattery,
    charging = day.currentCharging,
    chart = ParentCard.Chart(
        active = List(96) { bin -> if (day.observed[bin] && day.interactions[bin] > 0) 1 else 0 },
        usual = day.usualUse,
        nowBin = day.nowBin,
        expectedByBin = day.usualFirstUseBin,
    ),
    lastUseMinute = day.lastUseMinute,
    usualFirstMinute = day.usualFirstUseMinute,
)

/** Both demo APKs render the same fixed model-derived dataset sample. */
@Composable
fun SampleDemoScreen(role: SampleDemoRole) {
    val isFamily = role == SampleDemoRole.FAMILY
    val context = LocalContext.current
    val day = remember { loadDemoDay(context) }
    val card = remember(day) { sampleCard(day) }
    var showDraft by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopBar(start = { Wordmark() })
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.xxl),
        ) {
            Spacer(Modifier.height(Space.xl))
            Eyebrow(if (isFamily) "FAMILY APP · DATASET SAMPLE" else "PHONE OWNER APP · DATASET SAMPLE")
            Spacer(Modifier.height(Space.s))
            Text(
                if (isFamily) "A calmer way to check in" else "Your day, on your terms",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Space.s))
            Text(
                if (isFamily) {
                    "The sample timeline is quieter than this model-derived profile's usual morning. MaPa gives the family context without claiming to know why."
                } else {
                    "This example shows the phone-use rhythm shared with family, with no camera, microphone, or location feed."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xl))
            StoryPanel(
                title = "REAL DATASET · MODEL-DERIVED SAMPLE",
                body = "One full day from MaPa-Rhythm final-v2 train: ${day.observedBins} observed 15-minute bins, ${day.interactionTotal} phone interactions, and ${day.chargingBins} charging bins. No participant ID or observed person's timeline is included.",
            )
            Spacer(Modifier.height(Space.l))
            ParentCardView(
                name = if (isFamily) "Alex" else "Your phone",
                card = card,
                labels = CardLabels(
                    today = "Dataset sample day",
                    lastUse = "Last observed phone use",
                    usualFirst = "Profile's usual first use",
                    timeOf = ::clockTime,
                ),
                expanded = isFamily,
            )
            Spacer(Modifier.height(Space.l))
            if (isFamily) {
                FamilyCheckIn(showDraft = showDraft, onToggle = { showDraft = !showDraft })
            } else {
                OwnerPrivacyPanel()
            }
            Spacer(Modifier.height(Space.l))
            Text(
                "Phone activity can support a check-in. It cannot tell a family what a person is doing or whether they need help.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun clockTime(minute: Int): String {
    val hour = (minute / 60) % 12
    return "${if (hour == 0) 12 else hour}:${(minute % 60).toString().padStart(2, '0')} ${if (minute / 60 < 12) "AM" else "PM"}"
}

@Composable
private fun StoryPanel(title: String, body: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OwnerPrivacyPanel() {
    StoryPanel(
        title = "THE PHONE OWNER CONTROLS SHARING",
        body = "The full app asks for consent before sharing and lets the phone owner pause. This demo view uses no account and does not connect to a family member.",
    )
}

@Composable
private fun FamilyCheckIn(showDraft: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("A gentle next step", style = MaterialTheme.typography.titleMedium)
            Text(
                "If it feels right, a simple message can start a conversation. The app does not send it for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onToggle) {
                Text(if (showDraft) "Hide message preview" else "Preview a check-in message")
            }
            if (showDraft) {
                Text("Hi Alex, just checking in. How is your morning going?", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Preview only · no notification, call, or message is sent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
